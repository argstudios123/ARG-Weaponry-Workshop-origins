package com.arg_studios.arg_weaponry.client.display;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsonValues;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackResources;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the display configuration of one weapon through its owning content pack.
 *
 * <p>The display document is the existing schema's entry point: it names the model, texture, animation
 * resource, HUD slot texture and the per-context transforms. Everything is resolved with
 * {@link ContentPackResources}, so no content pack has to be mounted as a vanilla resource pack and no
 * resource path is rewritten.</p>
 */
public final class WeaponDisplayLoader {

    /** Directory below a namespace that holds display configurations. */
    public static final String DISPLAY_DIRECTORY = "display";

    /**
     * The display-context names the schema can use. They are exactly vanilla's serialized
     * {@code ItemDisplayContext} names, kept here as strings so this class stays game-independent.
     */
    public static final List<String> KNOWN_CONTEXTS = List.of(
            "none",
            "thirdperson_lefthand",
            "thirdperson_righthand",
            "firstperson_lefthand",
            "firstperson_righthand",
            "head",
            "gui",
            "ground",
            "fixed",
            "on_shelf");

    private WeaponDisplayLoader() {
    }

    /**
     * Loads the display configuration of a weapon definition.
     *
     * @param pack       the owning content pack
     * @param definition the weapon definition
     * @param report     the report to collect diagnostics in
     * @return the parsed configuration, or empty when it is missing or unusable
     */
    public static Optional<WeaponDisplay> load(ContentPack pack, WeaponDefinition definition,
                                               ValidationReport report) {
        ResourceId displayId = definition.display();
        String source = definition.fileIdentity() == null ? "display" : definition.fileIdentity().asString();
        if (displayId == null) {
            report.error(pack.id(), source, "$.display",
                    "The weapon definition declares no display configuration, so no model, texture or transform can "
                            + "be resolved for it");
            return Optional.empty();
        }

        Optional<ContentPackResources.Resolved> resolved =
                ContentPackResources.find(pack, displayId, DISPLAY_DIRECTORY, ContentPackResources.JSON_SUFFIXES);
        if (resolved.isEmpty()) {
            report.error(pack.id(), source, "$.display", "The display configuration '" + displayId
                    + "' was not found; tried "
                    + ContentPackResources.describeCandidates(displayId, DISPLAY_DIRECTORY,
                    ContentPackResources.JSON_SUFFIXES));
            return Optional.empty();
        }

        ContentPackResources.Resolved resource = resolved.get();
        Optional<String> text = ContentPackResources.readText(resource);
        if (text.isEmpty()) {
            report.error(pack.id(), resource.relativePath(),
                    "The display configuration could not be read from disk");
            return Optional.empty();
        }

        JsonElement root;
        try {
            root = JsoncReader.parse(text.get());
        } catch (JsoncReader.JsoncException e) {
            report.error(pack.id(), resource.relativePath(), e.describeLocation(),
                    "Invalid JSON in the display configuration: " + e.getMessage());
            return Optional.empty();
        }

        if (!(root instanceof JsonObject document)) {
            report.error(pack.id(), resource.relativePath(), "$", "Expected a JSON object at the root of the display "
                    + "configuration but found " + JsonValues.describeType(root));
            return Optional.empty();
        }

        return Optional.of(read(pack, displayId, resource.relativePath(), document, report));
    }

    private static WeaponDisplay read(ContentPack pack, ResourceId displayId, String source, JsonObject document,
                                      ValidationReport report) {
        ResourceId model = readReference(pack, source, document, "model", true, report,
                "the weapon cannot be rendered without geometry");
        ResourceId texture = readReference(pack, source, document, "texture", true, report,
                "the weapon cannot be rendered without a texture");
        ResourceId animations = readReference(pack, source, document, "animations", false, report,
                "animation playback is not implemented in this milestone");
        ResourceId slot = readReference(pack, source, document, "slot", false, report,
                "the weapon keeps the built-in placeholder icon in GUI contexts until a slot texture is declared");

        for (String key : document.keySet()) {
            if (!key.equals("model") && !key.equals("texture") && !key.equals("animations") && !key.equals("slot")
                    && !key.equals("display")) {
                report.info(pack.id(), source, "$." + key,
                        "The display configuration declares '" + key + "', which this milestone does not interpret");
            }
        }

        Map<String, DisplayTransform> transforms = readTransforms(pack, source, document, report);
        return new WeaponDisplay(displayId, model, texture, animations, slot, transforms);
    }

    private static ResourceId readReference(ContentPack pack, String source, JsonObject document, String key,
                                            boolean required, ValidationReport report, String consequence) {
        JsonElement element = document.get(key);
        String pointer = "$." + key;
        if (element == null) {
            if (required) {
                report.error(pack.id(), source, pointer, "The display configuration declares no '" + key
                        + "'; " + consequence);
            } else {
                report.info(pack.id(), source, pointer,
                        "The display configuration declares no '" + key + "'; " + consequence);
            }
            return null;
        }

        if (!JsonValues.isString(element)) {
            report.error(pack.id(), source, pointer, "Expected a resource identifier string for '" + key
                    + "' but found " + JsonValues.describeType(element) + "; " + consequence);
            return null;
        }

        ResourceId.Result result = ResourceId.tryParse(element.getAsString());
        if (!result.isValid()) {
            report.error(pack.id(), source, pointer, "The " + key + " reference '" + element.getAsString()
                    + "' is not a valid resource identifier: " + result.error());
            return null;
        }
        if (!pack.providesNamespace(result.id().namespace())) {
            report.error(pack.id(), source, pointer, "The " + key + " reference '" + result.id()
                    + "' uses namespace '" + result.id().namespace() + "', which this content pack does not provide");
            return null;
        }
        return result.id();
    }

    private static Map<String, DisplayTransform> readTransforms(ContentPack pack, String source, JsonObject document,
                                                                ValidationReport report) {
        Map<String, DisplayTransform> transforms = new LinkedHashMap<>();
        JsonElement displayElement = document.get("display");
        if (displayElement == null) {
            report.info(pack.id(), source, "$.display", "The display configuration declares no per-context "
                    + "transforms; every context uses the identity transform");
            return transforms;
        }
        if (!(displayElement instanceof JsonObject display)) {
            report.error(pack.id(), source, "$.display", "Expected an object of display contexts but found "
                    + JsonValues.describeType(displayElement));
            return transforms;
        }

        for (Map.Entry<String, JsonElement> entry : display.entrySet()) {
            String context = entry.getKey();
            String pointer = "$.display." + context;
            if (!KNOWN_CONTEXTS.contains(context)) {
                report.warning(pack.id(), source, pointer, "Unknown display context '" + context
                        + "'; it is preserved but never used. Known contexts: " + KNOWN_CONTEXTS);
                continue;
            }
            if (!(entry.getValue() instanceof JsonObject transform)) {
                report.error(pack.id(), source, pointer, "Expected a transform object but found "
                        + JsonValues.describeType(entry.getValue()) + "; the context keeps the identity transform");
                continue;
            }

            for (String key : transform.keySet()) {
                if (!key.equals("translation") && !key.equals("rotation") && !key.equals("scale")) {
                    report.warning(pack.id(), source, pointer + "." + key, "Unknown transform property '" + key
                            + "'; it is ignored and the JSON was not modified");
                }
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

