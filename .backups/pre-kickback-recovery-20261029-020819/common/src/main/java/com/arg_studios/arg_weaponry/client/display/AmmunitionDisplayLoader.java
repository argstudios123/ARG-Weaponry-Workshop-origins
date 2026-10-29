package com.arg_studios.arg_weaponry.client.display;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsonValues;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackResources;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionDefinition;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the display definition that an ammunition definition's {@code display} reference points at.
 *
 * <p>Resolution follows the same rules as weapon displays: the identifier is looked up below
 * {@code assets/<namespace>/display/} inside the owning content pack, because content packs are deliberately
 * not mounted as vanilla resource packs. The value is therefore a <em>display document</em>, not a model or
 * texture path.</p>
 *
 * <h2>Choosing the rendering mode</h2>
 * <p>{@code "render"} is the explicit discriminator:</p>
 * <pre>{@code
 * // A. conventional 2D Minecraft item (no geometry, no texture, no new renderer)
 * {
 *   "render": "item",
 *   "item_model": "minecraft:arrow"
 * }
 *
 * // B. custom 3D ammunition model (existing Bedrock geometry + texture conventions)
 * {
 *   "render": "model",
 *   "model": "arg_pack:ammunition/dmrs_ammo/dmr_ammo",
 *   "texture": "arg_pack:ammunition/dmrs_ammo/dmr_ammo",
 *   "animations": "arg_pack:ammunition/dmrs_ammo/dmr_ammo",
 *   "display": { "gui": { "rotation": [0, 0, 0], "scale": [0.5, 0.5, 0.5] } }
 * }
 * }</pre>
 * <p>{@code render} is optional so existing documents keep working: a document that declares {@code model} is
 * a 3D definition, and one that declares neither is a 2D definition using the fallback icon. The shipped
 * {@code dmr_ammo} display was written without {@code render} and therefore resolves to 3D mode, unchanged.</p>
 *
 * <p>Per-context transforms are accepted under {@code display} (the key the weapon loader reads) and under
 * {@code display_views} (the key the shipped documents actually use); {@code display} wins when both are
 * present, and the choice is reported once.</p>
 */
public final class AmmunitionDisplayLoader {

    /**
     * Directory below a namespace that holds display definitions. Ammunition displays live beside weapon
     * displays, which is why this is the same constant rather than a second convention.
     */
    public static final String DISPLAY_DIRECTORY = WeaponDisplayLoader.DISPLAY_DIRECTORY;

    private AmmunitionDisplayLoader() {
    }

    /**
     * Loads the display definition of one ammunition definition.
     *
     * @param pack       the owning content pack
     * @param definition the ammunition definition
     * @param report     the report to collect diagnostics in
     * @return the parsed display, or empty when there is none or it cannot be read
     */
    public static Optional<AmmunitionDisplay> load(ContentPack pack, AmmunitionDefinition definition,
                                                   ValidationReport report) {
        if (pack == null || definition == null) {
            return Optional.empty();
        }
        String source = definition.fileIdentity() == null ? "ammunition" : definition.fileIdentity().asString();
        ResourceId displayId = definition.display();
        if (displayId == null) {
            report.info(pack.id(), source, "$.display", "The ammunition definition declares no display, so the "
                    + "item uses the documented fallback item model");
            return Optional.empty();
        }

        Optional<ContentPackResources.Resolved> resolved = ContentPackResources.find(pack, displayId,
                WeaponDisplayLoader.DISPLAY_DIRECTORY, ContentPackResources.JSON_SUFFIXES);
        if (resolved.isEmpty()) {
            report.warning(pack.id(), source, "$.display", "The ammunition display '" + displayId
                    + "' was not found; tried " + ContentPackResources.describeCandidates(displayId,
                    WeaponDisplayLoader.DISPLAY_DIRECTORY, ContentPackResources.JSON_SUFFIXES)
                    + ". The item keeps working and uses the documented fallback item model");
            return Optional.empty();
        }

        Optional<String> text = ContentPackResources.readText(resolved.get());
        if (text.isEmpty()) {
            report.warning(pack.id(), resolved.get().relativePath(),
                    "The ammunition display could not be read from disk; the fallback item model is used");
            return Optional.empty();
        }

        JsonElement root;
        try {
            root = JsoncReader.parse(text.get());
        } catch (JsoncReader.JsoncException e) {
            report.warning(pack.id(), resolved.get().relativePath(), e.describeLocation(),
                    "Invalid JSON in the ammunition display: " + e.getMessage()
                            + ". The fallback item model is used");
            return Optional.empty();
        }
        if (!(root instanceof JsonObject document)) {
            report.warning(pack.id(), resolved.get().relativePath(), "$", "Expected a JSON object at the root of "
                    + "the ammunition display but found " + JsonValues.describeType(root)
                    + "; the fallback item model is used");
            return Optional.empty();
        }

        return Optional.of(read(pack, displayId, resolved.get().relativePath(), document, report));
    }

    private static AmmunitionDisplay read(ContentPack pack, ResourceId displayId, String source, JsonObject document,
                                          ValidationReport report) {
        AmmunitionDisplay.Mode mode = readMode(pack, source, document, report);
        ResourceId itemModel = readReference(pack, source, document, "item_model", false, report);
        ResourceId model = readReference(pack, source, document, "model", true, report);
        ResourceId texture = readReference(pack, source, document, "texture", true, report);
        ResourceId animations = readReference(pack, source, document, "animations", true, report);

        if (mode == AmmunitionDisplay.Mode.MODEL) {
            if (model == null) {
                report.warning(pack.id(), source, "$.model", "This display selects the 3D mode but declares no "
                        + "'model'; the fallback item model is used instead");
            }
            if (texture == null) {
                report.warning(pack.id(), source, "$.texture", "This display selects the 3D mode but declares no "
                        + "'texture'; the fallback item model is used instead");
            }
            if (itemModel == null) {
                report.info(pack.id(), source, "$.item_model", "No 'item_model' is declared. The item uses the "
                        + "fallback inventory icon; adding \"item_model\" to this display chooses a 2D icon without "
                        + "changing the 3D resources");
            }
        } else if (model != null || texture != null) {
            report.info(pack.id(), source, "$.render", "This display selects the 2D item mode, so 'model' and "
                    + "'texture' are read but not used. They are kept for a future switch to \"render\": \"model\"");
        }

        Map<String, DisplayTransform> transforms = readTransforms(pack, source, document, report);
        if (mode == AmmunitionDisplay.Mode.MODEL) {
            checkResource(pack, source, "model", model, "models", ContentPackResources.JSON_SUFFIXES, report);
            checkResource(pack, source, "texture", texture, "textures", ContentPackResources.TEXTURE_SUFFIXES,
                    report);
            checkResource(pack, source, "animations", animations, "animations",
                    ContentPackResources.ANIMATION_SUFFIXES, report);
        }
        return new AmmunitionDisplay(displayId, mode, itemModel, model, texture, animations, transforms);
    }

    /**
     * Reports a declared 3D resource whose file is missing.
     *
     * <p>The definition is never rejected for this: the item stays registered and usable, and the missing
     * artwork is reported once so a creator can fix the path. This is the ammunition counterpart of the
     * renderer-side "resource not found" warnings weapon displays produce when they are first drawn.</p>
     */
    private static void checkResource(ContentPack pack, String source, String key, ResourceId id, String directory,
                                      java.util.List<String> suffixes, ValidationReport report) {
        if (id == null) {
            return;
        }
        if (ContentPackResources.find(pack, id, directory, suffixes).isEmpty()) {
            report.warning(pack.id(), source, "$." + key, "The " + key + " resource '" + id + "' was not found; "
                    + "tried " + ContentPackResources.describeCandidates(id, directory, suffixes)
                    + ". The ammunition definition stays valid and the fallback item model is used");
        }
    }

    private static AmmunitionDisplay.Mode readMode(ContentPack pack, String source, JsonObject document,
                                                   ValidationReport report) {
        JsonElement element = document.get("render");
        boolean declaresModel = document.has("model");
        AmmunitionDisplay.Mode inferred =
                declaresModel ? AmmunitionDisplay.Mode.MODEL : AmmunitionDisplay.Mode.ITEM;

        if (element == null) {
            report.info(pack.id(), source, "$.render", "No 'render' is declared, so the mode is inferred from the "
                    + "resources present: " + inferred.name().toLowerCase(Locale.ROOT)
                    + ". Declare \"render\": \"item\" or \"render\": \"model\" to make it explicit");
            return inferred;
        }
        if (!JsonValues.isString(element)) {
            report.warning(pack.id(), source, "$.render", "Expected \"item\" or \"model\" but found "
                    + JsonValues.describeType(element) + "; the mode is inferred from the declared resources");
            return inferred;
        }
        String value = element.getAsString();
        if ("item".equalsIgnoreCase(value)) {
            return AmmunitionDisplay.Mode.ITEM;
        }
        if ("model".equalsIgnoreCase(value)) {
            return AmmunitionDisplay.Mode.MODEL;
        }
        report.warning(pack.id(), source, "$.render", "Unknown render mode '" + value + "'; expected 'item' or "
                + "'model'. The mode is inferred from the declared resources");
        return inferred;
    }

    /**
     * Reads one optional resource-identifier property.
     *
     * @param pack                  the owning pack
     * @param source               the document, for diagnostics
     * @param document              the parsed document
     * @param key                   the property name
     * @param requirePackNamespace  whether the reference must stay inside a namespace this pack provides.
     *                              Geometry, texture and animation references are pack resources and must be;
     *                              {@code item_model} is an <em>item id</em> whose baked model is reused, so it
     *                              may legitimately name a vanilla or another mod's item.
     * @param report                the report to collect diagnostics in
     * @return the reference, or {@code null} when it is absent, malformed or not allowed
     */
    private static ResourceId readReference(ContentPack pack, String source, JsonObject document, String key,
                                            boolean requirePackNamespace, ValidationReport report) {
        JsonElement element = document.get(key);
        if (element == null) {
            return null;
        }
        if (!JsonValues.isString(element)) {
            report.warning(pack.id(), source, "$." + key, "Expected a resource identifier string for '" + key
                    + "' but found " + JsonValues.describeType(element) + "; the value is ignored");
            return null;
        }
        ResourceId.Result result = ResourceId.tryParse(element.getAsString());
        if (!result.isValid()) {
            report.warning(pack.id(), source, "$." + key, "The " + key + " reference '" + element.getAsString()
                    + "' is not a valid resource identifier: " + result.error() + "; the value is ignored");
            return null;
        }
        if (requirePackNamespace && !pack.providesNamespace(result.id().namespace())) {
            report.warning(pack.id(), source, "$." + key, "The " + key + " reference '" + result.id()
                    + "' uses namespace '" + result.id().namespace() + "', which this content pack does not "
                    + "provide; the value is ignored");
            return null;
        }
        return result.id();
    }

    private static Map<String, DisplayTransform> readTransforms(ContentPack pack, String source, JsonObject document,
                                                                ValidationReport report) {
        JsonElement element = document.get("display");
        String key = "display";
        if (element == null && document.has("display_views")) {
            element = document.get("display_views");
            key = "display_views";
            report.info(pack.id(), source, "$.display_views", "The transforms are declared under 'display_views'; "
                    + "they are read from there, but the weapon display loader reads 'display', so the two kinds "
                    + "of document currently use different keys");
        }
        if (element == null) {
            report.info(pack.id(), source, "$.display", "The ammunition display declares no per-context "
                    + "transforms; every context uses the identity transform");
            return Map.of();
        }
        if (!(element instanceof JsonObject display)) {
            report.warning(pack.id(), source, "$." + key, "Expected an object of display contexts but found "
                    + JsonValues.describeType(element) + "; every context uses the identity transform");
            return Map.of();
        }

        Map<String, DisplayTransform> transforms = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : display.entrySet()) {
            String context = entry.getKey();
            String pointer = "$." + key + "." + context;
            if (!WeaponDisplayLoader.KNOWN_CONTEXTS.contains(context)) {
                report.warning(pack.id(), source, pointer, "Unknown display context '" + context
                        + "'; it is preserved but never used. Known contexts: "
                        + WeaponDisplayLoader.KNOWN_CONTEXTS);
                continue;
            }
            if (!(entry.getValue() instanceof JsonObject transform)) {
                report.warning(pack.id(), source, pointer, "Expected a transform object but found "
                        + JsonValues.describeType(entry.getValue())
                        + "; the context keeps the identity transform");
                continue;
            }
            transforms.put(context, new DisplayTransform(
                    readVector(pack, source, transform.get("translation"), pointer + ".translation", report),
                    readVector(pack, source, transform.get("rotation"), pointer + ".rotation", report),
                    readVector(pack, source, transform.get("scale"), pointer + ".scale", report)));
        }
        return transforms;
    }

    private static Vec3 readVector(ContentPack pack, String source, JsonElement element, String pointer,
                                   ValidationReport report) {
        if (element == null) {
            return null;
        }
        if (!(element instanceof JsonArray array) || array.size() < 3) {
            report.warning(pack.id(), source, pointer, "Expected an array of three numbers but found "
                    + JsonValues.describeType(element) + "; the value is treated as zero");
            return null;
        }
        Double x = JsonValues.asDouble(array.get(0));
        Double y = JsonValues.asDouble(array.get(1));
        Double z = JsonValues.asDouble(array.get(2));
        if (x == null || y == null || z == null) {
            report.warning(pack.id(), source, pointer,
                    "The vector components are not all numbers; the value is treated as zero");
            return null;
        }
        return new Vec3(x.floatValue(), y.floatValue(), z.floatValue());
    }
}
