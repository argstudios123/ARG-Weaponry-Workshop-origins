package com.arg_studios.arg_weaponry.pack;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.core.ValidationReport;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discovers content packs and reads their metadata.
 *
 * <p>Packs are looked up in {@code assets/arg_weaponry/weapon_packs/<pack>/} below each content root
 * reported by the loader, which is the same relative path in a development environment (resource
 * directories on the classpath) and in a packaged mod (an archive). If the loader cannot report
 * content roots, a classpath lookup of the same directory is used as a fallback.</p>
 *
 * <p>Discovery is deterministic: pack folders are visited in name order, the first pack claiming an
 * identity or a resource namespace wins, and every conflict is reported instead of silently
 * overwriting another pack's resources.</p>
 */
public final class ContentPackScanner {

    /** Directory below a content root that contains the bundled content packs. */
    public static final String PACKS_DIRECTORY = "assets/" + Constants.MOD_ID + "/weapon_packs";

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final String PACK_ASSETS_DIRECTORY = "assets";

    private ContentPackScanner() {
    }

    /**
     * Scans the install root and the mod's bundled content for content packs.
     *
     * <p>The install root is scanned first, so an installed pack always shadows the bundled copy of the
     * same pack and nothing is ever loaded twice.</p>
     *
     * @param installedRoot the install root, for example {@code <gameDir>/arg_weaponry}
     * @param modRoots      the content roots reported by the loader; may be empty
     * @param report        the report to collect diagnostics in
     * @return the discovered packs, in deterministic order; never {@code null}
     */
    public static List<ContentPack> scan(Path installedRoot, List<Path> modRoots, ValidationReport report) {
        List<PackLocation> locations = findPackLocations(installedRoot, modRoots, report);

        List<ContentPack> candidates = new ArrayList<>();
        for (PackLocation location : locations) {
            for (Path packRoot : listPackRoots(location, report)) {
                ContentPack pack = readPack(packRoot, location.source(), report);
                if (pack != null) {
                    candidates.add(pack);
                }
            }
        }

        List<ContentPack> accepted = resolveIdentities(candidates, report);

        Map<String, Path> namespaceOwners = new LinkedHashMap<>();
        for (ContentPack pack : accepted) {
            for (String namespace : pack.resourceNamespaces()) {
                Path owner = namespaceOwners.putIfAbsent(namespace, pack.root());
                if (owner != null && !owner.equals(pack.root())) {
                    report.warning(pack.id(), pack.root().getFileName().toString(),
                            "Resource namespace '" + namespace + "' is also provided by " + owner
                                    + "; resource lookup will need an explicit pack choice.");
                }
            }
        }

        return List.copyOf(accepted);
    }

    /**
     * Resolves duplicate pack identities. A pack whose folder name equals the identity wins over one
     * that only declares it in {@code pack_id.json}, so the obvious pack on disk is never displaced;
     * otherwise the first pack in folder-name order wins. Every displaced pack is reported.
     */
    private static List<ContentPack> resolveIdentities(List<ContentPack> candidates, ValidationReport report) {
        Map<String, List<ContentPack>> byIdentity = new LinkedHashMap<>();
        for (ContentPack candidate : candidates) {
            byIdentity.computeIfAbsent(candidate.id(), identity -> new ArrayList<>()).add(candidate);
        }

        List<ContentPack> accepted = new ArrayList<>();
        for (Map.Entry<String, List<ContentPack>> entry : byIdentity.entrySet()) {
            List<ContentPack> group = entry.getValue();
            ContentPack chosen = group.getFirst();
            if (group.size() > 1) {
                for (ContentPack candidate : group) {
                    if (candidate.root().getFileName().toString().equals(entry.getKey())) {
                        chosen = candidate;
                        break;
                    }
                }
                for (ContentPack candidate : group) {
                    if (candidate == chosen) {
                        continue;
                    }
                    if (chosen.source() == ContentPackSource.INSTALLED
                            && candidate.source() == ContentPackSource.BUNDLED) {
                        report.info(candidate.id(), candidate.root().toString(),
                                "The bundled copy of pack '" + candidate.id() + "' is shadowed by the installed copy at "
                                        + chosen.root() + " and is not loaded");
                    } else {
                        report.error(candidate.id(), candidate.root().getFileName().toString(),
                                "Duplicate content pack id '" + candidate.id() + "'; it is already provided by "
                                        + chosen.root() + ". This pack is ignored.");
                    }
                }
            }
            accepted.add(chosen);
        }
        return accepted;
    }

    // --- discovery helpers ---

    private static List<PackLocation> findPackLocations(Path installedRoot, List<Path> modRoots,
                                                        ValidationReport report) {
        List<PackLocation> locations = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();

        if (installedRoot != null && Files.isDirectory(installedRoot)) {
            Path normalized = installedRoot.toAbsolutePath().normalize();
            if (seen.add(normalized)) {
                locations.add(new PackLocation(normalized, ContentPackSource.INSTALLED));
                Constants.LOG.debug("[ARG] Installed content pack directory: {}", normalized);
            }
        }

        for (Path root : modRoots) {
            if (root == null) {
                continue;
            }
            Path candidate = root.resolve(PACKS_DIRECTORY);
            if (Files.isDirectory(candidate)) {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (seen.add(normalized)) {
                    locations.add(new PackLocation(normalized, ContentPackSource.BUNDLED));
                    Constants.LOG.debug("[ARG] Bundled content pack directory found below {}: {}", root, candidate);
                }
            } else {
                Constants.LOG.debug("[ARG] No bundled content pack directory below {} (expected {})", root, PACKS_DIRECTORY);
            }
        }

        boolean hasBundledLocation = locations.stream()
                .anyMatch(location -> location.source() == ContentPackSource.BUNDLED);
        if (!hasBundledLocation) {
            Constants.LOG.debug("[ARG] No bundled content pack directory was reported; trying a classpath lookup");
            findBundledLocationsOnClasspath(report, locations, seen);
        }

        if (locations.isEmpty()) {
            report.warning(ValidationReport.frameworkScope(), PACKS_DIRECTORY,
                    "No content pack directory was found, so no content packs were loaded");
        }
        return List.copyOf(locations);
    }

    private static void findBundledLocationsOnClasspath(ValidationReport report, List<PackLocation> locations,
                                                        Set<Path> seen) {
        ClassLoader classLoader = ContentPackScanner.class.getClassLoader();
        try {
            Enumeration<URL> resources = classLoader.getResources(PACKS_DIRECTORY);
            while (resources.hasMoreElements()) {
                URL location = resources.nextElement();
                if (!"file".equals(location.getProtocol())) {
                    Constants.LOG.debug("[ARG] Ignoring non-file classpath content pack location {}", location);
                    continue;
                }
                Path path = Paths.get(location.toURI()).toAbsolutePath().normalize();
                if (Files.isDirectory(path) && seen.add(path)) {
                    locations.add(new PackLocation(path, ContentPackSource.BUNDLED));
                    Constants.LOG.debug("[ARG] Bundled content pack directory found on the classpath: {}", path);
                }
            }
        } catch (IOException | URISyntaxException e) {
            report.warning(ValidationReport.frameworkScope(), PACKS_DIRECTORY,
                    "Classpath lookup for content packs failed: " + e);
        }
    }

    private static List<Path> listPackRoots(PackLocation location, ValidationReport report) {
        Path packsDirectory = location.directory();
        List<Path> packRoots = new ArrayList<>();
        try (Stream<Path> children = Files.list(packsDirectory)) {
            List<Path> entries = children
                    .sorted(Comparator.comparing(child -> child.getFileName().toString()))
                    .toList();
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.startsWith(".")) {
                    // Reserved for framework bookkeeping such as .install-state.json, .staging and
                    // .backups; hidden entries are never content packs.
                    Constants.LOG.debug("[ARG] Ignoring hidden entry in the content pack directory: {}", entry);
                } else if (Files.isDirectory(entry)) {
                    packRoots.add(entry);
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    report.warning(ValidationReport.frameworkScope(), entry.toString(),
                            "ZIP content packs are not supported yet; extract the folder '" + name
                                    + "' to load this pack");
                }
            }
        } catch (IOException e) {
            report.error(ValidationReport.frameworkScope(), packsDirectory.toString(),
                    "Failed to list content packs: " + e.getMessage());
            return List.of();
        }

        if (packRoots.isEmpty()) {
            report.warning(ValidationReport.frameworkScope(), packsDirectory.toString(),
                    "The content pack directory does not contain any pack folders");
        }
        return packRoots;
    }

    // --- pack reading helpers ---

    private static ContentPack readPack(Path packRoot, ContentPackSource source, ValidationReport report) {
        String folderName = packRoot.getFileName().toString();
        PackMetadata metadata = PackMetadata.read(packRoot, folderName, report);

        String packId = folderName;
        if (!ID_PATTERN.matcher(folderName).matches()) {
            report.warning(folderName, "content pack folder", "Folder name '" + folderName
                    + "' is not a valid identifier; expected [a-z0-9_.-] with at most 64 characters");
        }

        String declared = metadata.declaredPackId();
        if (declared != null) {
            if (ID_PATTERN.matcher(declared).matches()) {
                packId = declared;
            } else {
                report.error(folderName, "pack_id.json", "Invalid pack_id '" + declared
                        + "'; expected [a-z0-9_.-] with at most 64 characters. Falling back to the folder name '"
                        + folderName + "'");
            }
        }

        List<String> namespaces = readResourceNamespaces(packRoot, packId, report);
        return new ContentPack(packId, source, metadata, packRoot, namespaces);
    }

    private static List<String> readResourceNamespaces(Path packRoot, String packId, ValidationReport report) {
        Path assetsDirectory = packRoot.resolve(PACK_ASSETS_DIRECTORY);
        if (!Files.isDirectory(assetsDirectory)) {
            report.warning(packId, PACK_ASSETS_DIRECTORY,
                    "The pack does not contain an assets directory, so no resource namespaces were detected");
            return List.of();
        }

        List<String> namespaces = new ArrayList<>();
        try (Stream<Path> children = Files.list(assetsDirectory)) {
            List<Path> directories = children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(child -> child.getFileName().toString()))
                    .toList();
            for (Path directory : directories) {
                String name = directory.getFileName().toString();
                if (!ID_PATTERN.matcher(name).matches()) {
                    report.warning(packId, "assets/" + name, "Invalid resource namespace '" + name
                            + "'; expected [a-z0-9_.-] with at most 64 characters. It is ignored.");
                    continue;
                }
                namespaces.add(name);
            }
        } catch (IOException e) {
            report.error(packId, PACK_ASSETS_DIRECTORY, "Failed to list resource namespaces: " + e.getMessage());
            return List.of();
        }

        if (namespaces.isEmpty()) {
            report.warning(packId, PACK_ASSETS_DIRECTORY, "No resource namespaces were found below the assets directory");
        }
        return List.copyOf(namespaces);
    }

}