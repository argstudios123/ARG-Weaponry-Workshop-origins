package com.arg_studios.arg_weaponry.weapon;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsonValues;
import com.arg_studios.arg_weaponry.weapon.aiming.AimZoom;
import com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundKey;
import com.arg_studios.arg_weaponry.weapon.fire.FiringModes;
import com.arg_studios.arg_weaponry.weapon.state.FireMode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns one weapon definition document into a {@link WeaponDefinition}.
 *
 * <p>The parser reports structural and typing problems through {@link ValidationReport} and keeps
 * going, so a creator sees every problem in one pass instead of fixing them one build at a time.</p>
 *
 * <p>Failure policy:</p>
 * <ul>
 *     <li>A missing or invalid <em>required</em> property rejects the definition: it cannot be
 *     interpreted safely, so {@code parse} returns {@code null}.</li>
 *     <li>A malformed <em>optional</em> entry (one animation step, one sound reference) is reported
 *     and skipped; the weapon itself stays usable.</li>
 *     <li>A malformed {@code weapon_id} is reported as an error but does not reject the definition:
 *     the file-derived identity is unambiguous, the written value is preserved verbatim, and no
 *     identifier is invented.</li>
 * </ul>
 *
 * <p>Nothing is written back to the source document.</p>
 */
public final class WeaponDefinitionParser {

    /**
     * Every top-level property this build recognises.
     *
     * <p>{@code screen_shake_Recoil_value} and {@code screen_shake_recoil_speed} are <em>retired</em>. They stay listed
     * here on purpose: a content pack written before the screen-shake removal still carries them, and this way such a pack
     * loads without a spurious "unknown property" finding. Nothing reads them, so they cannot produce any effect - the
     * keys are tolerated and ignored, which is the whole contract for them.</p>
     */
    private static final Set<String> KNOWN_PROPERTIES = Set.of(
            "weapon_name", "weapon_id", "type", "ammo_to_use", "display", "damage", "ammo_capacity", "rpm",
            "aim_zoom", "kickback_recoil_amplitude", "screen_shake_Recoil_value", "screen_shake_recoil_speed",
            "vertical_recoil", "horizontal_recoil", "custom_hud_ui", "aiming", "reusable_default_animations",
            "glowing_weapon_parts", "sounds", "accuracy", "sustained_firing_Recoil", "idle_anims",
            "holster_anims", "draw_anims", "reload_sequences", "fire_anims", "range", "recoil",
            "horizontal_recoil_left", "horizontal_recoil_right", "smoothing", "recovery_speed", "aim_multiplier",
            "crouch_multiplier");

    private static final Set<String> AIMING_KEYS = Set.of("enabled", "no_aiming_on_inspect", "no_aiming_on_reload",
            "aimimg_zoom", "aiming_zoom", "aiming_zoom_type");

    /**
     * Every key the {@code recoil} object may contain, including the two retired screen-shake keys, which are tolerated
     * inside the object as well so a migrated pack keeps loading, and the four optional tuning properties, which resolve
     * exactly like the existing values (nested first, legacy top level second).
     */
    private static final Set<String> RECOIL_KEYS = Set.of("kickback_recoil_amplitude", "screen_shake_Recoil_value",
            "screen_shake_recoil_speed", "vertical_recoil", "horizontal_recoil", "horizontal_recoil_left",
            "horizontal_recoil_right", "smoothing", "recovery_speed", "aim_multiplier", "crouch_multiplier");
    private static final Set<String> ACCURACY_KEYS = Set.of("crouching", "walking", "standing", "aiming");
    private static final Set<String> SUSTAINED_RECOIL_KEYS =
            Set.of("positionZCap", "positionZIncrement", "pitchIncrement", "positionYIncrement");
    private static final Set<String> GLOWING_PART_KEYS = Set.of("name");
    private static final Set<String> ANIMATION_SELECTION_KEYS = Set.of("name", "duration", "when");

    /**
     * Keys a {@code draw_anims} entry may declare. {@code draw_first} (the legacy key-only spelling) and {@code
     * first_draw} (the boolean flag) both mark the first-draw clip; the parser normalizes them into one model, so a pack
     * may use either and both stay readable.
     */
    private static final Set<String> DRAW_SELECTION_KEYS =
            Set.of("name", "draw_first", "first_draw", "duration", "when");
    private static final Set<String> RELOAD_STEP_KEYS = Set.of("phase", "animation", "duration", "when", "repeat");

    /**
     * Upper bound for the optional {@code range} property, in blocks.
     *
     * <p>A hitscan longer than this is a performance and gameplay problem rather than a design choice, so the
     * parser refuses it instead of silently clamping.</p>
     */
    private static final double MAX_RANGE = 256.0;

    private final String packId;
    private final String sourceName;
    private final ResourceId registryId;
    private final ValidationReport report;

    private boolean rejected;

    /**
     * @param packId     the content pack the definition belongs to, for diagnostics
     * @param sourceName the definition file, for diagnostics
     * @param registryId the identity derived from the file location
     * @param report     the report to collect diagnostics in
     */
    public WeaponDefinitionParser(String packId, String sourceName, ResourceId registryId, ValidationReport report) {
        this.packId = packId;
        this.sourceName = sourceName;
        this.registryId = registryId;
        this.report = report;
    }

    /**
     * Parses a weapon definition document.
     *
     * @param root the parsed JSON object
     * @return the definition, or {@code null} when a required property is missing or invalid
     */
    public WeaponDefinition parse(JsonObject root) {
        reportUnknownKeys(root, KNOWN_PROPERTIES, "$");

        String declaredWeaponId = optionalString(root, "weapon_id");
        ResourceId authoritativeId = resolveIdentity(declaredWeaponId);
        String name = requiredString(root, "weapon_name");
        WeaponType type = readType(root);
        ResourceId display = readRequiredResourceId(root, "display");
        double damage = requiredDouble(root, "damage");

        List<String> ammoToUse = optionalStringList(root, "ammo_to_use");
        AmmoCapacity ammoCapacity = readAmmoCapacity(root);
        List<FireMode> firingModes = readFiringModes(root);
        int burstCount = readBurstCount(root);
        boolean continuousBurst = readContinuousBurst(root);
        double timeBetweenBursts = readTimeBetweenBursts(root);
        Integer rpm = optionalInt(root, "rpm");
        Double aimZoom = optionalDouble(root, "aim_zoom");
        String customHudUi = optionalString(root, "custom_hud_ui");

        WeaponDefinition.Recoil recoil = readRecoil(root);
        WeaponDefinition.Aiming aiming = readAiming(root);
        WeaponDefinition.Accuracy accuracy = readAccuracy(root);
        WeaponDefinition.SustainedRecoil sustainedRecoil = readSustainedRecoil(root);
        List<WeaponDefinition.GlowingPart> glowingParts = readGlowingParts(root);
        Map<String, ResourceId> sounds = readSounds(root);
        List<String> reusableAnimations = optionalStringList(root, "reusable_default_animations");

        List<WeaponDefinition.AnimationSelection> idleAnimations = readAnimationSelections(root, "idle_anims");
        List<WeaponDefinition.AnimationSelection> holsterAnimations = readAnimationSelections(root, "holster_anims");
        List<WeaponDefinition.DrawSelection> drawAnimations = readDrawSelections(root);
        List<WeaponDefinition.ReloadStep> reloadSequences = readReloadSteps(root);
        List<WeaponDefinition.AnimationSelection> fireAnimations = readAnimationSelections(root, "fire_anims");
        List<WeaponDefinition.AnimationSelection> inspectAnimations = readAnimationSelections(root, "inspect_anims");
        Double range = optionalDouble(root, "range");

        validateFirearmProperties(type, ammoCapacity, rpm);
        validateRange(range);

        if (rejected) {
            return null;
        }

        return new WeaponDefinition(registryId, authoritativeId, declaredWeaponId, name, type, display, ammoToUse,
                damage, ammoCapacity, firingModes, burstCount, continuousBurst, timeBetweenBursts, rpm, aimZoom, recoil,
                customHudUi, aiming, reusableAnimations, glowingParts, sounds, accuracy, sustainedRecoil, idleAnimations,
                holsterAnimations, drawAnimations, inspectAnimations, reloadSequences, fireAnimations, range, root);
    }

    // --- field readers ---

    private String requiredString(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null) {
            report.error(packId, sourceName, pointer(key), "Required property '" + key + "' is missing");
            rejected = true;
            return null;
        }
        if (!JsonValues.isString(element)) {
            report.error(packId, sourceName, pointer(key),
                    "Expected a string for required property '" + key + "' but found " + JsonValues.describeType(element));
            rejected = true;
            return null;
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            report.error(packId, sourceName, pointer(key), "Required property '" + key + "' is empty");
            rejected = true;
            return null;
        }
        return value;
    }

    private String optionalString(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null) {
            return null;
        }
        if (!JsonValues.isString(element)) {
            report.warning(packId, sourceName, pointer(key), "Expected a string for '" + key + "' but found "
                    + JsonValues.describeType(element) + "; the property is ignored");
            return null;
        }
        return element.getAsString();
    }

    private double requiredDouble(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null) {
            report.error(packId, sourceName, pointer(key), "Required property '" + key + "' is missing");
            rejected = true;
            return 0.0D;
        }
        Double value = JsonValues.asDouble(element);
        if (value == null) {
            report.error(packId, sourceName, pointer(key),
                    "Expected a number for required property '" + key + "' but found " + JsonValues.describeType(element));
            rejected = true;
            return 0.0D;
        }
        return value;
    }

    private Double optionalDouble(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null) {
            return null;
        }
        Double value = JsonValues.asDouble(element);
        if (value == null) {
            report.warning(packId, sourceName, pointer(key), "Expected a number for '" + key + "' but found "
                    + JsonValues.describeType(element) + "; the property is ignored");
        }
        return value;
    }

    private Integer optionalInt(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null) {
            return null;
        }
        Integer value = JsonValues.asInt(element);
        if (value == null) {
            report.warning(packId, sourceName, pointer(key), "Expected a whole number for '" + key + "' but found "
                    + describeValue(element) + "; the property is ignored");
        }
        return value;
    }

    private List<String> optionalStringList(JsonObject root, String key) {
        JsonArray array = optionalArray(root, key);
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            String pointer = pointer(key) + "[" + i + "]";
            if (!JsonValues.isString(element)) {
                report.warning(packId, sourceName, pointer, "Expected a string but found "
                        + JsonValues.describeType(element) + "; the entry is ignored");
                continue;
            }
            String value = element.getAsString();
            if (value.isBlank()) {
                report.warning(packId, sourceName, pointer, "The entry is empty and is ignored");
                continue;
            }
            values.add(value);
        }
        return values;
    }

    private ResourceId readRequiredResourceId(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null) {
            report.error(packId, sourceName, pointer(key), "Required property '" + key + "' is missing");
            rejected = true;
            return null;
        }
        if (!JsonValues.isString(element)) {
            report.error(packId, sourceName, pointer(key), "Expected a resource identifier string for '" + key
                    + "' but found " + JsonValues.describeType(element));
            rejected = true;
            return null;
        }
        ResourceId.Result result = ResourceId.tryParse(element.getAsString());
        if (!result.isValid()) {
            report.error(packId, sourceName, pointer(key), "'" + key + "' is not a valid resource identifier: "
                    + result.error());
            rejected = true;
            return null;
        }
        return result.id();
    }

    // --- container and shared helpers ---

    private JsonObject optionalObject(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null) {
            return null;
        }
        if (!element.isJsonObject()) {
            report.warning(packId, sourceName, pointer(key), "Expected an object for '" + key + "' but found "
                    + JsonValues.describeType(element) + "; the property is ignored");
            return null;
        }
        return element.getAsJsonObject();
    }

    private JsonArray optionalArray(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null) {
            return null;
        }
        if (!element.isJsonArray()) {
            report.warning(packId, sourceName, pointer(key), "Expected an array for '" + key + "' but found "
                    + JsonValues.describeType(element) + "; the property is ignored");
            return null;
        }
        return element.getAsJsonArray();
    }

    private boolean nestedBoolean(JsonObject object, String key, String pointer) {
        JsonElement element = object.get(key);
        if (element == null) {
            return false;
        }
        if (!JsonValues.isBoolean(element)) {
            report.warning(packId, sourceName, pointer, "Expected true or false but found "
                    + JsonValues.describeType(element) + "; assuming false");
            return false;
        }
        return element.getAsBoolean();
    }

    private Double nestedDouble(JsonObject object, String key, String pointer) {
        JsonElement element = object.get(key);
        if (element == null) {
            return null;
        }
        Double value = JsonValues.asDouble(element);
        if (value == null) {
            report.warning(packId, sourceName, pointer, "Expected a number but found "
                    + JsonValues.describeType(element) + "; the property is ignored");
        }
        return value;
    }

    private String nestedString(JsonObject object, String key, String pointer) {
        JsonElement element = object.get(key);
        if (element == null) {
            return null;
        }
        if (!JsonValues.isString(element)) {
            report.warning(packId, sourceName, pointer, "Expected a string but found "
                    + JsonValues.describeType(element) + "; the property is ignored");
            return null;
        }
        return element.getAsString();
    }

    private static String pointer(String key) {
        return "$." + key;
    }

    private static String pointer(String scope, String key) {
        return scope + "." + key;
    }

    private static String describeValue(JsonElement element) {
        if (JsonValues.isNumber(element)) {
            return "the non-integral number " + element.getAsString();
        }
        return JsonValues.describeType(element);
    }

    private WeaponType readType(JsonObject root) {
        JsonElement element = root.get("type");
        if (element == null) {
            report.error(packId, sourceName, pointer("type"), "Required property 'type' is missing");
            rejected = true;
            return null;
        }
        if (!JsonValues.isString(element)) {
            report.error(packId, sourceName, pointer("type"), "Expected a string for 'type' but found "
                    + JsonValues.describeType(element));
            rejected = true;
            return null;
        }
        String value = element.getAsString();
        WeaponType type = WeaponType.fromJson(value);
        if (type == null) {
            report.error(packId, sourceName, pointer("type"), "Unknown weapon type '" + value
                    + "'; expected one of: " + WeaponType.knownNames());
            rejected = true;
            return null;
        }
        if (type != WeaponType.GUN) {
            report.warning(packId, sourceName, pointer("type"), "Weapon type '" + value
                    + "' is recognised but its properties are not validated by this milestone yet");
        }
        return type;
    }

    /**
     * Resolves the authoritative weapon identity from {@code weapon_id}.
     *
     * <p>Item registration uses this value verbatim. When it is missing or malformed the definition is
     * <em>not</em> rejected: it is still parsed, it keeps its file-derived identity for diagnostics, and its
     * resources stay usable, but no Minecraft item can be registered for it. That remaining use is exactly
     * why the problem is reported as an error even though the definition itself is accepted — see
     * {@link com.arg_studios.arg_weaponry.core.ValidationReport} for what an error means here.</p>
     *
     * @return the authoritative identity, or {@code null} when it cannot be established
     */
    private ResourceId resolveIdentity(String declaredWeaponId) {
        if (declaredWeaponId == null) {
            report.error(packId, sourceName, pointer("weapon_id"), "Required property 'weapon_id' is missing. Without "
                    + "it the weapon has no authoritative identity and no Minecraft item can be registered; the "
                    + "definition keeps its file-derived identity '" + registryId + "' for diagnostics");
            return null;
        }

        ResourceId.Result declared = ResourceId.tryParse(declaredWeaponId);
        if (!declared.isValid()) {
            report.error(packId, sourceName, pointer("weapon_id"), "'weapon_id' value '" + declaredWeaponId
                    + "' is not a valid resource identifier: " + declared.error()
                    + ". The value is kept as written; the definition keeps its file-derived identity '"
                    + registryId + "' for diagnostics, but no Minecraft item can be registered for it");
            return null;
        }

        if (!declared.id().equals(registryId)) {
            report.info(packId, sourceName, pointer("weapon_id"), "The definition identity '" + declared.id()
                    + "' differs from its file location '" + registryId
                    + "'; the declared identity is authoritative for item registration");
        }
        return declared.id();
    }

    /**
     * Validates the optional {@code range} property.
     *
     * <p>The range is the length of the server-side hitscan, so an unusable value is rejected rather than
     * defaulted: a non-positive range would make a weapon unable to hit anything, and an unbounded one would
     * turn every shot into a full-length raycast across the world. A definition that declares no range at all
     * is fine — it uses {@link WeaponDefinition#DEFAULT_RANGE}.</p>
     *
     * @param range the declared value, or {@code null}
     */
    private void validateRange(Double range) {
        if (range == null) {
            return;
        }
        if (!(range > 0.0)) {
            report.error(packId, sourceName, pointer("range"),
                    "'range' must be greater than 0 but is " + range);
            rejected = true;
        } else if (range > MAX_RANGE) {
            report.error(packId, sourceName, pointer("range"),
                    "'range' must not exceed " + MAX_RANGE + " blocks but is " + range);
            rejected = true;
        }
    }

    /**
     * Reads {@code ammo_capacity}, which is either a finite magazine size or the reserved unlimited token.
     *
     * <p>Accepted forms:</p>
     * <ul>
     *     <li>a whole number greater than zero - a finite magazine;</li>
     *     <li>the string {@code "unlimited"} (case-insensitive, surrounding whitespace ignored) - unlimited
     *     ammunition, see {@link AmmoCapacity}.</li>
     * </ul>
     * <p>Everything else is an error that rejects the definition rather than being coerced: a non-positive or
     * fractional number, a string that is not the reserved token, an array, an object, {@code null}. The value is
     * never parsed as an integer first and then compared, so {@code "unlimited"} can never end up as {@code 0} or as
     * a silently ignored property; the diagnostic names the value and lists both accepted forms.</p>
     *
     * <p>Absence is not an error here: a weapon type that needs no magazine (melee, throwable) simply has none, and
     * {@link #validateFirearmProperties} requires the property for firearms.</p>
     *
     * @param root the definition document
     * @return the declared capacity, or {@code null} when the property is absent or unusable
     */
    private AmmoCapacity readAmmoCapacity(JsonObject root) {
        String key = "ammo_capacity";
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        String expected = "Expected a whole number greater than 0 or the string '"
                + AmmoCapacity.UNLIMITED_TOKEN + "' for '" + key + "'";
        if (JsonValues.isString(element)) {
            String raw = element.getAsString();
            if (AmmoCapacity.isUnlimitedToken(raw)) {
                return AmmoCapacity.unlimited();
            }
            report.error(packId, sourceName, pointer(key), expected + " but found the string '" + raw
                    + "'. Only '" + AmmoCapacity.UNLIMITED_TOKEN + "' is reserved; no other text is accepted, "
                    + "and it is not an ammunition name");
            rejected = true;
            return null;
        }
        Integer value = JsonValues.asInt(element);
        if (value == null) {
            report.error(packId, sourceName, pointer(key), expected + " but found "
                    + describeValue(element) + "; the definition is rejected because its magazine size cannot be "
                    + "interpreted");
            rejected = true;
            return null;
        }
        if (value <= 0) {
            report.error(packId, sourceName, pointer(key),
                    "'" + key + "' must be greater than 0 but is " + value + " (or use '"
                            + AmmoCapacity.UNLIMITED_TOKEN + "' for unlimited ammunition)");
            rejected = true;
            return null;
        }
        return AmmoCapacity.of(value);
    }

    /**
     * Reads {@code firing_mode}: the ordered list of firing modes this weapon supports.
     *
     * <p>Absent means the documented single-shot fallback, so every definition written before this milestone keeps
     * working. Present means a JSON array of the tokens {@code "semi"}, {@code "auto"} and {@code "burst"}, mapped
     * onto {@link FireMode#SINGLE}, {@link FireMode#AUTO} and {@link FireMode#BURST}. The authored order is preserved
     * because it is the order the B key cycles through.</p>
     *
     * <p>An unsupported token is an error that rejects the definition instead of being dropped: silently ignoring it
     * would hand the player a weapon that cannot fire the mode its own file advertises. Duplicates are reported as a
     * warning and skipped, because the mode set is unchanged. The internal name {@code "single"} is <em>not</em>
     * accepted - the schema token is {@code "semi"} - and the diagnostic says so.</p>
     *
     * @param root the definition document
     * @return the supported modes in authored order, never empty
     */
    private List<FireMode> readFiringModes(JsonObject root) {
        String key = "firing_mode";
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return List.of(FireMode.SINGLE);
        }
        String expected = "Expected an array of firing modes for '" + key + "'; accepted values are '"
                + FiringModes.SEMI_TOKEN + "', '" + FireMode.AUTO.serializedName() + "' and '"
                + FireMode.BURST.serializedName() + "'";
        if (!element.isJsonArray()) {
            report.error(packId, sourceName, pointer(key), expected + " but found " + describeValue(element));
            rejected = true;
            return List.of(FireMode.SINGLE);
        }
        JsonArray array = element.getAsJsonArray();
        List<FireMode> modes = new ArrayList<>(array.size());
        boolean failed = false;
        for (int index = 0; index < array.size(); index++) {
            JsonElement entry = array.get(index);
            String entryPointer = pointer(key) + "[" + index + "]";
            if (!JsonValues.isString(entry)) {
                report.error(packId, sourceName, entryPointer, expected + " but found "
                        + JsonValues.describeType(entry));
                failed = true;
                continue;
            }
            String token = entry.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
            FireMode mode = switch (token) {
                case "semi" -> FireMode.SINGLE;
                case "auto" -> FireMode.AUTO;
                case "burst" -> FireMode.BURST;
                default -> null;
            };
            if (mode == null) {
                String hint = "single".equals(token)
                        ? " The schema token for the single-shot mode is 'semi', not 'single'."
                        : "";
                report.error(packId, sourceName, entryPointer, expected + " but found '" + entry.getAsString() + "'."
                        + hint);
                failed = true;
                continue;
            }
            if (modes.contains(mode)) {
                report.warning(packId, sourceName, entryPointer, "'" + token + "' is declared more than once and is "
                        + "kept only at its first position");
                continue;
            }
            modes.add(mode);
        }
        if (failed) {
            rejected = true;
        }
        if (modes.isEmpty()) {
            report.error(packId, sourceName, pointer(key), "'" + key + "' must declare at least one firing mode");
            rejected = true;
            return List.of(FireMode.SINGLE);
        }
        return List.copyOf(modes);
    }

    /**
     * Reads {@code burst_count}: how many shots one burst fires.
     *
     * @param root the definition document
     * @return the declared count, or {@link WeaponDefinition#DEFAULT_BURST_COUNT} when the property is absent
     */
    private int readBurstCount(JsonObject root) {
        String key = "burst_count";
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return WeaponDefinition.DEFAULT_BURST_COUNT;
        }
        Integer value = JsonValues.asInt(element);
        if (value == null) {
            report.error(packId, sourceName, pointer(key), "Expected a whole number for '" + key + "' but found "
                    + describeValue(element) + "; the definition is rejected because its burst length cannot be "
                    + "interpreted");
            rejected = true;
            return WeaponDefinition.DEFAULT_BURST_COUNT;
        }
        if (value < WeaponDefinition.MIN_BURST_COUNT || value > WeaponDefinition.MAX_BURST_COUNT) {
            report.error(packId, sourceName, pointer(key), "'" + key + "' must be between "
                    + WeaponDefinition.MIN_BURST_COUNT + " and " + WeaponDefinition.MAX_BURST_COUNT + " but is "
                    + value);
            rejected = true;
            return WeaponDefinition.DEFAULT_BURST_COUNT;
        }
        return value;
    }

    /**
     * Reads {@code continuous_burst}, which defaults to {@code false} so a held trigger fires one burst.
     *
     * @param root the definition document
     * @return the declared flag, or {@code false} when the property is absent
     */
    private boolean readContinuousBurst(JsonObject root) {
        String key = "continuous_burst";
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (!JsonValues.isBoolean(element)) {
            report.error(packId, sourceName, pointer(key), "Expected true or false for '" + key + "' but found "
                    + describeValue(element) + "; the definition is rejected");
            rejected = true;
            return false;
        }
        return element.getAsBoolean();
    }

    /**
     * Reads {@code time_between_bursts}, the delay between two bursts in <em>seconds</em>.
     *
     * @param root the definition document
     * @return the declared delay, or {@link WeaponDefinition#DEFAULT_TIME_BETWEEN_BURSTS} when the property is absent
     */
    private double readTimeBetweenBursts(JsonObject root) {
        String key = "time_between_bursts";
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return WeaponDefinition.DEFAULT_TIME_BETWEEN_BURSTS;
        }
        Double value = JsonValues.asDouble(element);
        if (value == null) {
            report.error(packId, sourceName, pointer(key), "Expected a number of seconds for '" + key
                    + "' but found " + describeValue(element) + "; the definition is rejected");
            rejected = true;
            return WeaponDefinition.DEFAULT_TIME_BETWEEN_BURSTS;
        }
        if (!Double.isFinite(value) || value < 0.0 || value > WeaponDefinition.MAX_TIME_BETWEEN_BURSTS) {
            report.error(packId, sourceName, pointer(key), "'" + key + "' must be between 0 and "
                    + WeaponDefinition.MAX_TIME_BETWEEN_BURSTS + " seconds but is " + value);
            rejected = true;
            return WeaponDefinition.DEFAULT_TIME_BETWEEN_BURSTS;
        }
        return value;
    }

    private void validateFirearmProperties(WeaponType type, AmmoCapacity ammoCapacity, Integer rpm) {
        if (type != WeaponType.GUN) {
            return;
        }
        if (ammoCapacity == null) {
            report.error(packId, sourceName, pointer("ammo_capacity"),
                    "Firearms require 'ammo_capacity': a whole number greater than 0, or '"
                            + AmmoCapacity.UNLIMITED_TOKEN + "'");
            rejected = true;
        }
        if (rpm == null) {
            report.error(packId, sourceName, pointer("rpm"), "Firearms require 'rpm'");
            rejected = true;
        } else if (rpm <= 0) {
            report.error(packId, sourceName, pointer("rpm"), "'rpm' must be greater than 0 but is " + rpm);
            rejected = true;
        }
    }

    private WeaponDefinition.Aiming readAiming(JsonObject root) {
        JsonObject aiming = optionalObject(root, "aiming");
        if (aiming == null) {
            return null;
        }
        reportUnknownKeys(aiming, AIMING_KEYS, "$.aiming");
        return new WeaponDefinition.Aiming(
                nestedBoolean(aiming, "enabled", "$.aiming.enabled"),
                nestedBoolean(aiming, "no_aiming_on_inspect", "$.aiming.no_aiming_on_inspect"),
                nestedBoolean(aiming, "no_aiming_on_reload", "$.aiming.no_aiming_on_reload"),
                readAimZoom(aiming),
                readAimZoomType(aiming));
    }

    /**
     * Reads the ADS field-of-view multiplier.
     *
     * <p>{@code aimimg_zoom} is the documented spelling and keeps working exactly as content packs already write it;
     * {@code aiming_zoom} is accepted as an alias. When a file declares both, the documented {@code aimimg_zoom} wins - the
     * precedence is fixed here so a pack can never depend on file order - and the duplicate is reported, because a file
     * carrying both spellings is almost certainly a copy/paste accident.</p>
     *
     * @param aiming the {@code aiming} object
     * @return a usable multiplier, always finite, positive and at most {@code 1.0}
     */
    private double readAimZoom(JsonObject aiming) {
        Double legacy = optionalDouble(aiming, "aimimg_zoom");
        Double alias = optionalDouble(aiming, "aiming_zoom");
        if (legacy != null && alias != null) {
            report.warning(packId, sourceName, "$.aiming.aiming_zoom",
                    "Both 'aimimg_zoom' (" + legacy + ") and 'aiming_zoom' (" + alias + ") are declared; 'aimimg_zoom' "
                            + "is used, because it is the documented property");
        }
        Double configured = legacy != null ? legacy : alias;
        if (configured == null) {
            return AimZoom.DEFAULT_ZOOM;
        }
        String problem = AimZoom.describeProblem(configured);
        if (problem != null) {
            report.warning(packId, sourceName, legacy != null ? "$.aiming.aimimg_zoom" : "$.aiming.aiming_zoom",
                    problem);
        }
        return AimZoom.sanitise(configured);
    }

    /**
     * Reads the easing name of the zoom transition, reporting an unknown one.
     *
     * @param aiming the {@code aiming} object
     * @return the configured name as written, or {@code null} when the weapon declares none
     */
    private String readAimZoomType(JsonObject aiming) {
        JsonElement element = aiming.get("aiming_zoom_type");
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            report.warning(packId, sourceName, "$.aiming.aiming_zoom_type",
                    "Expected a string naming an easing curve but found " + JsonValues.describeType(element)
                            + "; the transition falls back to 'linear'");
            return null;
        }
        String name = element.getAsString();
        if (ZoomCurve.lookup(name) == null) {
            report.warning(packId, sourceName, "$.aiming.aiming_zoom_type",
                    "Unknown easing curve '" + name + "'; the transition falls back to 'linear'. Known curves: "
                            + String.join(", ", ZoomCurve.canonicalNames()));
        }
        return name;
    }

    /**
     * Reads the recoil configuration, nested object first and legacy top-level values second.
     *
     * <p>Milestone 10S added the nested {@code recoil} object. Every value resolves independently, so a pack can move one
     * property into the object and leave the rest at the top level while it migrates, and a nested value always wins over
     * the legacy top-level one for that same value.</p>
     *
     * <p>The retired {@code screen_shake_Recoil_value} and {@code screen_shake_recoil_speed} keys are deliberately not
     * read: they are still accepted (they are part of {@link #RECOIL_KEYS} and {@link #KNOWN_PROPERTIES}, so an older pack
     * produces no unknown-property finding and still loads), but no value is taken from them, so nothing downstream can
     * turn them into a camera effect.</p>
     *
     * <p>Package-private seam rather than a private helper: the recoil schema is plain JSON, while a full
     * {@link #parse(JsonObject)} needs the game's own classes (through
     * {@link com.arg_studios.arg_weaponry.weapon.state.FireMode}), so the retired-key tolerance is verified on this method
     * exactly as it is used by a definition.</p>
     *
     * <p>The four tuning properties ({@code smoothing}, {@code recovery_speed}, {@code aim_multiplier},
     * {@code crouch_multiplier}) are optional and resolve through the same {@link #recoilValue} helper as the existing
     * values, so a pack may write them in the nested object or at the legacy top level. An absent or non-numeric value
     * stays {@code null} here; the documented defaults and limits are applied once, in
     * {@link com.arg_studios.arg_weaponry.client.recoil.RecoilProfile}.</p>
     *
     * @param root the weapon definition
     * @return the resolved configuration, or {@code null} when the weapon declares no recoil at all
     */
    WeaponDefinition.Recoil readRecoil(JsonObject root) {
        JsonObject nested = optionalObject(root, "recoil");
        if (nested != null) {
            reportUnknownKeys(nested, RECOIL_KEYS, "$.recoil");
        }
        WeaponDefinition.Recoil recoil = new WeaponDefinition.Recoil(
                recoilValue(nested, root, "kickback_recoil_amplitude"),
                recoilValue(nested, root, "vertical_recoil"),
                recoilValue(nested, root, "horizontal_recoil"),
                recoilValue(nested, root, "horizontal_recoil_left"),
                recoilValue(nested, root, "horizontal_recoil_right"),
                recoilValue(nested, root, "smoothing"),
                recoilValue(nested, root, "recovery_speed"),
                recoilValue(nested, root, "aim_multiplier"),
                recoilValue(nested, root, "crouch_multiplier"));
        if (recoil.usesLegacyHorizontal()) {
            report.info(packId, sourceName, "$.recoil",
                    "The weapon uses the legacy symmetrical 'horizontal_recoil', which keeps its original meaning; "
                            + "'horizontal_recoil_left' and 'horizontal_recoil_right' configure each direction instead");
        }
        return recoil.isEmpty() ? null : recoil;
    }

    /**
     * Reads one recoil value: the nested value when present, otherwise the legacy top-level value.
     *
     * @param nested the {@code recoil} object, may be {@code null}
     * @param root   the weapon definition that carries the legacy top-level value
     * @param key    the property name, identical in both places
     * @return the resolved value, or {@code null} when neither place declares it
     */
    private Double recoilValue(JsonObject nested, JsonObject root, String key) {
        if (nested != null) {
            Double value = optionalDouble(nested, key);
            if (value != null) {
                return value;
            }
        }
        return optionalDouble(root, key);
    }

    private WeaponDefinition.Accuracy readAccuracy(JsonObject root) {
        JsonObject accuracy = optionalObject(root, "accuracy");
        if (accuracy == null) {
            return null;
        }
        reportUnknownKeys(accuracy, ACCURACY_KEYS, "$.accuracy");
        return new WeaponDefinition.Accuracy(
                nestedDouble(accuracy, "crouching", "$.accuracy.crouching"),
                nestedDouble(accuracy, "walking", "$.accuracy.walking"),
                nestedDouble(accuracy, "standing", "$.accuracy.standing"),
                nestedDouble(accuracy, "aiming", "$.accuracy.aiming"));
    }

    private WeaponDefinition.SustainedRecoil readSustainedRecoil(JsonObject root) {
        JsonObject sustained = optionalObject(root, "sustained_firing_Recoil");
        if (sustained == null) {
            return null;
        }
        reportUnknownKeys(sustained, SUSTAINED_RECOIL_KEYS, "$.sustained_firing_Recoil");
        return new WeaponDefinition.SustainedRecoil(
                nestedDouble(sustained, "positionZCap", "$.sustained_firing_Recoil.positionZCap"),
                nestedDouble(sustained, "positionZIncrement", "$.sustained_firing_Recoil.positionZIncrement"),
                nestedDouble(sustained, "pitchIncrement", "$.sustained_firing_Recoil.pitchIncrement"),
                nestedDouble(sustained, "positionYIncrement", "$.sustained_firing_Recoil.positionYIncrement"));
    }

    /**
     * Reports properties that are not part of the modelled schema. They are never rejected, because
     * the schema is meant to grow; the source document keeps them for later milestones.
     *
     * @param scope the JSON pointer prefix used for the diagnostic, for example {@code $.aiming}
     */
    private void reportUnknownKeys(JsonObject object, Set<String> known, String scope) {
        for (String key : object.keySet()) {
            if (!known.contains(key)) {
                report.info(packId, sourceName, pointer(scope, key),
                        "Unknown property '" + key + "' is preserved but not interpreted by this milestone");
            }
        }
    }

    private List<WeaponDefinition.GlowingPart> readGlowingParts(JsonObject root) {
        JsonArray array = optionalArray(root, "glowing_weapon_parts");
        if (array == null) {
            return List.of();
        }

        List<WeaponDefinition.GlowingPart> parts = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            String entryPointer = pointer("glowing_weapon_parts") + "[" + i + "]";
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                report.warning(packId, sourceName, entryPointer, "Expected an object but found "
                        + JsonValues.describeType(element) + "; the entry is ignored");
                continue;
            }
            JsonObject part = element.getAsJsonObject();
            reportUnknownKeys(part, GLOWING_PART_KEYS, entryPointer);
            String name = nestedString(part, "name", entryPointer + ".name");
            if (name == null || name.isBlank()) {
                report.warning(packId, sourceName, entryPointer,
                        "The entry has no usable 'name' and is ignored");
                continue;
            }
            parts.add(new WeaponDefinition.GlowingPart(name));
        }
        return parts;
    }

    /**
     * Reads the {@code sounds} object: an optional map from a sound key to a resource identifier.
     *
     * <p>Every key is optional and independently optional, so the whole reader is tolerant: a missing object yields an
     * empty map (the weapon is silent, which is a valid configuration), and a missing, {@code null}, empty or
     * whitespace-only value is treated as "not configured" and dropped. Only a value that <em>is</em> a string but is
     * not a valid resource identifier is reported as a warning, and even then only that one key is dropped - a broken
     * sound entry never rejects the weapon, because sounds are presentation and gameplay must keep working without
     * them.</p>
     *
     * <p>An unknown key is reported once and ignored: the gameplay code asks for sounds by meaning
     * ({@link WeaponSoundKey}), so a typo would otherwise be an entry nothing ever plays.</p>
     *
     * @param root the weapon document
     * @return the declared, valid sound references, in file order; never {@code null}
     */
    private Map<String, ResourceId> readSounds(JsonObject root) {
        JsonElement element = root.get("sounds");
        if (element == null || element.isJsonNull()) {
            return Map.of();
        }
        if (!element.isJsonObject()) {
            report.warning(packId, sourceName, pointer("sounds"), "Expected an object of sound identifiers but found "
                    + JsonValues.describeType(element) + "; the weapon stays silent");
            return Map.of();
        }

        Map<String, ResourceId> references = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            String entryPointer = "$.sounds." + key;
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                // Explicitly unconfigured. Documented equivalent of omitting the key.
                continue;
            }
            if (!JsonValues.isString(value)) {
                report.warning(packId, sourceName, entryPointer, "Expected a resource identifier string but found "
                        + JsonValues.describeType(value) + "; the sound key is ignored");
                continue;
            }
            String text = value.getAsString();
            if (text == null || text.isBlank()) {
                // An empty string is unconfigured, not an error: it is how a creator disables one key in place.
                continue;
            }
            if (WeaponSoundKey.byJsonName(key) == null) {
                report.warning(packId, sourceName, entryPointer, "Unknown weapon sound key '" + key
                        + "'; known keys are " + String.join(", ", WeaponSoundKey.jsonNames())
                        + ". The entry is ignored");
                continue;
            }
            ResourceId.Result reference = ResourceId.tryParse(text);
            if (!reference.isValid()) {
                report.warning(packId, sourceName, entryPointer, "Sound reference '" + text
                        + "' is not a valid resource identifier: " + reference.error() + "; the sound key is ignored");
                continue;
            }
            references.put(key, reference.id());
        }
        return references;
    }

    private List<WeaponDefinition.AnimationSelection> readAnimationSelections(JsonObject root, String key) {
        JsonArray array = optionalArray(root, key);
        if (array == null) {
            return List.of();
        }

        List<WeaponDefinition.AnimationSelection> selections = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            String entryPointer = pointer(key) + "[" + i + "]";
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                report.warning(packId, sourceName, entryPointer, "Expected an object but found "
                        + JsonValues.describeType(element) + "; the entry is ignored");
                continue;
            }

            JsonObject entry = element.getAsJsonObject();
            reportUnknownKeys(entry, ANIMATION_SELECTION_KEYS, entryPointer);
            String name = nestedString(entry, "name", entryPointer + ".name");
            Double duration = nestedDouble(entry, "duration", entryPointer + ".duration");

            if (name == null || name.isBlank() || duration == null) {
                report.warning(packId, sourceName, entryPointer,
                        "The entry needs a 'name' and a 'duration' and is ignored");
                continue;
            }
            if (duration <= 0) {
                report.warning(packId, sourceName, entryPointer + ".duration",
                        "The duration must be greater than 0 but is " + duration + "; the entry is ignored");
                continue;
            }

            selections.add(new WeaponDefinition.AnimationSelection(name, duration,
                    nestedString(entry, "when", entryPointer + ".when")));
        }
        return selections;
    }

    /**
     * Reads the {@code draw_anims} entries and normalizes both first-draw spellings into one model.
     *
     * <p>Accepted declarations of the first-draw clip, both normalized to
     * {@link WeaponDefinition.DrawSelection#firstDraw()} = {@code true}:</p>
     * <pre>
     *   { "draw_first": "anim.model.draw_first", "duration": 1.2 }                 // key-only form
     *   { "name": "anim.model.draw_first", "first_draw": true, "duration": 1.2 }   // flag form
     * </pre>
     * <p>The {@code name} property always wins when both spellings are present, so the legacy key marks an entry as the
     * first-draw clip only when it is what supplied the name; {@code first_draw} is an independent boolean and works with
     * either spelling. Parsing stays lenient: an entry without a usable name or duration, or with a non-positive
     * duration, is reported with the weapon and skipped, and a non-boolean {@code first_draw} is reported and treated as
     * {@code false} - the pack never fails to load because of a draw entry.</p>
     *
     * <p>Package-private seam rather than a private helper, the same precedent as {@link #readRecoil}: a full
     * {@link #parse(JsonObject)} needs the game's own classes (through
     * {@link com.arg_studios.arg_weaponry.weapon.state.FireMode}), while this schema is plain JSON, so the
     * normalization is verified on exactly the method a definition goes through.</p>
     *
     * @param root the weapon definition
     * @return the entries, in file order, never {@code null}
     */
    List<WeaponDefinition.DrawSelection> readDrawSelections(JsonObject root) {
        JsonArray array = optionalArray(root, "draw_anims");
        if (array == null) {
            return List.of();
        }

        List<WeaponDefinition.DrawSelection> selections = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            String entryPointer = pointer("draw_anims") + "[" + i + "]";
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                report.warning(packId, sourceName, entryPointer, "Expected an object but found "
                        + JsonValues.describeType(element) + "; the entry is ignored");
                continue;
            }

            JsonObject entry = element.getAsJsonObject();
            reportUnknownKeys(entry, DRAW_SELECTION_KEYS, entryPointer);

            String name = nestedString(entry, "name", entryPointer + ".name");
            boolean declaredWithDrawFirstKey = false;
            String legacyDrawFirst = nestedString(entry, "draw_first", entryPointer + ".draw_first");
            if (name == null && legacyDrawFirst != null && !legacyDrawFirst.isBlank()) {
                // The key-only spelling: the property itself supplies the animation name.
                name = legacyDrawFirst;
                declaredWithDrawFirstKey = true;
            }
            boolean firstDraw = declaredWithDrawFirstKey
                    || nestedBoolean(entry, "first_draw", entryPointer + ".first_draw");

            Double duration = nestedDouble(entry, "duration", entryPointer + ".duration");
            if (name == null || name.isBlank() || duration == null) {
                report.warning(packId, sourceName, entryPointer,
                        "The entry needs a 'name' (or 'draw_first') and a 'duration' and is ignored");
                continue;
            }
            if (duration <= 0) {
                report.warning(packId, sourceName, entryPointer + ".duration",
                        "The duration must be greater than 0 but is " + duration + "; the entry is ignored");
                continue;
            }

            if (declaredWithDrawFirstKey) {
                report.info(packId, sourceName, entryPointer,
                        "'draw_first' supplied this entry's animation name and is normalized to the first_draw flag; "
                                + "the clip is used only while the stack's first-draw flag is unset. The file was not "
                                + "modified");
            }

            selections.add(new WeaponDefinition.DrawSelection(name, duration,
                    nestedString(entry, "when", entryPointer + ".when"), declaredWithDrawFirstKey, firstDraw));
        }
        return selections;
    }

    // --- reload reader ---

    private List<WeaponDefinition.ReloadStep> readReloadSteps(JsonObject root) {
        JsonArray array = optionalArray(root, "reload_sequences");
        if (array == null) {
            return List.of();
        }

        List<WeaponDefinition.ReloadStep> steps = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            String entryPointer = pointer("reload_sequences") + "[" + i + "]";
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                report.warning(packId, sourceName, entryPointer, "Expected an object but found "
                        + JsonValues.describeType(element) + "; the entry is ignored");
                continue;
            }

            JsonObject entry = element.getAsJsonObject();
            reportUnknownKeys(entry, RELOAD_STEP_KEYS, entryPointer);

            String phase = nestedString(entry, "phase", entryPointer + ".phase");
            String animation = nestedString(entry, "animation", entryPointer + ".animation");
            Double duration = nestedDouble(entry, "duration", entryPointer + ".duration");

            Boolean repeat = null;
            JsonElement repeatElement = entry.get("repeat");
            if (repeatElement != null) {
                if (JsonValues.isBoolean(repeatElement)) {
                    repeat = repeatElement.getAsBoolean();
                    report.info(packId, sourceName, entryPointer + ".repeat",
                            "The 'repeat' flag is parsed but not acted on by this milestone");
                } else {
                    report.warning(packId, sourceName, entryPointer + ".repeat", "Expected true or false but found "
                            + JsonValues.describeType(repeatElement) + "; the flag is ignored");
                }
            }

            if (phase == null || phase.isBlank()) {
                report.warning(packId, sourceName, entryPointer, "The entry has no 'phase' and is ignored");
                continue;
            }
            if (animation == null || animation.isBlank()) {
                report.warning(packId, sourceName, entryPointer, "The entry has no 'animation' and is ignored");
                continue;
            }
            if (duration == null || duration <= 0) {
                report.warning(packId, sourceName, entryPointer,
                        "The entry needs a positive 'duration' and is ignored");
                continue;
            }

            steps.add(new WeaponDefinition.ReloadStep(phase, animation, duration,
                    nestedString(entry, "when", entryPointer + ".when"), repeat));
        }
        return steps;
    }
}

