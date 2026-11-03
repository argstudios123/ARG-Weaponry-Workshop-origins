package com.arg_studios.arg_weaponry.weapon;

import com.arg_studios.arg_weaponry.client.display.SlotTextureId;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsonValues;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackResources;
import com.arg_studios.arg_weaponry.pack.PackResourceIndex;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionEntries;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionRegistry;
import com.arg_studios.arg_weaponry.weapon.animation.ReusableAnimations;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationCache;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundSet;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies the resource references of one parsed weapon definition against the pack that provides
 * them.
 *
 * <p>Only checks that the current discovery infrastructure can actually perform are made: files are
 * located through {@link PackResourceIndex} using the pack's own namespaces and whatever folder
 * layout the pack uses. Nothing is resolved through Minecraft's resource manager yet, and no
 * rendering, audio or animation system is involved.</p>
 *
 * <p>Missing references are warnings, not errors: a definition is still usable data when one
 * optional resource is absent, and the final reference conventions are not settled. Every finding
 * names the JSON pointer, the namespace and the candidate paths that were tried.</p>
 */
public final class WeaponReferenceValidator {

    private final ContentPack pack;
    private final PackResourceIndex index;
    private final WeaponDefinition weapon;
    private final String sourceName;
    private final ValidationReport report;

    private WeaponReferenceValidator(ContentPack pack, PackResourceIndex index, WeaponDefinition weapon,
                                     String sourceName, ValidationReport report) {
        this.pack = pack;
        this.index = index;
        this.weapon = weapon;
        this.sourceName = sourceName;
        this.report = report;
    }

    public static void validate(ContentPack pack, PackResourceIndex index, WeaponDefinition weapon,
                                String sourceName, ValidationReport report) {
        new WeaponReferenceValidator(pack, index, weapon, sourceName, report).run();
    }

    private void run() {
        Set<String> animationNames = validateDisplay();
        validateAnimationNames(animationNames);
        validateSounds();
        validateReusableAnimations();
        validateHudUi();
        validateAmmunition();
        validateMuzzleFlash();

        report.info(pack.id(), sourceName, "$", "Not resolved by this milestone: sound playback. Glowing-part and "
                + "muzzle-flash bone names are resolved against the parsed geometry when the weapon is first drawn, "
                + "because only the model knows its bones; a name that matches no bone is reported there");
    }

    // --- display configuration ---

    /** @return the animation names defined by the weapon's animation resource, or an empty set. */
    private Set<String> validateDisplay() {
        ResourceId display = weapon.display();
        if (!pack.providesNamespace(display.namespace())) {
            report.warning(pack.id(), sourceName, "$.display", "The display resource '" + display
                    + "' uses namespace '" + display.namespace()
                    + "', which this content pack does not provide; the reference was not verified");
            return Set.of();
        }

        String displayPath = index.findFirstExisting(display.namespace(), "display/" + display.path(),
                ContentPackResources.JSON_SUFFIXES);
        if (displayPath == null) {
            report.warning(pack.id(), sourceName, "$.display", "The display configuration '" + display
                    + "' was not found; tried "
                    + candidates(display.namespace(), "display/" + display.path(),
                    ContentPackResources.JSON_SUFFIXES)
                    + ". Display resources are not resolved by this milestone, so no deeper validation was performed");
            return Set.of();
        }
        if (index.isEmpty(display.namespace(), displayPath)) {
            report.warning(pack.id(), sourceName, "$.display", "The display configuration '" + display
                    + "' resolves to an empty file (" + displayPath + ")");
            return Set.of();
        }

        JsonObject displayJson = readObject(display.namespace(), displayPath, "$.display");
        if (displayJson == null) {
            return Set.of();
        }

        validateNestedReference(displayJson, "model", "models/", ContentPackResources.JSON_SUFFIXES, "model");
        validateNestedReference(displayJson, "texture", "textures/", ContentPackResources.TEXTURE_SUFFIXES,
                "texture");
        validateNestedReference(displayJson, "slot", "textures/", ContentPackResources.TEXTURE_SUFFIXES,
                "HUD slot texture");

        String animationsPath = validateNestedReference(displayJson, "animations", "animations/",
                ContentPackResources.ANIMATION_SUFFIXES, "animation resource");
        if (animationsPath == null) {
            return Set.of();
        }

        JsonObject animationsJson = readObject(display.namespace(), animationsPath, "$.display.animations");
        if (animationsJson == null) {
            return Set.of();
        }
        JsonElement animations = animationsJson.get("animations");
        if (!(animations instanceof JsonObject animationMap)) {
            report.warning(pack.id(), sourceName, "$.display.animations", "The animation resource '"
                    + display.namespace() + ":" + animationsPath
                    + "' has no 'animations' object, so animation names cannot be verified");
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>(animationMap.keySet());
        // Reusable libraries contribute names to the same lookup, so a declared idle/fire clip that lives in a library must
        // not be reported as missing. The placeholder primary only supplies the preferred namespace for bare paths.
        ResourceId namespaceHint = new ResourceId(display.namespace(), "display");
        for (ResourceId library : ReusableAnimations.resolveLibraries(pack, weapon, namespaceHint)) {
            WeaponAnimationCache.instance().loadLibrary(pack, library)
                    .ifPresent(file -> names.addAll(file.animations().keySet()));
        }
        return names;
    }

    // --- nested reference validation ---

    /**
     * The path a nested reference is looked up under inside its namespace.
     *
     * <p>Every reference is authored as the id Minecraft uses ({@code arg_pack:weapons/dmrs/m100_dmr/m100_dmr}), with one
     * deliberate exception: {@code slot} is usually written the way the file appears in the pack
     * ({@code arg_pack:textures/gui/hud/slots/m100_dmr.png}). The display loader accepts both spellings, because the icon
     * genuinely comes from the second one, so the validator has to resolve the same id the loader will use - otherwise a pack
     * whose icon works would still be told its icon is missing.</p>
     *
     * @param key  the display property being validated
     * @param id   the parsed reference
     * @return the path relative to the namespace
     */
    private String validatedPath(String key, ResourceId id) {
        return "slot".equals(key) ? SlotTextureId.canonical(id).path() : id.path();
    }

    /**
     * Validates one string reference inside the display configuration.
     *
     * @return the resolved path relative to the namespace, or {@code null} when it could not be
     *         resolved (a diagnostic has already been reported)
     */
    private String validateNestedReference(JsonObject displayJson, String key, String prefix,
                                           List<String> suffixes, String description) {
        String pointer = "$.display." + key;
        JsonElement element = displayJson.get(key);
        if (element == null) {
            report.info(pack.id(), sourceName, pointer,
                    "The display configuration declares no '" + key + "' (" + description + ")");
            return null;
        }
        if (!JsonValues.isString(element)) {
            report.warning(pack.id(), sourceName, pointer, "Expected a resource identifier string for '" + key
                    + "' but found " + JsonValues.describeType(element));
            return null;
        }

        ResourceId.Result reference = ResourceId.tryParse(element.getAsString());
        if (!reference.isValid()) {
            report.warning(pack.id(), sourceName, pointer, "The " + description + " reference '"
                    + element.getAsString() + "' is not a valid resource identifier: " + reference.error());
            return null;
        }
        if (!pack.providesNamespace(reference.id().namespace())) {
            report.warning(pack.id(), sourceName, pointer, "The " + description + " reference '" + reference.id()
                    + "' uses namespace '" + reference.id().namespace()
                    + "', which this content pack does not provide; it was not verified");
            return null;
        }

        String found = index.findFirstExisting(reference.id().namespace(),
                prefix + validatedPath(key, reference.id()), suffixes);
        if (found == null) {
            report.warning(pack.id(), sourceName, pointer, "The " + description + " '" + reference.id()
                    + "' was not found; tried "
                    + candidates(reference.id().namespace(), prefix + validatedPath(key, reference.id()), suffixes));
            return null;
        }
        if (index.isEmpty(reference.id().namespace(), found)) {
            report.warning(pack.id(), sourceName, pointer, "The " + description + " '" + reference.id()
                    + "' resolves to an empty file (" + found + ")");
            return null;
        }
        return found;
    }

    // --- animation names ---

    private void validateAnimationNames(Set<String> knownAnimations) {
        if (knownAnimations.isEmpty()) {
            return;
        }

        for (int i = 0; i < weapon.idleAnimations().size(); i++) {
            checkAnimation("$.idle_anims[" + i + "].name", weapon.idleAnimations().get(i).name(), knownAnimations);
        }
        for (int i = 0; i < weapon.holsterAnimations().size(); i++) {
            checkAnimation("$.holster_anims[" + i + "].name", weapon.holsterAnimations().get(i).name(), knownAnimations);
        }
        for (int i = 0; i < weapon.drawAnimations().size(); i++) {
            WeaponDefinition.DrawSelection selection = weapon.drawAnimations().get(i);
            String pointer = selection.declaredWithDrawFirstKey()
                    ? "$.draw_anims[" + i + "].draw_first" : "$.draw_anims[" + i + "].name";
            checkAnimation(pointer, selection.name(), knownAnimations);
        }
        for (int i = 0; i < weapon.reloadSequences().size(); i++) {
            checkAnimation("$.reload_sequences[" + i + "].animation",
                    weapon.reloadSequences().get(i).animation(), knownAnimations);
        }
        for (int i = 0; i < weapon.fireAnimations().size(); i++) {
            checkAnimation("$.fire_anims[" + i + "].name", weapon.fireAnimations().get(i).name(), knownAnimations);
        }
    }

    private void checkAnimation(String pointer, String animationName, Set<String> knownAnimations) {
        if (animationName == null || animationName.isBlank()) {
            // A reload stage may deliberately declare no clip: it is a timed beat, not an animation.
            return;
        }
        if (knownAnimations.contains(animationName)) {
            return;
        }
        report.warning(pack.id(), sourceName, pointer, "The animation '" + animationName
                + "' is not defined in the weapon's animation resource, which defines " + summarise(knownAnimations));
    }

    // --- sounds, reusable animations and HUD ---

    private void validateSounds() {
        for (Map.Entry<String, WeaponSoundSet> entry : weapon.soundSets().entrySet()) {
            String key = entry.getKey();
            WeaponSoundSet set = entry.getValue();
            if (set == null) {
                continue;
            }
            for (int index = 0; index < set.size(); index++) {
                String pointer = "$.sounds." + key;
                // A single-sample key keeps the original pointer; a key with several samples points at the one that is
                // missing, so the creator knows which take to add.
                if (set.size() > 1) {
                    pointer = pointer + ".variants[" + index + "]";
                }
                validateSoundReference(pointer, set.variant(index));
            }
        }
    }

    /**
     * Checks one declared sound identifier against the content pack's files.
     *
     * @param pointer   the JSON pointer of the entry
     * @param reference the declared identifier
     */
    private void validateSoundReference(String pointer, ResourceId reference) {
        if (!pack.providesNamespace(reference.namespace())) {
            report.warning(pack.id(), sourceName, pointer, "The sound reference '" + reference
                    + "' uses namespace '" + reference.namespace()
                    + "', which this content pack does not provide; it was not verified");
            return;
        }

        String found = index.findFirstExisting(reference.namespace(), "sounds/" + reference.path(),
                ContentPackResources.SOUND_SUFFIXES);
        if (found == null) {
            report.warning(pack.id(), sourceName, pointer, "The sound file for '" + reference
                    + "' was not found; tried "
                    + candidates(reference.namespace(), "sounds/" + reference.path(),
                    ContentPackResources.SOUND_SUFFIXES)
                    + ". The sound key is kept; playback is not implemented by this milestone");
            return;
        }
        if (index.isEmpty(reference.namespace(), found)) {
            report.warning(pack.id(), sourceName, pointer,
                    "The sound file for '" + reference + "' is empty (" + found + ")");
        }
    }

    private void validateReusableAnimations() {
        List<String> entries = weapon.reusableDefaultAnimations();
        for (int i = 0; i < entries.size(); i++) {
            String value = entries.get(i);
            String pointer = "$.reusable_default_animations[" + i + "]";
            if (!findInAnyNamespace(value)) {
                report.warning(pack.id(), sourceName, pointer, "The reusable animation library '" + value
                        + "' was not found below default_animations/ in namespace(s) " + pack.resourceNamespaces()
                        + " (tried <value> with .json and .animation.json). The entry is skipped; the weapon keeps "
                        + "its own animations");
            }
        }
    }

    private void validateHudUi() {
        String hud = weapon.customHudUi();
        if (hud == null) {
            return;
        }

        String relativePath = "hud_ui/" + hud;
        for (String namespace : pack.resourceNamespaces()) {
            String found = index.findFirstExisting(namespace, relativePath, ContentPackResources.JSON_SUFFIXES);
            if (found == null) {
                continue;
            }
            if (index.isEmpty(namespace, found)) {
                report.warning(pack.id(), sourceName, "$.custom_hud_ui", "The HUD configuration '" + hud
                        + "' resolves to an empty file (" + namespace + ":" + found + ")");
            }
            return;
        }

        report.warning(pack.id(), sourceName, "$.custom_hud_ui", "The HUD configuration '" + hud
                + "' could not be resolved; tried hud_ui/" + hud + ".json in namespace(s) "
                + pack.resourceNamespaces() + ". This reference carries no namespace and its convention is not settled");
    }

    /**
     * Reports how the definition's existing {@code ammo_to_use} entries resolve.
     *
     * <p>{@link AmmunitionRegistry} is the single source of truth: an entry equal to an ammunition
     * definition's {@code ammo_name} resolves to that definition's {@code ammo_id}; a value that is already a
     * {@code namespace:path} identifier is read as a direct item reference (the behaviour milestone 10K
     * established); anything else resolves to nothing and is reported here. The ammunition index is built
     * before weapon definitions are loaded, so these results are complete at this point.</p>
     *
     * <p>Nothing is an error. A weapon with no usable ammunition entry still loads and still fires from its
     * magazine; only reserve-ammunition matching has nothing to match.</p>
     */
    private void validateAmmunition() {
        List<AmmunitionEntries> entries = AmmunitionRegistry.entriesOf(weapon);
        List<String> unresolvedNames = AmmunitionEntries.unresolvedNames(entries);

        boolean unlimited = weapon.ammoCapacity() != null && weapon.ammoCapacity().isUnlimited();
        if (weapon.type() == WeaponType.GUN && entries.isEmpty()) {
            if (unlimited) {
                report.info(pack.id(), sourceName, "$.ammo_to_use", "An unlimited-ammunition weapon needs no "
                        + "ammunition items: 'ammo_capacity' is 'unlimited', so firing consumes nothing and the "
                        + "weapon never reloads. 'ammo_to_use' is optional metadata here and is not consulted for "
                        + "firing or reloading");
            } else {
                report.warning(pack.id(), sourceName, "$.ammo_to_use", "'ammo_to_use' declares no entries. The "
                        + "weapon still loads and still fires from its magazine, but no inventory item can be "
                        + "recognised as reserve ammunition for it until at least one entry names an ammunition "
                        + "definition or an item id");
            }
        }

        for (AmmunitionEntries entry : entries) {
            String pointer = "$.ammo_to_use";
            if (entry.isAmmunition()) {
                report.info(pack.id(), sourceName, pointer, "'" + entry.authored() + "' resolves through the "
                        + "ammunition definition '" + entry.ammunitionName() + "' to the Minecraft item '"
                        + entry.itemId() + "'");
            } else if (entry.isItemId()) {
                report.info(pack.id(), sourceName, pointer, "'" + entry.authored() + "' is not an ammunition "
                        + "definition name, so it is read as a direct Minecraft item id reference ('"
                        + entry.itemId() + "')");
            }
        }

        if (!unresolvedNames.isEmpty()) {
            report.warning(pack.id(), sourceName, "$.ammo_to_use", "These entries resolve to no ammunition: "
                    + String.join(", ", unresolvedNames) + ". No loaded ammunition definition declares one of "
                    + "those names and none of them is a 'namespace:path' item id, so they match no inventory "
                    + "stack and are never replaced by a guessed item. Names such as 'creative_ammo' have no "
                    + "established behaviour in this build: no creative-mode exemption is granted for them. "
                    + "Unlimited ammunition is a capacity mode ('ammo_capacity': 'unlimited'), not an "
                    + "'ammo_to_use' entry, and is never inferred from one");
        }
    }

    private boolean findInAnyNamespace(String relativePath) {
        for (String namespace : pack.resourceNamespaces()) {
            if (index.findFirstExisting(namespace, relativePath, ContentPackResources.ANIMATION_SUFFIXES) != null) {
                return true;
            }
        }
        return false;
    }

    // --- muzzle flash ---

    /**
     * Verifies the sprite sheet of the optional {@code muzzle_flash} block.
     *
     * <p>The attachment bones cannot be checked here - they live in the geometry, which discovery does not parse - but the
     * sprite sheet is an ordinary pack texture, so a wrong path is worth a warning at load time instead of a silent
     * effect that never appears. Both spellings of a texture reference are resolved exactly like the renderer resolves
     * them, so the validator and the texture cache cannot disagree about which file a reference names.</p>
     */
    private void validateMuzzleFlash() {
        WeaponDefinition.MuzzleFlash flash = weapon.muzzleFlash();
        if (flash == null) {
            return;
        }
        if (!flash.enabled()) {
            report.info(pack.id(), sourceName, "$.muzzle_flash.enabled",
                    "The muzzle flash is disabled, so nothing is rendered for it");
            return;
        }
        ResourceId texture = flash.texture();
        if (texture == null) {
            // The parser already reported the missing or malformed reference; the flash is disabled because of it.
            return;
        }
        if (!pack.providesNamespace(texture.namespace())) {
            report.warning(pack.id(), sourceName, "$.muzzle_flash.texture", "The muzzle flash texture '" + texture
                    + "' uses namespace '" + texture.namespace() + "', which this content pack does not provide; it was "
                    + "not verified");
            return;
        }

        ResourceId canonical = ContentPackResources.stripTextureSuffix(texture);
        String relativePath = "textures/" + (canonical == null ? texture.path() : canonical.path());
        String found = index.findFirstExisting(texture.namespace(), relativePath,
                ContentPackResources.TEXTURE_SUFFIXES);
        if (found == null) {
            report.warning(pack.id(), sourceName, "$.muzzle_flash.texture", "The muzzle flash texture '" + texture
                    + "' was not found; tried " + candidates(texture.namespace(), relativePath,
                    ContentPackResources.TEXTURE_SUFFIXES));
            return;
        }
        if (index.isEmpty(texture.namespace(), found)) {
            report.warning(pack.id(), sourceName, "$.muzzle_flash.texture", "The muzzle flash texture '" + texture
                    + "' resolves to an empty file (" + found + ")");
            return;
        }
        report.info(pack.id(), sourceName, "$.muzzle_flash.texture", "The muzzle flash sprite sheet '" + texture
                + "' resolves to the pack file '" + found + "'; its frames are derived from the image size when it is "
                + "first drawn");
    }

    // --- helpers ---

    private JsonObject readObject(String namespace, String relativePath, String pointer) {
        Path file = pack.root().resolve("assets").resolve(namespace).resolve(relativePath);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsoncReader.parse(text);
            if (root instanceof JsonObject object) {
                return object;
            }
            report.warning(pack.id(), sourceName, pointer, "Expected a JSON object at the root of '"
                    + namespace + ":" + relativePath + "' but found " + JsonValues.describeType(root));
        } catch (JsoncReader.JsoncException e) {
            report.warning(pack.id(), sourceName, pointer, "Could not parse '" + namespace + ":" + relativePath
                    + "': " + e.describe());
        } catch (IOException e) {
            report.warning(pack.id(), sourceName, pointer, "Could not read '" + namespace + ":" + relativePath
                    + "': " + e.getMessage());
        }
        return null;
    }

    private static String candidates(String namespace, String relativePath, List<String> suffixes) {
        return namespace + ":" + String.join(", " + namespace + ":",
                PackResourceIndex.describeCandidates(relativePath, suffixes));
    }

    private static String summarise(Set<String> names) {
        int limit = 10;
        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (String name : names) {
            if (index == limit) {
                builder.append(", … (").append(names.size() - limit).append(" more)");
                break;
            }
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(name);
            index++;
        }
        return builder.toString();
    }
}

