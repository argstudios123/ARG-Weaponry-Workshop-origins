package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arg_studios.arg_weaponry.core.PackDiagnostic;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The discovery-to-rejection half of the weapon loader, driven through {@link WeaponDefinitionLoader} itself.
 *
 * <p>A definition with invalid JSON is rejected before the parser runs, which is exactly the state the SPAS-12 was in
 * when it "was not registered": the file was found, in its own category folder, and thrown away at the JSON layer. This
 * test therefore proves three things at once - the recursive scan reaches {@code shotguns/}, the file is reported with
 * its position, and the rejection is one entry in the result rather than a silent absence.</p>
 */
class WeaponDefinitionLoaderTest {

    @Test
    void aBrokenDefinitionInItsOwnCategoryIsDiscoveredAndRejectedWithItsLocation(@TempDir Path packRoot)
            throws Exception {
        Path weapons = Files.createDirectories(
                packRoot.resolve("assets/test_pack/weapons_registry/weapons/shotguns"));
        Files.writeString(weapons.resolve("spas12.json"), """
                {
                  "weapon_name": "spas12",
                  "ammo_capacity": 8,
                  "allow_firing_when_sprinting": false,selec
                }
                """);

        ValidationReport report = new ValidationReport();
        WeaponLoadResult result = WeaponDefinitionLoader.load(List.of(pack(packRoot)), report);

        assertEquals(1, result.rejected().size(), "the broken definition must be reported, not silently dropped");
        assertTrue(result.weapons().isEmpty());
        WeaponLoadResult.RejectedDefinition rejected = result.rejected().getFirst();
        assertEquals("assets/test_pack/weapons_registry/weapons/shotguns/spas12.json", rejected.source());
        assertTrue(rejected.reason().startsWith("invalid JSON"),
                "the reason must name the problem: " + rejected.reason());
        assertTrue(rejected.reason().contains("line 4"),
                "the reason must carry the position: " + rejected.reason());
        assertTrue(report.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.severity() == PackDiagnostic.Severity.ERROR),
                "a rejected definition is an error, never a warning");
    }

    @Test
    void aPackWithoutAWeaponsDirectoryProducesNoDefinitionsAndNoRejections(@TempDir Path packRoot) throws Exception {
        Files.createDirectories(packRoot.resolve("assets/test_pack"));

        WeaponLoadResult result = WeaponDefinitionLoader.load(List.of(pack(packRoot)), new ValidationReport());

        assertTrue(result.weapons().isEmpty());
        assertTrue(result.rejected().isEmpty());
    }

    private static ContentPack pack(Path root) {
        return new ContentPack("test_pack", ContentPackSource.BUNDLED, null, root, List.of("test_pack"));
    }
}
