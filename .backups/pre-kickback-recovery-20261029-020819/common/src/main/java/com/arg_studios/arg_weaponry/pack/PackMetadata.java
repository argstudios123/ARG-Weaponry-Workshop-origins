package com.arg_studios.arg_weaponry.pack;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Metadata of a content pack, read from {@code pack.mcmeta} and {@code pack_id.json}.
 *
 * <p>No field is required. Missing metadata produces diagnostics and {@code null} values instead of
 * failing the pack, so a creator can start with an almost empty pack. Unknown fields are reported as
 * informational diagnostics rather than errors, because the schema is expected to grow.</p>
 *
 * @param description      the {@code description} of {@code pack.mcmeta}'s {@code pack} object
 * @param formatInfo       a short description of the declared pack format, for logging only
 * @param nameKey          {@code pack_name} from {@code pack_id.json}, currently a translation key
 * @param declaredPackId   {@code pack_id} from {@code pack_id.json}, when present
 * @param creativeIcon     {@code creative_icon} from {@code pack_id.json}, meaning still to be decided
 * @param version          {@code pack_version} from {@code pack_id.json}
 */
public record PackMetadata(
        String description,
        String formatInfo,
        String nameKey,
        String declaredPackId,
        String creativeIcon,
        String version
) {

    private static final String PACK_MCMETA = "pack.mcmeta";
    private static final String PACK_ID_JSON = "pack_id.json";

    private static final Set<String> MCMETA_KEYS = Set.of("pack");
    private static final Set<String> PACK_KEYS = Set.of("description", "pack_format", "supported_formats", "min_format", "max_format");
    private static final Set<String> PACK_ID_KEYS = Set.of("pack_id", "pack_name", "creative_icon", "pack_version");

    /**
     * Reads the metadata of a content pack.
     *
     * <p>Diagnostics are reported under {@code packFolderName}, which is what the creator sees on
     * disk, even when {@code pack_id.json} declares a different pack id.</p>
     *
     * @param packRoot       the pack directory
     * @param packFolderName the folder name, used as the diagnostic scope
     * @param report         the report to collect diagnostics in
     * @return the parsed metadata; never {@code null}
     */
    public static PackMetadata read(Path packRoot, String packFolderName, ValidationReport report) {
        JsonObject mcmeta = readObject(packRoot.resolve(PACK_MCMETA), packFolderName, report);
        String description = null;
        String formatInfo = null;
        if (mcmeta != null) {
            reportUnknownKeys(mcmeta, MCMETA_KEYS, packFolderName, PACK_MCMETA, "pack", report);
            JsonElement packElement = mcmeta.get("pack");
            if (packElement instanceof JsonObject packObject) {
                reportUnknownKeys(packObject, PACK_KEYS, packFolderName, PACK_MCMETA, "pack", report);
                description = readString(packObject, "description", packFolderName, PACK_MCMETA, "pack", report);
                formatInfo = readFormat(packObject, packFolderName, report);
            } else {
                report.warning(packFolderName, PACK_MCMETA, "Expected an object under 'pack' but found " + describeType(packElement));
            }
        } else if (!Files.isRegularFile(packRoot.resolve(PACK_MCMETA))) {
            report.warning(packFolderName, PACK_MCMETA, "Missing pack.mcmeta; the pack description and format cannot be read");
        }

        JsonObject packId = readObject(packRoot.resolve(PACK_ID_JSON), packFolderName, report);
        String nameKey = null;
        String declaredPackId = null;
        String creativeIcon = null;
        String version = null;
        if (packId != null) {
            reportUnknownKeys(packId, PACK_ID_KEYS, packFolderName, PACK_ID_JSON, null, report);
            nameKey = readString(packId, "pack_name", packFolderName, PACK_ID_JSON, null, report);
            declaredPackId = readString(packId, "pack_id", packFolderName, PACK_ID_JSON, null, report);
            creativeIcon = readString(packId, "creative_icon", packFolderName, PACK_ID_JSON, null, report);
            version = readString(packId, "pack_version", packFolderName, PACK_ID_JSON, null, report);
        } else if (!Files.isRegularFile(packRoot.resolve(PACK_ID_JSON))) {
            report.warning(packFolderName, PACK_ID_JSON, "Missing pack_id.json; the pack identity falls back to the folder name");
        }

        return new PackMetadata(description, formatInfo, nameKey, declaredPackId, creativeIcon, version);
    }

    // --- helpers ---

    private static JsonObject readObject(Path file, String packId, ValidationReport report) {
        if (!Files.isRegularFile(file)) {
            return null;
        }

        String fileName = file.getFileName().toString();
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            report.error(packId, fileName, "Failed to read file: " + e.getMessage());
            return null;
        }

        try {
            JsonElement root = JsoncReader.parse(text);
            if (root instanceof JsonObject object) {
                return object;
            }
            report.error(packId, fileName, "Expected a JSON object at the document root but found " + describeType(root));
            return null;
        } catch (JsoncReader.JsoncException e) {
            report.error(packId, fileName, e.describeLocation(), e.getMessage());
            return null;
        }
    }

    private static String readString(JsonObject object, String key, String packId, String source, String scope,
                                     ValidationReport report) {
        JsonElement element = object.get(key);
        if (element == null) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        report.warning(packId, source, "Expected a string for '" + label(scope, key) + "' but found " + describeType(element));
        return null;
    }

    private static String readFormat(JsonObject packObject, String packId, ValidationReport report) {
        JsonElement legacy = packObject.get("pack_format");
        if (legacy != null) {
            if (legacy.isJsonPrimitive() && legacy.getAsJsonPrimitive().isNumber()) {
                return legacy.getAsString();
            }
            if (legacy.isJsonArray()) {
                return legacy.toString();
            }
            report.warning(packId, PACK_MCMETA, "Expected a number for 'pack.pack_format' but found " + describeType(legacy));
        }

        String min = formatComponent(packObject, "min_format");
        String max = formatComponent(packObject, "max_format");
        if (min != null || max != null) {
            return (min == null ? "?" : min) + ".." + (max == null ? "?" : max);
        }
        return null;
    }

    private static String formatComponent(JsonObject packObject, String key) {
        JsonElement element = packObject.get(key);
        if (element == null) {
            return null;
        }
        if (element.isJsonArray()) {
            return element.toString();
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsString();
        }
        return null;
    }

    private static void reportUnknownKeys(JsonObject object, Set<String> known, String packId, String source, String scope,
                                          ValidationReport report) {
        for (String key : object.keySet()) {
            if (!known.contains(key)) {
                report.info(packId, source, "Unknown field '" + label(scope, key)
                        + "' is ignored; it may belong to a newer content-pack format");
            }
        }
    }

    private static String label(String scope, String key) {
        return scope == null || scope.isEmpty() ? key : scope + "." + key;
    }

    private static String describeType(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonObject()) {
            return "an object";
        }
        if (element.isJsonArray()) {
            return "an array";
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                return "a string";
            }
            if (primitive.isNumber()) {
                return "a number";
            }
            if (primitive.isBoolean()) {
                return "a boolean";
            }
        }
        return "an unsupported value";
    }
}
