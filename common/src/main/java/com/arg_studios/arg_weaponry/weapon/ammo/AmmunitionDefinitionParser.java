package com.arg_studios.arg_weaponry.weapon.ammo;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsonValues;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

/**
 * Turns one ammunition definition document into an {@link AmmunitionDefinition}.
 *
 * <p>The failure policy mirrors the weapon parser: a missing or malformed <em>required</em> property rejects
 * the definition, because an ammunition definition without a logical name or without a registry id cannot
 * be interpreted safely; a malformed <em>optional</em> property is reported and ignored, and the definition
 * stays usable. Nothing is ever invented to fill a gap — no fallback name, no fallback registry id.</p>
 *
 * <p>The four field names are the ones the packs already use and are not renamed. Unknown properties are
 * reported as informational and preserved, because the schema is meant to grow.</p>
 */
public final class AmmunitionDefinitionParser {

    private static final Set<String> KNOWN_PROPERTIES = Set.of("ammo_name", "ammo_id", "type", "display");

    /** Upper bound for a logical ammunition name, generous but bounded for diagnostics and logs. */
    private static final int MAX_NAME_LENGTH = 64;

    private final String packId;
    private final String sourceName;
    private final ResourceId fileIdentity;
    private final ValidationReport report;

    private boolean rejected;

    /**
     * @param packId       the content pack the definition belongs to, for diagnostics
     * @param sourceName   the definition file, for diagnostics
     * @param fileIdentity the identity derived from the file location
     * @param report       the report to collect diagnostics in
     */
    public AmmunitionDefinitionParser(String packId, String sourceName, ResourceId fileIdentity,
                                      ValidationReport report) {
        this.packId = packId;
        this.sourceName = sourceName;
        this.fileIdentity = fileIdentity;
        this.report = report;
    }

    /**
     * Parses an ammunition definition document.
     *
     * @param root the parsed JSON object
     * @return the definition, or {@code null} when a required property is missing or invalid
     */
    public AmmunitionDefinition parse(JsonObject root) {
        for (String key : root.keySet()) {
            if (!KNOWN_PROPERTIES.contains(key)) {
                report.info(packId, sourceName, pointer(key),
                        "Unknown property '" + key + "' is preserved but not interpreted by this milestone");
            }
        }

        String ammoName = readName(root);
        ResourceId ammoId = readAmmoId(root);
        String type = readType(root);
        ResourceId display = readDisplay(root);

        if (rejected) {
            return null;
        }
        return new AmmunitionDefinition(fileIdentity, ammoName, ammoId, type, display);
    }

    private String readName(JsonObject root) {
        JsonElement element = root.get("ammo_name");
        if (element == null) {
            report.error(packId, sourceName, pointer("ammo_name"), "Required property 'ammo_name' is missing. "
                    + "Without it no weapon's 'ammo_to_use' entry can reference this ammunition");
            rejected = true;
            return null;
        }
        if (!JsonValues.isString(element)) {
            report.error(packId, sourceName, pointer("ammo_name"), "Expected a string for 'ammo_name' but found "
                    + JsonValues.describeType(element));
            rejected = true;
            return null;
        }
        String name = element.getAsString();
        if (name.isBlank()) {
            report.error(packId, sourceName, pointer("ammo_name"), "'ammo_name' is empty");
            rejected = true;
            return null;
        }
        if (name.length() > MAX_NAME_LENGTH) {
            report.error(packId, sourceName, pointer("ammo_name"), "'ammo_name' is longer than "
                    + MAX_NAME_LENGTH + " characters");
            rejected = true;
            return null;
        }
        if (!name.equals(name.strip())) {
            report.warning(packId, sourceName, pointer("ammo_name"), "'ammo_name' has leading or trailing "
                    + "whitespace. The value is kept exactly as written, so an 'ammo_to_use' entry has to "
                    + "match the whitespace to resolve");
        }
        return name;
    }

    private ResourceId readAmmoId(JsonObject root) {
        JsonElement element = root.get("ammo_id");
        if (element == null) {
            report.error(packId, sourceName, pointer("ammo_id"), "Required property 'ammo_id' is missing. It is "
                    + "the Minecraft item id this ammunition is registered under");
            rejected = true;
            return null;
        }
        if (!JsonValues.isString(element)) {
            report.error(packId, sourceName, pointer("ammo_id"), "Expected a string for 'ammo_id' but found "
                    + JsonValues.describeType(element));
            rejected = true;
            return null;
        }
        ResourceId.Result result = ResourceId.tryParse(element.getAsString());
        if (!result.isValid()) {
            report.error(packId, sourceName, pointer("ammo_id"), "'ammo_id' value '" + element.getAsString()
                    + "' is not a valid Minecraft item identifier: " + result.error());
            rejected = true;
            return null;
        }
        return result.id();
    }

    private String readType(JsonObject root) {
        JsonElement element = root.get("type");
        if (element == null) {
            report.error(packId, sourceName, pointer("type"), "Required property 'type' is missing; expected '"
                    + AmmunitionDefinition.TYPE_AMMO + "'");
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
        if (!AmmunitionDefinition.TYPE_AMMO.equalsIgnoreCase(value)) {
            report.error(packId, sourceName, pointer("type"), "'type' must be '"
                    + AmmunitionDefinition.TYPE_AMMO + "' for an ammunition definition but is '" + value + "'");
            rejected = true;
            return null;
        }
        return AmmunitionDefinition.TYPE_AMMO;
    }

    private ResourceId readDisplay(JsonObject root) {
        JsonElement element = root.get("display");
        if (element == null) {
            report.info(packId, sourceName, pointer("display"), "No 'display' is declared; the ammunition item "
                    + "will use the documented fallback item model instead of custom artwork");
            return null;
        }
        if (!JsonValues.isString(element)) {
            report.warning(packId, sourceName, pointer("display"), "Expected a resource identifier string for "
                    + "'display' but found " + JsonValues.describeType(element) + "; the property is ignored");
            return null;
        }
        ResourceId.Result result = ResourceId.tryParse(element.getAsString());
        if (!result.isValid()) {
            report.warning(packId, sourceName, pointer("display"), "'display' value '" + element.getAsString()
                    + "' is not a valid resource identifier: " + result.error()
                    + "; the property is ignored and the fallback item model is used");
            return null;
        }
        return result.id();
    }

    private static String pointer(String key) {
        return "$." + key;
    }
}
