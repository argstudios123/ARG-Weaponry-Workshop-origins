package com.arg_studios.arg_weaponry.weapon.ammo;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsonValues;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackResources;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinitionLoader;
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
import java.util.stream.Stream;

/**
 * Discovers and parses ammunition definitions inside the discovered content packs.
 *
 * <h2>Location</h2>
 * <p>Definitions live below {@code assets/<namespace>/weapons_registry/ammunition/}, which follows the
 * existing registry convention ({@code weapons_registry/weapons/} holds weapons) and lets a pack group
 * ammunition in subfolders, as the default pack does with {@code ammunition/dmrs_ammo/dmr_ammo.json}.
 * Discovery is recursive, sorted and hidden files are skipped, exactly like weapon discovery.</p>
 *
 * <h2>Global logical names</h2>
 * <p>An {@code ammo_name} is a <em>global</em> compatibility identity, because a weapon's
 * {@code ammo_to_use} entry carries no namespace. Two definitions that claim the same name would make the
 * meaning of a weapon's entry ambiguous, so the second one is rejected with an error that names the first
 * instead of one being picked silently. The same rule applies to two definitions claiming the same
 * {@code ammo_id}. Discovery order (pack order, then file path) makes the winner deterministic.</p>
 *
 * <p>A definition whose {@code display} reference cannot be resolved is kept: the reference is optional
 * artwork, so the item stays registrable and simply uses the documented fallback item model.</p>
 */
public final class AmmunitionDefinitionLoader {

    /** Directory below {@code weapons_registry} that holds ammunition definitions. */
    public static final String AMMUNITION_DIRECTORY = WeaponDefinitionLoader.REGISTRY_DIRECTORY + "/ammunition";

    private static final String JSON_EXTENSION = ".json";

    private AmmunitionDefinitionLoader() {
    }

    /**
     * Loads every ammunition definition from the given packs.
     *
     * @param packs  the discovered content packs, in discovery order
     * @param report the report to collect diagnostics in
     * @return the accepted and rejected definitions; never {@code null}
     */
    public static AmmunitionLoadResult load(List<ContentPack> packs, ValidationReport report) {
        List<AmmunitionLoadResult.LoadedAmmunition> accepted = new ArrayList<>();
        List<AmmunitionLoadResult.RejectedDefinition> rejected = new ArrayList<>();
        Map<String, String> claimedNames = new LinkedHashMap<>();
        Map<ResourceId, String> claimedItemIds = new LinkedHashMap<>();

        for (ContentPack pack : packs) {
            for (String namespace : pack.resourceNamespaces()) {
                Path root = pack.root().resolve("assets").resolve(namespace).resolve(AMMUNITION_DIRECTORY);
                for (Path file : listDefinitionFiles(pack, namespace, root, report)) {
                    loadDefinition(pack, namespace, root, file, report, accepted, rejected, claimedNames,
                            claimedItemIds);
                }
            }
        }

        return new AmmunitionLoadResult(accepted, rejected);
    }

    private static List<Path> listDefinitionFiles(ContentPack pack, String namespace, Path root,
                                                  ValidationReport report) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(JSON_EXTENSION))
                    .filter(file -> !isHidden(root.relativize(file)))
                    .sorted()
                    .toList();
            if (files.isEmpty()) {
                report.info(pack.id(), "assets/" + namespace + "/" + AMMUNITION_DIRECTORY,
                        "The ammunition directory exists but contains no definitions");
            }
            return files;
        } catch (IOException e) {
            report.error(pack.id(), "assets/" + namespace + "/" + AMMUNITION_DIRECTORY,
                    "Failed to list ammunition definitions: " + e.getMessage());
            return List.of();
        }
    }

    private static boolean isHidden(Path relative) {
        for (Path part : relative) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    // --- single definition ---

    private static void loadDefinition(ContentPack pack, String namespace, Path root, Path file,
                                       ValidationReport report,
                                       List<AmmunitionLoadResult.LoadedAmmunition> accepted,
                                       List<AmmunitionLoadResult.RejectedDefinition> rejected,
                                       Map<String, String> claimedNames,
                                       Map<ResourceId, String> claimedItemIds) {
        String packRelative = pack.root().relativize(file).toString().replace('\\', '/');
        String namespaceRelative = root.relativize(file).toString().replace('\\', '/');
        String identityPath = "ammunition/" + namespaceRelative
                .substring(0, namespaceRelative.length() - JSON_EXTENSION.length());

        ResourceId fileIdentity;
        try {
            fileIdentity = new ResourceId(namespace, identityPath);
        } catch (IllegalArgumentException e) {
            report.error(pack.id(), packRelative, "$", "The file location cannot form a valid identity: "
                    + e.getMessage() + ". The file was left unchanged");
            rejected.add(new AmmunitionLoadResult.RejectedDefinition(pack.id(), packRelative,
                    "invalid identity derived from the file path"));
            return;
        }

        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            report.error(pack.id(), packRelative, "Failed to read the ammunition definition: " + e.getMessage());
            rejected.add(new AmmunitionLoadResult.RejectedDefinition(pack.id(), packRelative, "unreadable file"));
            return;
        }
        if (text.isBlank()) {
            report.error(pack.id(), packRelative, "The ammunition definition file is empty and cannot be "
                    + "interpreted; the file was left unchanged");
            rejected.add(new AmmunitionLoadResult.RejectedDefinition(pack.id(), packRelative, "empty file"));
            return;
        }

        JsonElement document;
        try {
            document = JsoncReader.parse(text);
        } catch (JsoncReader.JsoncException e) {
            report.error(pack.id(), packRelative, e.describeLocation(), "Invalid JSON: " + e.getMessage());
            rejected.add(new AmmunitionLoadResult.RejectedDefinition(pack.id(), packRelative, "invalid JSON"));
            return;
        }
        if (!(document instanceof JsonObject rootObject)) {
            report.error(pack.id(), packRelative, "$", "Expected a JSON object at the document root but found "
                    + JsonValues.describeType(document));
            rejected.add(new AmmunitionLoadResult.RejectedDefinition(pack.id(), packRelative,
                    "document root is not an object"));
            return;
        }

        AmmunitionDefinition definition =
                new AmmunitionDefinitionParser(pack.id(), packRelative, fileIdentity, report).parse(rootObject);
        if (definition == null) {
            rejected.add(new AmmunitionLoadResult.RejectedDefinition(pack.id(), packRelative,
                    "required properties are missing or invalid"));
            return;
        }

        String previousName = claimedNames.putIfAbsent(definition.ammoName(), packRelative);
        if (previousName != null) {
            report.error(pack.id(), packRelative, "$.ammo_name", "Duplicate ammunition name '"
                    + definition.ammoName() + "'; it is already provided by " + previousName + ". The ammunition "
                    + "name is a global compatibility identity because 'ammo_to_use' carries no namespace, so this "
                    + "definition is rejected instead of one being chosen arbitrarily");
            rejected.add(new AmmunitionLoadResult.RejectedDefinition(pack.id(), packRelative,
                    "duplicate ammunition name '" + definition.ammoName() + "'"));
            return;
        }

        String previousItemId = claimedItemIds.putIfAbsent(definition.ammoId(), packRelative);
        if (previousItemId != null) {
            report.error(pack.id(), packRelative, "$.ammo_id", "Duplicate ammunition registry id '"
                    + definition.ammoId() + "'; it is already used by " + previousItemId + ". This definition is "
                    + "rejected and no second item is registered for that id");
            rejected.add(new AmmunitionLoadResult.RejectedDefinition(pack.id(), packRelative,
                    "duplicate ammunition registry id '" + definition.ammoId() + "'"));
            return;
        }

        validateDisplay(pack, definition, packRelative, report);
        accepted.add(new AmmunitionLoadResult.LoadedAmmunition(pack, definition));
        report.info(pack.id(), packRelative, "$", "Parsed ammunition definition '" + definition.ammoName()
                + "' registered as item '" + definition.ammoId() + "'");
    }

    /**
     * Reports whether the optional display reference resolves.
     *
     * <p>A missing display definition is a warning, never a rejection: it is artwork, the item stays
     * registrable, and the client falls back to a documented item model.</p>
     */
    private static void validateDisplay(ContentPack pack, AmmunitionDefinition definition, String source,
                                        ValidationReport report) {
        if (!definition.hasDisplay()) {
            return;
        }
        boolean found = ContentPackResources.find(pack, definition.display(), "display",
                ContentPackResources.JSON_SUFFIXES).isPresent();
        if (!found) {
            report.warning(pack.id(), source, "$.display", "The display definition '" + definition.display()
                    + "' was not found; tried " + ContentPackResources.describeCandidates(definition.display(),
                    "display", ContentPackResources.JSON_SUFFIXES) + ". The ammunition item stays registered and "
                    + "uses the documented fallback item model");
        }
    }
}
