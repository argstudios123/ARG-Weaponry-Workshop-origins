package com.arg_studios.arg_weaponry.weapon;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsonValues;
import com.arg_studios.arg_weaponry.weapon.aiming.AimZoom;
import com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponSwayStyle;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundKey;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundSet;
import com.arg_studios.arg_weaponry.weapon.fire.FiringModes;
import com.arg_studios.arg_weaponry.weapon.state.FireMode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
            "firing_mode", "burst_count", "continuous_burst", "time_between_bursts",
            "aim_zoom", "kickback_recoil_amplitude", "screen_shake_Recoil_value", "screen_shake_recoil_speed",
            "vertical_recoil", "horizontal_recoil", "custom_hud_ui", "aiming", "reusable_default_animations",
            "glowing_weapon_parts", "muzzle_flash", "sounds", "accuracy", "sustained_firing_Recoil", "idle_anims",
            "holster_anims", "draw_anims", "reload_sequences", "fire_anims", "inspect_anims", "range", "recoil",
            "horizontal_recoil_left", "horizontal_recoil_right", "smoothing", "recovery_speed", "aim_multiplier",
            "weapons_sway", "allow_firing_when_sprinting", "bullets_amount", "shell_amount_reload",
            "crouch_multiplier");

    private static final Set<String> AIMING_KEYS = Set.of("enabled", "no_aiming_on_inspect", "no_aiming_on_reload",
            "aiming_zoom", "aimimg_zoom", "aiming_zoom_type", "aiming_animation_type", "aiming_speed",
            "aiming_zoom_speed", "aim_in", "aim_out", "ads_constraint", "ads_reload_zoom_control");

    /**
     * @return every top-level property this build recognises
     *
     * <p>Package-private seam so a test can prove that a shipped definition produces no "unknown property" finding -
     * the check the schema grows against. It returns an immutable set and is not part of the public API.</p>
     */
    static Set<String> knownProperties() {
        return KNOWN_PROPERTIES;
    }

    private static final Set<String> ADS_CONSTRAINT_KEYS = Set.of("enabled", "camera", "camera_constraint_modifier");

    private static final Set<String> ADS_RELOAD_ZOOM_KEYS = Set.of("enabled", "zoom_effect_type", "zoom_amount");

    /**
     * Every key the {@code recoil} object may contain, including the two retired screen-shake keys, which are tolerated
     * inside the object as well so a migrated pack keeps loading, and the four optional tuning properties, which resolve
     * exactly like the existing values (nested first, legacy top level second).
     */
    private static final Set<String> RECOIL_KEYS = Set.of("kickback_recoil_amplitude", "kickback_aiming_modifier",
            "screen_shake_Recoil_value", "screen_shake_recoil_speed", "vertical_recoil", "horizontal_recoil",
            "horizontal_recoil_left", "horizontal_recoil_right", "smoothing", "recovery_speed", "aim_multiplier",
            "crouch_multiplier");
    private static final Set<String> ACCURACY_KEYS = Set.of("crouching", "walking", "standing", "aiming");

    /** Every key the optional {@code weapons_sway} object may contain. */
    private static final Set<String> SWAY_KEYS = Set.of("enabled", "weapon_sway_type", "intensity", "smoothing",
            "sway_sensitivity", "return_speed", "aim_multiplier");
    private static final Set<String> SUSTAINED_RECOIL_KEYS =
            Set.of("positionZCap", "positionZIncrement", "pitchIncrement", "positionYIncrement");
    /** Every key a {@code glowing_weapon_parts} entry may declare. */
    private static final Set<String> GLOWING_PART_KEYS = Set.of("part_name", "name");

    /** Every key the optional {@code muzzle_flash.sprites} object may contain. */
    private static final Set<String> MUZZLE_FLASH_SPRITE_KEYS =
            Set.of("rows", "columns", "fps", "type");

    /** Every key the optional {@code muzzle_flash} object may contain. */
    private static final Set<String> MUZZLE_FLASH_KEYS =
            Set.of("enabled", "bone", "bones", "texture", "size", "sprites", "duration_ms");
    private static final Set<String> ANIMATION_SELECTION_KEYS = Set.of("name", "duration", "when");

    /**
     * Keys a {@code draw_anims} entry may declare. {@code draw_first} (the legacy key-only spelling) and {@code
     * first_draw} (the boolean flag) both mark the first-draw clip; the parser normalizes them into one model, so a pack
     * may use either and both stay readable.
     */
    private static final Set<String> DRAW_SELECTION_KEYS =
            Set.of("name", "draw_first", "first_draw", "duration", "when");
    /** Every key a {@code reload_sequences} entry may declare. {@code stage} and {@code phase} are synonyms. */
    private static final Set<String> RELOAD_STEP_KEYS =
            Set.of("stage", "phase", "animation", "duration", "when", "repeat", "insert_at",
                    "transition_duration");

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
        // Read for validation only: the value is part of the retained source document, and the definition exposes it
        // through allowFiringWhenSprinting(). A wrongly typed value is reported and the documented default (true) applies.
        readAllowFiringWhenSprinting(root);
        readShellAmountReload(root);

        WeaponDefinition.Recoil recoil = readRecoil(root);
        WeaponDefinition.Aiming aiming = readAiming(root);
        WeaponDefinition.Accuracy accuracy = readAccuracy(root);
        WeaponDefinition.SustainedRecoil sustainedRecoil = readSustainedRecoil(root);
        WeaponDefinition.Sway sway = readSway(root);
        List<WeaponDefinition.GlowingPart> glowingParts = readGlowingParts(root);
        WeaponDefinition.MuzzleFlash muzzleFlash = readMuzzleFlash(root);
        DeclaredSounds sounds = readSounds(root);
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
                customHudUi, aiming, reusableAnimations, glowingParts, muzzleFlash, sounds.primary(), sounds.sets(), accuracy,
                sustainedRecoil, idleAnimations,
                holsterAnimations, drawAnimations, inspectAnimations, reloadSequences, fireAnimations, range, sway, root);
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

    /**
     * Reads an optional boolean, reporting a wrongly typed value instead of silently flipping it.
     *
     * @param root the object to read from
     * @param key  the property name
     * @return the value, or {@code null} when the property is absent or not a boolean
     */
    private Boolean optionalBoolean(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null) {
            return null;
        }
        if (!JsonValues.isBoolean(element)) {
            report.warning(packId, sourceName, pointer(key), "Expected true or false for '" + key + "' but found "
                    + JsonValues.describeType(element) + "; the documented default is used");
            return null;
        }
        return element.getAsBoolean();
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
        return nestedBoolean(object, key, pointer, false);
    }

    /**
     * Reads a boolean nested in an object, falling back to a documented default.
     *
     * @param object   the containing object
     * @param key      the property name
     * @param pointer  the JSON pointer used for diagnostics
     * @param fallback the value to use when the property is absent or malformed
     * @return the parsed boolean, or {@code fallback}
     */
    private boolean nestedBoolean(JsonObject object, String key, String pointer, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!JsonValues.isBoolean(element)) {
            report.warning(packId, sourceName, pointer, "Expected true or false but found "
                    + JsonValues.describeType(element) + "; using " + fallback);
            return fallback;
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

    /**
     * Validates {@code shell_amount_reload}, the number of rounds one insertion cycle moves.
     *
     * <p>The value is read from the retained source document by
     * {@link WeaponDefinition#shellAmountReload()}; this reader only makes sure a definition that declares it declares
     * something usable. An unusable value rejects the definition instead of being ignored, for the same reason a broken
     * {@code ammo_capacity} does: silently falling back would hand the player a weapon that loads a different amount
     * per cycle than its own file says.</p>
     *
     * <p>Absence is fine - it means {@link WeaponDefinition#DEFAULT_SHELL_AMOUNT_RELOAD} - and so is a definition
     * without staged reload stages, which never inserts per cycle at all.</p>
     *
     * @param root the definition document
     */
    private void readShellAmountReload(JsonObject root) {
        String key = "shell_amount_reload";
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return;
        }
        Integer value = JsonValues.asInt(element);
        if (value == null) {
            report.error(packId, sourceName, pointer(key), "Expected a whole number for '" + key + "' but found "
                    + describeValue(element) + "; the definition is rejected because the amount loaded per cycle "
                    + "cannot be interpreted");
            rejected = true;
            return;
        }
        if (value < WeaponDefinition.MIN_SHELL_AMOUNT_RELOAD || value > WeaponDefinition.MAX_SHELL_AMOUNT_RELOAD) {
            report.error(packId, sourceName, pointer(key), "'" + key + "' must be between "
                    + WeaponDefinition.MIN_SHELL_AMOUNT_RELOAD + " and " + WeaponDefinition.MAX_SHELL_AMOUNT_RELOAD
                    + " but is " + value);
            rejected = true;
        }
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

    WeaponDefinition.Aiming readAiming(JsonObject root) {
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
                readEasingName(aiming, "aiming_zoom_type"),
                readEasingName(aiming, "aiming_animation_type"),
                readAimSpeed(aiming, "aiming_speed"),
                readAimSpeed(aiming, "aiming_zoom_speed"),
                nestedString(aiming, "aim_in", "$.aiming.aim_in"),
                nestedString(aiming, "aim_out", "$.aiming.aim_out"),
                readAdsConstraint(aiming),
                readAdsReloadZoom(aiming));
    }

    /**
     * Reads the optional {@code aiming.ads_constraint} block.
     *
     * <p>{@code enabled} defaults to {@code false}, {@code camera} to {@code true} (the reference implementation's
     * defaults). {@code camera_constraint_modifier} is a <em>retention</em> fraction, not a multiplier on top of the
     * rotation: at full ADS the camera keeps that fraction of its animated movement. When the property is absent, the
     * model's {@code ads_offset} bone supplies the retention per axis, exactly like the reference implementation.</p>
     *
     * @param aiming the {@code aiming} object
     * @return the configuration, never {@code null}
     */
    WeaponDefinition.AdsConstraint readAdsConstraint(JsonObject aiming) {
        JsonObject block = optionalObject(aiming, "ads_constraint");
        if (block == null) {
            return WeaponDefinition.AdsConstraint.DISABLED;
        }
        reportUnknownKeys(block, ADS_CONSTRAINT_KEYS, "$.aiming.ads_constraint");
        boolean enabled = nestedBoolean(block, "enabled", "$.aiming.ads_constraint.enabled", false);
        boolean camera = nestedBoolean(block, "camera", "$.aiming.ads_constraint.camera", true);
        Double modifier = nestedDouble(block, "camera_constraint_modifier",
                "$.aiming.ads_constraint.camera_constraint_modifier");
        double retention = Double.NaN;
        if (modifier != null) {
            if (!Double.isFinite(modifier)) {
                report.warning(packId, sourceName, "$.aiming.ads_constraint.camera_constraint_modifier",
                        "The camera constraint modifier must be a finite retention fraction; the ads_offset bone "
                                + "values are used instead");
            } else {
                retention = modifier;
                if (modifier > 1.0) {
                    report.warning(packId, sourceName, "$.aiming.ads_constraint.camera_constraint_modifier",
                            "The camera constraint modifier " + modifier + " is a retention fraction; it is clamped to 1.0");
                } else if (modifier < 0.0) {
                    report.warning(packId, sourceName, "$.aiming.ads_constraint.camera_constraint_modifier",
                            "The camera constraint modifier " + modifier + " is a retention fraction; it is clamped to 0.0");
                }
            }
        }
        return new WeaponDefinition.AdsConstraint(enabled, camera, retention);
    }

    /**
     * Reads the optional {@code aiming.ads_reload_zoom_control} block.
     *
     * <p>{@code zoom_amount} is the fraction of the current aiming zoom removed at full effect, not an absolute zoom.
     * {@code zoom_effect_type} uses the same easing vocabulary as the ordinary aiming transition; an unknown name is
     * reported and the reference implementation's {@code smooth} fallback is used.</p>
     *
     * @param aiming the {@code aiming} object
     * @return the configuration, never {@code null}
     */
    WeaponDefinition.AdsReloadZoom readAdsReloadZoom(JsonObject aiming) {
        JsonObject block = optionalObject(aiming, "ads_reload_zoom_control");
        if (block == null) {
            return WeaponDefinition.AdsReloadZoom.DISABLED;
        }
        reportUnknownKeys(block, ADS_RELOAD_ZOOM_KEYS, "$.aiming.ads_reload_zoom_control");
        boolean enabled = nestedBoolean(block, "enabled", "$.aiming.ads_reload_zoom_control.enabled", false);
        String effectType = nestedString(block, "zoom_effect_type", "$.aiming.ads_reload_zoom_control.zoom_effect_type");
        if (effectType != null && !effectType.isBlank() && ZoomCurve.lookup(effectType) == null) {
            report.warning(packId, sourceName, "$.aiming.ads_reload_zoom_control.zoom_effect_type",
                    "Unknown reload zoom easing '" + effectType + "'; the effect falls back to 'smooth'. Known curves: "
                            + String.join(", ", ZoomCurve.canonicalNames()));
        }
        Double amount = nestedDouble(block, "zoom_amount", "$.aiming.ads_reload_zoom_control.zoom_amount");
        if (amount != null) {
            if (!Double.isFinite(amount) || amount < 0.0) {
                report.warning(packId, sourceName, "$.aiming.ads_reload_zoom_control.zoom_amount",
                        "The reload zoom amount must be a fraction in [0, 1], but it is " + amount
                                + "; the effect is disabled");
            } else if (amount > 1.0) {
                report.warning(packId, sourceName, "$.aiming.ads_reload_zoom_control.zoom_amount",
                        "The reload zoom amount " + amount + " is a fraction in [0, 1]; it is clamped to 1.0");
            }
        }
        return new WeaponDefinition.AdsReloadZoom(enabled, effectType,
                amount == null ? 0.0 : amount);
    }

    /**
     * Reads the optional {@code weapons_sway} block.
     *
     * <p>Every property is optional and independently falls back to {@link WeaponDefinition.Sway#DEFAULT}, so a pack that
     * declares nothing - or only one of the five values - keeps a natural sway. The block is never required: an absent
     * {@code weapons_sway} returns the defaults, which is what keeps every existing content pack working unchanged.</p>
     *
     * @param root the weapon document
     * @return the resolved configuration, never {@code null}
     */
    WeaponDefinition.Sway readSway(JsonObject root) {
        JsonObject sway = optionalObject(root, "weapons_sway");
        if (sway == null) {
            return WeaponDefinition.Sway.DEFAULT;
        }
        reportUnknownKeys(sway, SWAY_KEYS, "$.weapons_sway");
        WeaponDefinition.Sway defaults = WeaponDefinition.Sway.DEFAULT;
        Boolean enabled = optionalBoolean(sway, "enabled");
        WeaponSwayStyle style = readSwayStyle(sway, defaults.style());
        Double intensity = optionalDouble(sway, "intensity");
        Double smoothing = optionalDouble(sway, "smoothing");
        Double sensitivity = optionalDouble(sway, "sway_sensitivity");
        Double returnSpeed = optionalDouble(sway, "return_speed");
        Double aimMultiplier = optionalDouble(sway, "aim_multiplier");
        return new WeaponDefinition.Sway(
                enabled == null ? defaults.enabled() : enabled,
                style,
                intensity == null ? defaults.intensity() : intensity,
                smoothing == null ? defaults.smoothing() : smoothing,
                sensitivity == null ? defaults.sensitivity() : sensitivity,
                returnSpeed == null ? defaults.returnSpeed() : returnSpeed,
                aimMultiplier == null ? defaults.aimMultiplier() : aimMultiplier);
    }

    /**
     * Reads the optional {@code weapon_sway_type}, reporting an unknown name.
     *
     * <p>An unknown style falls back to {@link WeaponSwayStyle#VANILLA} rather than failing the weapon: a typo must not be
     * able to disable or corrupt the handling of a weapon that works. The fallback is reported so the mistake is visible.</p>
     *
     * @param sway     the {@code weapons_sway} object
     * @param fallback the style to use when the property is absent or unusable
     * @return the resolved style, never {@code null}
     */
    private WeaponSwayStyle readSwayStyle(JsonObject sway, WeaponSwayStyle fallback) {
        String pointer = "$.weapons_sway.weapon_sway_type";
        String name = nestedString(sway, "weapon_sway_type", pointer);
        if (name == null) {
            return fallback;
        }
        WeaponSwayStyle resolved = WeaponSwayStyle.lookup(name);
        if (resolved == null) {
            report.warning(packId, sourceName, pointer,
                    "Unknown weapon sway type '" + name + "'; '" + WeaponSwayStyle.VANILLA.canonicalName()
                            + "' is used. Known types: vanilla, rotatory, type_1");
            return WeaponSwayStyle.VANILLA;
        }
        return resolved;
    }

    /**
     * Reads the ADS field-of-view multiplier.
     *
     * <p>{@code aiming_zoom} is the documented spelling. {@code aimimg_zoom} - the typo the first schema draft shipped -
     * is still accepted so every existing content pack keeps working unchanged, but it is reported as deprecated and the
     * corrected name wins when a file declares both. The precedence is fixed here so a pack can never depend on file
     * order, and the duplicate is reported, because a file carrying both spellings is almost certainly a copy/paste
     * accident.</p>
     *
     * @param aiming the {@code aiming} object
     * @return a usable multiplier, always finite, positive and at most {@code 1.0}
     */
    private double readAimZoom(JsonObject aiming) {
        Double documented = optionalDouble(aiming, "aiming_zoom");
        Double deprecated = optionalDouble(aiming, "aimimg_zoom");
        if (deprecated != null) {
            report.warning(packId, sourceName, "$.aiming.aimimg_zoom",
                    "'aimimg_zoom' is a misspelling of 'aiming_zoom' and is deprecated; it still works, but the property "
                            + "is 'aiming_zoom'" + (documented != null
                            ? ". Both are declared, so the corrected 'aiming_zoom' (" + documented + ") is used"
                            : ""));
        }
        Double configured = documented != null ? documented : deprecated;
        if (configured == null) {
            return AimZoom.DEFAULT_ZOOM;
        }
        String problem = AimZoom.describeProblem(configured);
        if (problem != null) {
            report.warning(packId, sourceName,
                    documented != null ? "$.aiming.aiming_zoom" : "$.aiming.aimimg_zoom", problem);
        }
        return AimZoom.sanitise(configured);
    }

    /**
     * Reads one easing name of the {@code aiming} block, resolving it through {@link ZoomCurve} and reporting an unknown
     * one.
     *
     * <p>The same vocabulary is used for both transitions ({@code aiming_zoom_type} and
     * {@code aiming_animation_type}), so a creator cannot end up with two subtly different easing languages. An unknown
     * name is reported here and falls back to {@link ZoomCurve#LINEAR} at use time, which never disables aiming.</p>
     *
     * @param aiming the {@code aiming} object
     * @param key    the property name
     * @return the configured name as written, or {@code null} when the weapon declares none
     */
    private String readEasingName(JsonObject aiming, String key) {
        String pointer = "$.aiming." + key;
        JsonElement element = aiming.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            report.warning(packId, sourceName, pointer,
                    "Expected a string naming an easing curve but found " + JsonValues.describeType(element)
                            + "; the transition falls back to 'linear'");
            return null;
        }
        String name = element.getAsString();
        if (ZoomCurve.lookup(name) == null) {
            report.warning(packId, sourceName, pointer,
                    "Unknown easing curve '" + name + "'; the transition falls back to 'linear'. Known curves: "
                            + String.join(", ", ZoomCurve.canonicalNames()));
        }
        return name;
    }

    /**
     * Reads one speed multiplier of the {@code aiming} block.
     *
     * <p>The value is a rate multiplier, not a duration: {@code 1.0} is the framework's base transition speed. A
     * non-positive or non-finite value is reported and treated as "not declared" (which is the neutral multiplier), and a
     * finite value outside {@code [MIN_SPEED, MAX_SPEED]} is reported and clamped - a transition that never finishes or one
     * that snaps are both worse than a clamped one.</p>
     *
     * @param aiming the {@code aiming} object
     * @param key    the property name
     * @return the usable multiplier, or {@link WeaponDefinition.Aiming#DEFAULT_SPEED} when the property is absent
     */
    private double readAimSpeed(JsonObject aiming, String key) {
        String pointer = "$.aiming." + key;
        Double value = optionalDouble(aiming, key);
        if (value == null) {
            return WeaponDefinition.Aiming.DEFAULT_SPEED;
        }
        if (!Double.isFinite(value) || value <= 0.0) {
            report.warning(packId, sourceName, pointer,
                    "The aiming speed must be a positive multiplier of the base transition rate, but it is " + value
                            + "; the default (" + WeaponDefinition.Aiming.DEFAULT_SPEED + ") is used");
            return WeaponDefinition.Aiming.DEFAULT_SPEED;
        }
        if (value < WeaponDefinition.Aiming.MIN_SPEED || value > WeaponDefinition.Aiming.MAX_SPEED) {
            double clamped = Math.max(WeaponDefinition.Aiming.MIN_SPEED,
                    Math.min(WeaponDefinition.Aiming.MAX_SPEED, value));
            report.warning(packId, sourceName, pointer,
                    "The aiming speed " + value + " is outside [" + WeaponDefinition.Aiming.MIN_SPEED + ", "
                            + WeaponDefinition.Aiming.MAX_SPEED + "] and is clamped to " + clamped);
            return clamped;
        }
        return value;
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
                recoilValue(nested, root, "crouch_multiplier"),
                recoilValue(nested, root, "kickback_aiming_modifier"));
        if (recoil.usesLegacyHorizontal()) {
            report.info(packId, sourceName, "$.recoil",
                    "The weapon uses the legacy symmetrical 'horizontal_recoil', which keeps its original meaning; "
                            + "'horizontal_recoil_left' and 'horizontal_recoil_right' configure each direction instead");
        }
        return recoil.isEmpty() ? null : recoil;
    }

    /**
     * Reads the optional {@code allow_firing_when_sprinting} property.
     *
     * <p>The value is retained in the source document, which is what {@link WeaponDefinition#allowFiringWhenSprinting()}
     * exposes to the client and server. This seam exists so the parser's type checking and backward-compatible default can be
     * verified headlessly, exactly like {@link #readRecoil(JsonObject)}.</p>
     *
     * @param root the weapon definition
     * @return {@code false} only when the property is a JSON boolean {@code false}; a missing or invalid value is {@code true}
     */
    boolean readAllowFiringWhenSprinting(JsonObject root) {
        Boolean configured = optionalBoolean(root, "allow_firing_when_sprinting");
        return configured == null || configured;
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

    /**
     * Reads {@code glowing_weapon_parts}: the parent bones whose whole bone hierarchy renders emissively.
     *
     * <p>One entry is one property, {@code part_name}, which names the bone. The older {@code name} spelling is still
     * accepted so a pack written against the first draft of the schema keeps loading, but {@code part_name} wins when a
     * single entry declares both - the entry then describes one bone, never two.</p>
     *
     * <p>Nothing about a bone name can be verified here: whether the model actually contains that bone is a property of
     * the geometry, which the client resolves when it draws the weapon. This reader only guarantees that every retained
     * name is non-blank and unique, so no bone is ever processed twice by the renderer.</p>
     *
     * @param root the definition document
     * @return the declared parent bones, in authored order, without duplicates
     */
    List<WeaponDefinition.GlowingPart> readGlowingParts(JsonObject root) {
        JsonArray array = optionalArray(root, "glowing_weapon_parts");
        if (array == null) {
            return List.of();
        }

        List<WeaponDefinition.GlowingPart> parts = new ArrayList<>(array.size());
        Set<String> seen = new LinkedHashSet<>();
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
            String name = nestedString(part, "part_name", entryPointer + ".part_name");
            if (name == null) {
                name = nestedString(part, "name", entryPointer + ".name");
            }
            if (name == null || name.isBlank()) {
                report.warning(packId, sourceName, entryPointer,
                        "The entry has no usable 'part_name' and is ignored");
                continue;
            }
            String trimmed = name.trim();
            if (!seen.add(trimmed)) {
                report.info(packId, sourceName, entryPointer, "The glowing part '" + trimmed
                        + "' is already declared earlier in the list; this duplicate is ignored");
                continue;
            }
            parts.add(new WeaponDefinition.GlowingPart(trimmed));
        }
        return parts;
    }

    /**
     * Reads the optional {@code muzzle_flash} block.
     *
     * <p>The block is presentation only, so nothing in it can reject the weapon: an absent block returns {@code null}
     * (feature disabled), and a malformed value is reported and replaced by the documented default or by "absent". The
     * single rule that changes the result rather than only reporting it is the {@code bone} / {@code bones} conflict:
     * they are mutually exclusive by design, so when a pack declares both the singular {@code bone} is kept and the
     * list is dropped - deterministically, and with a warning, never as a silent combination.</p>
     *
     * @param root the definition document
     * @return the parsed block, or {@code null} when the weapon declares none
     */
    WeaponDefinition.MuzzleFlash readMuzzleFlash(JsonObject root) {
        JsonObject flash = optionalObject(root, "muzzle_flash");
        if (flash == null) {
            return null;
        }
        reportUnknownKeys(flash, MUZZLE_FLASH_KEYS, "$.muzzle_flash");

        // The presence of the block is the opt-in, so an omitted flag keeps the feature on; only an explicit `false`
        // disables it.
        boolean enabled = nestedBoolean(flash, "enabled", "$.muzzle_flash.enabled", true);

        String single = nestedString(flash, "bone", "$.muzzle_flash.bone");
        List<String> bones = readMuzzleFlashBones(flash);
        if (single != null && !single.isBlank()) {
            if (!bones.isEmpty()) {
                report.warning(packId, sourceName, "$.muzzle_flash.bones", "'bone' and 'bones' are mutually exclusive:"
                        + " 'bone' is used and 'bones' is ignored. Declare one attachment point with 'bone' or several"
                        + " with 'bones', never both");
            }
            bones = List.of(single.trim());
        } else if (single != null) {
            report.warning(packId, sourceName, "$.muzzle_flash.bone", "'bone' is empty and is ignored");
        }

        ResourceId texture = readMuzzleFlashTexture(flash);
        double size = readMuzzleFlashSize(flash);
        Integer durationMs = readMuzzleFlashDurationMs(flash);
        WeaponDefinition.MuzzleFlash.Sprites sprites = readMuzzleFlashSprites(flash);

        if (enabled && bones.isEmpty()) {
            report.warning(packId, sourceName, "$.muzzle_flash",
                    "The muzzle flash declares no usable attachment bone, so no flash can be rendered");
        }
        if (enabled && texture == null && !bones.isEmpty()) {
            report.warning(packId, sourceName, "$.muzzle_flash.texture",
                    "The muzzle flash declares no usable texture, so no flash can be rendered");
        }
        return new WeaponDefinition.MuzzleFlash(enabled, bones, texture, size, sprites, durationMs);
    }

    /** Reads {@code bones}: the multiple-attachment spelling, without duplicates and capped at the schema limit. */
    private List<String> readMuzzleFlashBones(JsonObject flash) {
        JsonArray array = optionalArray(flash, "bones");
        if (array == null) {
            return List.of();
        }
        List<String> bones = new ArrayList<>(Math.min(array.size(), WeaponDefinition.MuzzleFlash.MAX_BONES));
        for (int i = 0; i < array.size(); i++) {
            String entryPointer = "$.muzzle_flash.bones[" + i + "]";
            JsonElement element = array.get(i);
            if (!JsonValues.isString(element)) {
                report.warning(packId, sourceName, entryPointer, "Expected a bone name but found "
                        + JsonValues.describeType(element) + "; the entry is ignored");
                continue;
            }
            String bone = element.getAsString().trim();
            if (bone.isEmpty()) {
                report.warning(packId, sourceName, entryPointer, "The bone name is empty and is ignored");
                continue;
            }
            if (bones.contains(bone)) {
                report.info(packId, sourceName, entryPointer, "The bone '" + bone
                        + "' is already declared earlier in the list; this duplicate is ignored");
                continue;
            }
            if (bones.size() >= WeaponDefinition.MuzzleFlash.MAX_BONES) {
                report.warning(packId, sourceName, entryPointer, "Only the first "
                        + WeaponDefinition.MuzzleFlash.MAX_BONES + " attachment bones are used; '" + bone
                        + "' and every later entry are ignored");
                break;
            }
            bones.add(bone);
        }
        return bones;
    }

    /** Reads {@code texture}: one animated sprite sheet, referenced like every other pack texture. */
    private ResourceId readMuzzleFlashTexture(JsonObject flash) {
        String raw = nestedString(flash, "texture", "$.muzzle_flash.texture");
        if (raw == null) {
            return null;
        }
        ResourceId.Result result = ResourceId.tryParse(raw.trim());
        if (!result.isValid()) {
            report.warning(packId, sourceName, "$.muzzle_flash.texture", "The muzzle flash texture '" + raw
                    + "' is not a valid resource identifier: " + result.error() + "; the flash is disabled");
            return null;
        }
        return result.id();
    }

    /**
     * Reads the optional {@code duration_ms}: how long the flash remains visible, in milliseconds.
     *
     * <p>This is deliberately independent of {@code sprites.fps}. The frame rate only says how fast the sheet's frames
     * advance; this property says how long the effect stays on screen, so the same sheet can flash for 60 ms at 200 fps
     * or for 150 ms at 30 fps. Absent means "derive the lifetime from the animation's own frames", which is what every
     * pack that predates the property does - so no existing file changes behaviour.</p>
     *
     * <p>Presentation never rejects a weapon: a value that cannot be used is reported and treated as absent, and a value
     * above {@link WeaponDefinition.MuzzleFlash#MAX_DURATION_MS} is clamped with a note.</p>
     *
     * @param flash the {@code muzzle_flash} object
     * @return the authored lifetime in whole milliseconds, or {@code null} when the block declares none it can use
     */
    private Integer readMuzzleFlashDurationMs(JsonObject flash) {
        Double duration = nestedDouble(flash, "duration_ms", "$.muzzle_flash.duration_ms");
        if (duration == null) {
            return null;
        }
        // Rounded first, then validated: a positive value below half a millisecond would otherwise become a zero-length
        // flash that never draws a single frame.
        long rounded = Double.isFinite(duration) ? Math.round(duration) : 0L;
        if (rounded <= 0L) {
            report.warning(packId, sourceName, "$.muzzle_flash.duration_ms",
                    "'duration_ms' must be a positive number of milliseconds but is " + duration
                            + "; the animation's own frames decide how long the flash lasts");
            return null;
        }
        int millis = (int) Math.min(rounded, Integer.MAX_VALUE);
        if (millis > WeaponDefinition.MuzzleFlash.MAX_DURATION_MS) {
            report.info(packId, sourceName, "$.muzzle_flash.duration_ms", "'duration_ms' (" + millis
                    + ") is longer than the supported " + WeaponDefinition.MuzzleFlash.MAX_DURATION_MS
                    + " ms and is clamped to it");
            millis = WeaponDefinition.MuzzleFlash.MAX_DURATION_MS;
        }
        return millis;
    }

    /**
     * Reads the optional {@code sprites} block: the sprite-sheet grid and the way its frames advance.
     *
     * <p>The layout is <strong>declared, never guessed</strong>, which is the reference implementation's rule and the
     * reason this block exists: a flash strip's cell is not the whole texture height, so a sheet cannot be split from its
     * aspect ratio alone. Absent means "derive a horizontal strip", which is what every pack that predates the block
     * keeps doing.</p>
     *
     * <p>Every value is presentation only and nothing here can reject the weapon: an unusable number is reported and
     * replaced by the documented default, and an unknown {@code type} falls back to {@code play_once} instead of dropping
     * the block.</p>
     *
     * @param flash the {@code muzzle_flash} object
     * @return the declared grid, or {@code null} when the block is absent or unusable
     */
    private WeaponDefinition.MuzzleFlash.Sprites readMuzzleFlashSprites(JsonObject flash) {
        JsonObject sprites = optionalObject(flash, "sprites");
        if (sprites == null) {
            return null;
        }
        reportUnknownKeys(sprites, MUZZLE_FLASH_SPRITE_KEYS, "$.muzzle_flash.sprites");

        int rows = readSpriteDimension(sprites, "rows");
        int columns = readSpriteDimension(sprites, "columns");
        int fps = readSpriteFps(sprites);
        WeaponDefinition.MuzzleFlash.SpriteType type = readSpriteType(sprites);
        return new WeaponDefinition.MuzzleFlash.Sprites(rows, columns, fps, type);
    }

    /**
     * Reads one grid dimension.
     *
     * @param sprites the {@code sprites} object
     * @param key     {@code rows} or {@code columns}
     * @return the declared value, or {@code 1} when absent or unusable
     */
    private int readSpriteDimension(JsonObject sprites, String key) {
        String pointer = "$.muzzle_flash.sprites." + key;
        Double value = nestedDouble(sprites, key, pointer);
        if (value == null) {
            return 1;
        }
        if (!Double.isFinite(value) || value < 1 || value > WeaponDefinition.MuzzleFlash.Sprites.MAX_DIMENSION
                || value != Math.rint(value)) {
            report.warning(packId, sourceName, pointer, "'" + key + "' must be a whole number between 1 and "
                    + WeaponDefinition.MuzzleFlash.Sprites.MAX_DIMENSION + " but is " + value + "; 1 is used instead");
            return 1;
        }
        return value.intValue();
    }

    /**
     * Reads {@code fps}.
     *
     * @param sprites the {@code sprites} object
     * @return the declared frame rate, or the documented default
     */
    private int readSpriteFps(JsonObject sprites) {
        Double fps = nestedDouble(sprites, "fps", "$.muzzle_flash.sprites.fps");
        if (fps == null) {
            return WeaponDefinition.MuzzleFlash.Sprites.DEFAULT_FPS;
        }
        if (!Double.isFinite(fps) || fps <= 0) {
            report.warning(packId, sourceName, "$.muzzle_flash.sprites.fps", "'fps' must be a positive number but is "
                    + fps + "; " + WeaponDefinition.MuzzleFlash.Sprites.DEFAULT_FPS + " is used instead");
            return WeaponDefinition.MuzzleFlash.Sprites.DEFAULT_FPS;
        }
        return (int) Math.min(Math.rint(fps), 1000.0D);
    }

    /**
     * Reads {@code type}, accepting the reference implementation's four names in any letter case.
     *
     * @param sprites the {@code sprites} object
     * @return the declared animation, or {@code play_once} when absent or unknown
     */
    private WeaponDefinition.MuzzleFlash.SpriteType readSpriteType(JsonObject sprites) {
        String raw = nestedString(sprites, "type", "$.muzzle_flash.sprites.type");
        if (raw == null) {
            return WeaponDefinition.MuzzleFlash.Sprites.DEFAULT_TYPE;
        }
        for (WeaponDefinition.MuzzleFlash.SpriteType candidate : WeaponDefinition.MuzzleFlash.SpriteType.values()) {
            if (candidate.name().equalsIgnoreCase(raw.trim())) {
                return candidate;
            }
        }
        report.warning(packId, sourceName, "$.muzzle_flash.sprites.type", "The sprite animation '" + raw
                + "' is not one of " + java.util.Arrays.toString(WeaponDefinition.MuzzleFlash.SpriteType.values())
                + "; " + WeaponDefinition.MuzzleFlash.Sprites.DEFAULT_TYPE + " is used instead");
        return WeaponDefinition.MuzzleFlash.Sprites.DEFAULT_TYPE;
    }

    /** Reads {@code size}: the rendered edge length in blocks, or the documented default. */
    private double readMuzzleFlashSize(JsonObject flash) {
        Double size = nestedDouble(flash, "size", "$.muzzle_flash.size");
        if (size == null) {
            return WeaponDefinition.MuzzleFlash.DEFAULT_SIZE;
        }
        if (!Double.isFinite(size) || size < WeaponDefinition.MuzzleFlash.MIN_SIZE
                || size > WeaponDefinition.MuzzleFlash.MAX_SIZE) {
            report.warning(packId, sourceName, "$.muzzle_flash.size", "The muzzle flash size " + size
                    + " is outside " + WeaponDefinition.MuzzleFlash.MIN_SIZE + ".."
                    + WeaponDefinition.MuzzleFlash.MAX_SIZE + " blocks; "
                    + WeaponDefinition.MuzzleFlash.DEFAULT_SIZE + " is used instead");
            return WeaponDefinition.MuzzleFlash.DEFAULT_SIZE;
        }
        return size;
    }

    /**
     * Reads the {@code sounds} object: an optional map from a sound key to its playable description.
     *
     * <p>Every key is optional and independently optional, so the whole reader is tolerant: a missing object yields an
     * empty map (the weapon is silent, which is a valid configuration), and a missing, {@code null}, empty or
     * whitespace-only value is treated as "not configured" and dropped. Only a value that <em>is</em> present and cannot be
     * read is reported as a warning, and even then only that one key is dropped - a broken sound entry never rejects the
     * weapon, because sounds are presentation and gameplay must keep working without them.</p>
     *
     * <h2>The three accepted shapes of one entry</h2>
     * <pre>
     *   "fire": "arg_pack:guns/rifle_fire"                       // one sample, the original form
     *   "fire": ["arg_pack:guns/rifle_fire_1", "..._2"]          // several samples, one chosen per shot
     *   "fire": {                                                // samples plus tuning
     *     "variants": ["arg_pack:guns/rifle_fire_1", "..._2"],
     *     "volume": 0.9,
     *     "pitch": 1.0,
     *     "pitch_variation": 0.035,
     *     "volume_variation": 0.06
     *   }
     * </pre>
     * <p>The first form is exactly the previous behaviour, and it keeps the improvement: a single-sample {@code fire} still
     * gets the default gunfire variation, so a pack needs no change at all to sound less repetitive. Both views of the
     * object are built from the same values, so a caller that only looks at the primary identifiers - the reference
     * validator, the server broadcast - cannot disagree with the audio engine.</p>
     *
     * <p>An unknown key is reported once and ignored: the gameplay code asks for sounds by meaning
     * ({@link WeaponSoundKey}), so a typo would otherwise be an entry nothing ever plays.</p>
     *
     * @param root the weapon document
     * @return the declared sound keys, in file order; never {@code null}
     */
    DeclaredSounds readSounds(JsonObject root) {
        JsonElement element = root.get("sounds");
        if (element == null || element.isJsonNull()) {
            return DeclaredSounds.EMPTY;
        }
        if (!element.isJsonObject()) {
            report.warning(packId, sourceName, pointer("sounds"), "Expected an object of sound identifiers but found "
                    + JsonValues.describeType(element) + "; the weapon stays silent");
            return DeclaredSounds.EMPTY;
        }

        Map<String, ResourceId> references = new LinkedHashMap<>();
        Map<String, WeaponSoundSet> sets = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            String entryPointer = "$.sounds." + key;
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                // Explicitly unconfigured. Documented equivalent of omitting the key.
                continue;
            }
            WeaponSoundKey known = WeaponSoundKey.byJsonName(key);
            if (known == null) {
                report.warning(packId, sourceName, entryPointer, "Unknown weapon sound key '" + key
                        + "'; known keys are " + String.join(", ", WeaponSoundKey.jsonNames())
                        + ". The entry is ignored");
                continue;
            }
            WeaponSoundSet set = readSoundSet(known, entryPointer, value);
            if (set == null) {
                continue;
            }
            references.put(key, set.primary());
            sets.put(key, set);
        }
        return new DeclaredSounds(references, sets);
    }

    /**
     * Reads one {@code sounds} entry in any of its three shapes.
     *
     * @param key     the sound key the entry belongs to
     * @param pointer the JSON pointer of the entry, for diagnostics
     * @param value   the entry's value
     * @return the sound set, or {@code null} when nothing playable was declared
     */
    private WeaponSoundSet readSoundSet(WeaponSoundKey key, String pointer, JsonElement value) {
        if (JsonValues.isString(value)) {
            ResourceId id = readSoundIdentifier(pointer, value.getAsString());
            return id == null ? null : WeaponSoundSet.single(key, id);
        }
        if (value.isJsonArray()) {
            List<ResourceId> variants = readSoundIdentifiers(pointer, value.getAsJsonArray());
            return variants.isEmpty() ? null : new WeaponSoundSet(variants, 1.0F, 1.0F,
                    WeaponSoundSet.defaultPitchVariation(key), WeaponSoundSet.defaultVolumeVariation(key));
        }
        if (!value.isJsonObject()) {
            report.warning(packId, sourceName, pointer, "Expected an identifier, an array of identifiers or an object "
                    + "with 'variants', but found " + JsonValues.describeType(value) + "; the sound key is ignored");
            return null;
        }

        JsonObject object = value.getAsJsonObject();
        JsonElement declared = object.get("variants");
        if (declared == null) {
            // 'sound' is the single-sample spelling of the same property, so an object that only re-tunes a key stays
            // readable: { "sound": "arg:fire", "pitch_variation": 0.02 }
            declared = object.get("sound");
        }
        if (declared == null || declared.isJsonNull()) {
            report.warning(packId, sourceName, pointer + ".variants", "The sound object declares no 'variants'; the "
                    + "sound key is ignored");
            return null;
        }
        List<ResourceId> variants;
        if (JsonValues.isString(declared)) {
            ResourceId single = readSoundIdentifier(pointer + ".variants", declared.getAsString());
            variants = single == null ? List.of() : List.of(single);
        } else if (declared.isJsonArray()) {
            variants = readSoundIdentifiers(pointer + ".variants", declared.getAsJsonArray());
        } else {
            report.warning(packId, sourceName, pointer + ".variants", "Expected an identifier or an array of "
                    + "identifiers but found " + JsonValues.describeType(declared) + "; the sound key is ignored");
            return null;
        }
        if (variants.isEmpty()) {
            report.warning(packId, sourceName, pointer + ".variants", "The sound object declares no usable "
                    + "identifier; the sound key is ignored");
            return null;
        }
        return new WeaponSoundSet(variants,
                tuning(object, pointer, "volume", 1.0F),
                tuning(object, pointer, "pitch", 1.0F),
                tuning(object, pointer, "pitch_variation", WeaponSoundSet.defaultPitchVariation(key)),
                tuning(object, pointer, "volume_variation", WeaponSoundSet.defaultVolumeVariation(key)));
    }

    /**
     * Reads one optional tuning number of a sound object.
     *
     * @param object   the sound object
     * @param pointer  its JSON pointer
     * @param property the property to read
     * @param fallback the value to use when the property is absent or unreadable
     * @return the declared value, or {@code fallback}
     */
    private float tuning(JsonObject object, String pointer, String property, float fallback) {
        JsonElement element = object.get(property);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        Double value = JsonValues.asDouble(element);
        if (value == null) {
            report.warning(packId, sourceName, pointer + "." + property, "Expected a number but found "
                    + JsonValues.describeType(element) + "; the default is used");
            return fallback;
        }
        return value.floatValue();
    }

    /**
     * Reads a list of sound identifiers, keeping every valid entry and reporting the invalid ones.
     *
     * @param pointer the JSON pointer of the array
     * @param array   the array to read
     * @return the valid identifiers, in file order; never {@code null}
     */
    private List<ResourceId> readSoundIdentifiers(String pointer, JsonArray array) {
        List<ResourceId> identifiers = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonElement element = array.get(index);
            String elementPointer = pointer + "[" + index + "]";
            if (!JsonValues.isString(element)) {
                report.warning(packId, sourceName, elementPointer, "Expected a resource identifier string but found "
                        + JsonValues.describeType(element) + "; the variant is ignored");
                continue;
            }
            ResourceId id = readSoundIdentifier(elementPointer, element.getAsString());
            if (id != null) {
                identifiers.add(id);
            }
        }
        return identifiers;
    }

    /**
     * Reads and validates one sound identifier.
     *
     * @param pointer the JSON pointer of the value
     * @param text    the declared text, may be {@code null}
     * @return the identifier, or {@code null} when the text is unconfigured or invalid
     */
    private ResourceId readSoundIdentifier(String pointer, String text) {
        if (text == null || text.isBlank()) {
            // An empty string is unconfigured, not an error: it is how a creator disables one key in place.
            return null;
        }
        ResourceId.Result reference = ResourceId.tryParse(text);
        if (!reference.isValid()) {
            report.warning(packId, sourceName, pointer, "Sound reference '" + text
                    + "' is not a valid resource identifier: " + reference.error() + "; the sound key is ignored");
            return null;
        }
        return reference.id();
    }

    /**
     * The two views of the {@code sounds} object: the primary identifier of every key and its complete playable
     * description.
     *
     * @param primary the first identifier of every key, which is exactly what a single-string declaration names
     * @param sets    the samples and tuning of every key
     */
    record DeclaredSounds(Map<String, ResourceId> primary, Map<String, WeaponSoundSet> sets) {

        private static final DeclaredSounds EMPTY = new DeclaredSounds(Map.of(), Map.of());
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

    /**
     * Reads {@code reload_sequences}.
     *
     * <p>The stage of an entry is declared with {@code stage} (the staged form) or {@code phase} (the original
     * spelling); both are accepted and {@code stage} wins when an entry declares both. The name is kept verbatim - the
     * interpretation belongs to {@link com.arg_studios.arg_weaponry.weapon.reload.ReloadStage} - and an unrecognized name
     * is reported, because it changes how the definition is executed: a definition whose entries are all unrecognized
     * is a legacy single-clip reload.</p>
     *
     * <p>{@code insert_at} is the optional point inside the entry, in seconds, at which the entry's ammunition is
     * inserted. Absent means "at the end of the entry", which is when a shell is seated; see
     * {@link WeaponDefinition.ReloadStep#insertOffsetSeconds()}.</p>
     *
     * <p>Package-private seam rather than a private helper, following the recoil and sway readers: the reload schema is
     * plain JSON, while a full {@link #parse(JsonObject)} needs the game's own classes (through
     * {@link com.arg_studios.arg_weaponry.weapon.state.FireMode}), so the staged schema is verified on this method
     * exactly as a definition uses it.</p>
     *
     * @param root the definition document
     * @return the accepted entries, in file order
     */
    List<WeaponDefinition.ReloadStep> readReloadSteps(JsonObject root) {
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

            String stage = readReloadStage(entry, entryPointer);
            String animation = nestedString(entry, "animation", entryPointer + ".animation");
            Double duration = nestedDouble(entry, "duration", entryPointer + ".duration");

            Boolean repeat = null;
            JsonElement repeatElement = entry.get("repeat");
            if (repeatElement != null) {
                if (JsonValues.isBoolean(repeatElement)) {
                    repeat = repeatElement.getAsBoolean();
                } else {
                    report.warning(packId, sourceName, entryPointer + ".repeat", "Expected true or false but found "
                            + JsonValues.describeType(repeatElement) + "; the flag is ignored");
                }
            }

            Double insertAt = readInsertAt(entry, entryPointer, duration);
            Double transitionDuration = readTransitionDuration(entry, entryPointer);

            if (stage == null || stage.isBlank()) {
                report.warning(packId, sourceName, entryPointer,
                        "The entry has no 'stage' and no 'phase' and is ignored");
                continue;
            }
            // An entry without an 'animation' is still a stage: it is a beat with a duration and (for a load stage) an
            // insertion point, it simply has no clip to play. Dropping it here used to remove the beat from the reload
            // entirely, which shortened a reload below the duration its author declared - so the stage is kept and the
            // planner carries a null animation for it.
            if (duration == null || duration <= 0) {
                report.warning(packId, sourceName, entryPointer,
                        "The entry needs a positive 'duration' and is ignored");
                continue;
            }

            steps.add(new WeaponDefinition.ReloadStep(stage, animation, duration,
                    nestedString(entry, "when", entryPointer + ".when"), repeat, insertAt, transitionDuration));
        }
        return steps;
    }

    /**
     * Reads the optional {@code transition_duration} of one reload entry.
     *
     * <p>The property is the cross-fade, in seconds, used when this entry's clip starts; it is a rendering choice and
     * changes no stage duration, no insertion point and no server timing. Absent means "use the documented default"
     * ({@link com.arg_studios.arg_weaponry.weapon.reload.ReloadRules#DEFAULT_TRANSITION_SECONDS}), so every existing file
     * keeps working without modification, and an explicit {@code 0} disables blending for that transition.</p>
     *
     * @param entry        the reload entry
     * @param entryPointer the JSON pointer of the entry, for diagnostics
     * @return the authored fade length in seconds, or {@code null} when the entry declares none
     */
    private Double readTransitionDuration(JsonObject entry, String entryPointer) {
        Double seconds = nestedDouble(entry, "transition_duration", entryPointer + ".transition_duration");
        if (seconds == null) {
            return null;
        }
        if (!Double.isFinite(seconds) || seconds < 0.0) {
            report.warning(packId, sourceName, entryPointer + ".transition_duration",
                    "'transition_duration' must be a non-negative number of seconds but is " + seconds
                            + "; the default cross-fade is used");
            return null;
        }
        if (seconds > com.arg_studios.arg_weaponry.weapon.reload.ReloadRules.MAX_TRANSITION_SECONDS) {
            report.info(packId, sourceName, entryPointer + ".transition_duration",
                    "'transition_duration' (" + seconds + "s) is longer than the supported "
                            + com.arg_studios.arg_weaponry.weapon.reload.ReloadRules.MAX_TRANSITION_SECONDS
                            + "s and is clamped to it");
        }
        return seconds;
    }

    /**
     * Reads the stage name of one reload entry, accepting {@code stage} and the legacy {@code phase}.
     *
     * @param entry        the reload entry
     * @param entryPointer the JSON pointer of the entry, for diagnostics
     * @return the declared name, or {@code null} when the entry declares neither
     */
    private String readReloadStage(JsonObject entry, String entryPointer) {
        String stage = nestedString(entry, "stage", entryPointer + ".stage");
        String phase = nestedString(entry, "phase", entryPointer + ".phase");
        if (stage != null && phase != null && !stage.equalsIgnoreCase(phase)) {
            report.warning(packId, sourceName, entryPointer, "The entry declares both 'stage' ('" + stage
                    + "') and 'phase' ('" + phase + "'); 'stage' is used");
        }
        String declared = stage != null ? stage : phase;
        if (declared == null || declared.isBlank()) {
            return null;
        }
        if (com.arg_studios.arg_weaponry.weapon.reload.ReloadStage.lookup(declared) == null) {
            report.info(packId, sourceName, entryPointer, "The stage '" + declared + "' is not part of the staged "
                    + "reload vocabulary (" + com.arg_studios.arg_weaponry.weapon.reload.ReloadStage.knownNames()
                    + "); the definition is executed as a single-clip reload unless another entry declares a known "
                    + "stage");
        }
        return declared;
    }

    /**
     * Reads the optional {@code insert_at} of one reload entry.
     *
     * @param entry        the reload entry
     * @param entryPointer the JSON pointer of the entry, for diagnostics
     * @param duration     the entry's duration, or {@code null} when it declared an unusable one
     * @return the declared offset in seconds, or {@code null} for "at the end of the entry"
     */
    private Double readInsertAt(JsonObject entry, String entryPointer, Double duration) {
        Double insertAt = nestedDouble(entry, "insert_at", entryPointer + ".insert_at");
        if (insertAt == null) {
            return null;
        }
        if (!Double.isFinite(insertAt) || insertAt < 0.0) {
            report.warning(packId, sourceName, entryPointer + ".insert_at", "'insert_at' must be a non-negative "
                    + "number of seconds but is " + insertAt + "; the ammunition is inserted at the end of the entry");
            return null;
        }
        if (duration != null && duration > 0 && insertAt > duration) {
            report.warning(packId, sourceName, entryPointer + ".insert_at", "'insert_at' (" + insertAt
                    + "s) is later than the entry's 'duration' (" + duration + "s) and is clamped to it");
        }
        return insertAt;
    }
}

