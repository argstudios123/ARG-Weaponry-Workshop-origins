package com.arg_studios.arg_weaponry.weapon;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsonValues;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.pack.ContentInventory;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.PackResourceIndex;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers weapon definitions inside the discovered content packs and parses them.
 *
 * <p>Definitions are looked up below {@code assets/<namespace>/weapons_registry/weapons/}, the one
 * location the existing schema establishes. Discovery itself - recursion, the ".json" filter, the derived registry
 * identity - lives in {@link WeaponDefinitionDiscovery}; this class adds the per-file diagnostics, the duplicate
 * checks and the parsing. Other registry categories, HUD, effects and data files are reported but not parsed by this
 * milestone.</p>
 *
 * <p>Each file is handled independently: reading, JSON syntax, document structure, required
 * properties and duplicate identities can each reject one definition while all others continue.</p>
 */
public final class WeaponDefinitionLoader {

    /** Directory below a namespace that holds the weapon registry. */
    public static final String REGISTRY_DIRECTORY = "weapons_registry";

    /** Directory below the registry that holds firearm definitions. */
    public static final String WEAPONS_DIRECTORY = REGISTRY_DIRECTORY + "/weapons";

    private WeaponDefinitionLoader() {
    }

    /**
     * Loads every weapon definition from the given packs.
     *
     * @param packs  the discovered content packs
     * @param report the report to collect diagnostics in
     * @return the accepted and rejected definitions; never {@code null}
     */
    public static WeaponLoadResult load(List<ContentPack> packs, ValidationReport report) {
        List<WeaponLoadResult.LoadedWeapon> weapons = new ArrayList<>();
        List<WeaponLoadResult.RejectedDefinition> rejected = new ArrayList<>();
        Map<ResourceId, String> claimedFileIdentities = new LinkedHashMap<>();
        Map<ResourceId, String> claimedAuthoritativeIdentities = new LinkedHashMap<>();

        for (ContentPack pack : packs) {
            PackResourceIndex index = PackResourceIndex.of(pack);
            for (String readError : index.readErrors()) {
                report.warning(pack.id(), "assets", readError);
            }
            ContentInventory.reportEmptyPlaceholders(pack, report);

            for (String namespace : pack.resourceNamespaces()) {
                Path weaponsRoot = pack.root().resolve("assets").resolve(namespace).resolve(WEAPONS_DIRECTORY);
                for (Path file : listDefinitionFiles(pack, namespace, weaponsRoot, report)) {
                    loadDefinition(pack, index, namespace, weaponsRoot, file, report, weapons, rejected,
                            claimedFileIdentities, claimedAuthoritativeIdentities);
                }
            }
        }

        return new WeaponLoadResult(weapons, rejected);
    }

    // --- file discovery ---

    private static List<Path> listDefinitionFiles(ContentPack pack, String namespace, Path weaponsRoot,
                                                  ValidationReport report) {
        if (!Files.isDirectory(weaponsRoot)) {
            return List.of();
        }
        try {
            List<Path> files = WeaponDefinitionDiscovery.listDefinitionFiles(weaponsRoot);
            if (files.isEmpty()) {
                report.info(pack.id(), "assets/" + namespace + "/" + WEAPONS_DIRECTORY,
                        "The weapon directory exists but contains no definitions");
            }
            return files;
        } catch (IOException e) {
            report.error(pack.id(), "assets/" + namespace + "/" + WEAPONS_DIRECTORY,
                    "Failed to list weapon definitions: " + e.getMessage());
            return List.of();
        }
    }

    // --- single definition ---

    private static void loadDefinition(ContentPack pack, PackResourceIndex index, String namespace, Path weaponsRoot,
                                       Path file, ValidationReport report,
                                       List<WeaponLoadResult.LoadedWeapon> weapons,
                                       List<WeaponLoadResult.RejectedDefinition> rejected,
                                       Map<ResourceId, String> claimedFileIdentities,
                                       Map<ResourceId, String> claimedAuthoritativeIdentities) {
        String packRelative = pack.root().relativize(file).toString().replace('\\', '/');

        ResourceId registryId;
        try {
            registryId = WeaponDefinitionDiscovery.registryIdentity(namespace, weaponsRoot, file);
        } catch (IllegalArgumentException e) {
            report.error(pack.id(), packRelative, "$", "The file location cannot form a valid registry identity: "
                    + e.getMessage() + ". The file was left unchanged");
            rejected.add(new WeaponLoadResult.RejectedDefinition(pack.id(), packRelative,
                    "invalid registry identity derived from the file path"));
            return;
        }

        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            report.error(pack.id(), packRelative, "Failed to read the weapon definition: " + e.getMessage());
            rejected.add(new WeaponLoadResult.RejectedDefinition(pack.id(), packRelative, "unreadable file"));
            return;
        }

        if (text.isBlank()) {
            report.error(pack.id(), packRelative, "The weapon definition file is empty. An empty weapon definition "
                    + "cannot be interpreted; the file was left unchanged");
            rejected.add(new WeaponLoadResult.RejectedDefinition(pack.id(), packRelative, "empty file"));
            return;
        }

        JsonElement root;
        try {
            root = JsoncReader.parse(text);
        } catch (JsoncReader.JsoncException e) {
            report.error(pack.id(), packRelative, e.describeLocation(), "Invalid JSON: " + e.getMessage());
            // The location belongs in the rejection reason too: the summary line a creator reads first is the
            // "rejected weapon definition ..." log entry, and without the position a syntax error in a large
            // definition is nearly impossible to find.
            rejected.add(new WeaponLoadResult.RejectedDefinition(pack.id(), packRelative,
                    "invalid JSON (" + e.describeLocation() + ")"));
            return;
        }

        if (!(root instanceof JsonObject document)) {
            report.error(pack.id(), packRelative, "$", "Expected a JSON object at the document root but found "
                    + JsonValues.describeType(root));
            rejected.add(new WeaponLoadResult.RejectedDefinition(pack.id(), packRelative,
                    "document root is not an object"));
            return;
        }

        WeaponDefinition definition = new WeaponDefinitionParser(pack.id(), packRelative, registryId, report)
                .parse(document);
        if (definition == null) {
            rejected.add(new WeaponLoadResult.RejectedDefinition(pack.id(), packRelative,
                    "required properties are missing or invalid"));
            return;
        }

        String previous = claimedFileIdentities.putIfAbsent(registryId, packRelative);
        if (previous != null) {
            report.error(pack.id(), packRelative, "$", "Duplicate weapon registry identity '" + registryId
                    + "'; it is already provided by " + previous + ". This definition is ignored");
            rejected.add(new WeaponLoadResult.RejectedDefinition(pack.id(), packRelative,
                    "duplicate registry identity '" + registryId + "'"));
            return;
        }

        ResourceId authoritativeId = definition.authoritativeId();
        if (authoritativeId != null) {
            String previousAuthoritative = claimedAuthoritativeIdentities.putIfAbsent(authoritativeId, packRelative);
            if (previousAuthoritative != null) {
                report.error(pack.id(), packRelative, "$.weapon_id", "Duplicate weapon identity '" + authoritativeId
                        + "'; it is already provided by " + previousAuthoritative
                        + ". This definition is rejected and no item is registered for it");
                rejected.add(new WeaponLoadResult.RejectedDefinition(pack.id(), packRelative,
                        "duplicate weapon identity '" + authoritativeId + "'"));
                return;
            }
        }

        WeaponReferenceValidator.validate(pack, index, definition, packRelative, report);
        weapons.add(new WeaponLoadResult.LoadedWeapon(pack, definition));
        report.info(pack.id(), packRelative, "$", "Parsed weapon definition '" + registryId
                + (authoritativeId == null ? "' (no authoritative weapon_id)" : "' as '" + authoritativeId + "'"));
    }
}
