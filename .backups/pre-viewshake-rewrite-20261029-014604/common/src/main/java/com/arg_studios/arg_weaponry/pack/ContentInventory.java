package com.arg_studios.arg_weaponry.pack;

import com.arg_studios.arg_weaponry.core.ValidationReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Reports empty JSON files so creators can tell an intentional placeholder from a broken file.
 *
 * <p>Empty files are never deleted or filled in. Files below
 * {@code weapons_registry/weapons/} are excluded, because an empty weapon definition is a real error
 * and is reported by {@link com.arg_studios.arg_weaponry.weapon.WeaponDefinitionLoader} instead.</p>
 */
public final class ContentInventory {

    private ContentInventory() {
    }

    /**
     * Emits one informational diagnostic per empty JSON placeholder in the pack.
     *
     * @param pack   the pack to inspect
     * @param report the report to collect diagnostics in
     */
    public static void reportEmptyPlaceholders(ContentPack pack, ValidationReport report) {
        try (Stream<Path> walk = Files.walk(pack.root())) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                if (!file.getFileName().toString().endsWith(".json") || !isEmptyFile(file)) {
                    continue;
                }
                String relativePath = pack.root().relativize(file).toString().replace('\\', '/');
                if (relativePath.contains("/weapons_registry/weapons/")) {
                    continue;
                }
                report.info(pack.id(), relativePath, "Empty JSON placeholder (" + category(relativePath)
                        + "); it is preserved as-is and is not filled in by the framework");
            }
        } catch (IOException e) {
            report.warning(pack.id(), "content pack", "Failed to list empty JSON files: " + e.getMessage());
        }
    }

    private static boolean isEmptyFile(Path file) {
        try {
            return Files.size(file) == 0L;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Derives a human readable category from a path relative to the pack root, so the diagnostic
     * says what the empty file is meant to be.
     */
    private static String category(String relativePath) {
        String[] parts = relativePath.split("/");
        if (parts.length >= 3 && "assets".equals(parts[0])) {
            String category = parts[2];
            return switch (category) {
                case "weapons_registry" -> parts.length >= 4
                        ? "registry entry of category '" + parts[3] + "'"
                        : "weapon registry directory";
                case "hud_ui" -> "HUD configuration, not implemented yet";
                case "effects" -> "effect configuration, not implemented yet";
                case "lang" -> "language file, not loaded by the framework yet";
                case "display" -> "display configuration";
                case "models" -> "model";
                case "animations" -> "animation resource";
                default -> "category '" + category + "'";
            };
        }
        if (parts.length >= 2 && "data".equals(parts[0])) {
            return "data-pack file, not processed by the framework yet";
        }
        return "unclassified file";
    }
}
