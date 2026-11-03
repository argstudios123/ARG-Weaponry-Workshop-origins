package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The weapon discovery rules, verified without a running game.
 *
 * <p>These tests exist because "the new weapon is not registered" is very often misdiagnosed as a folder problem. The
 * scan is recursive and category-agnostic, so a definition in {@code shotguns/} is discovered exactly like one in
 * {@code dmrs/} - and this class pins that down instead of leaving it to documentation.</p>
 */
class WeaponDefinitionDiscoveryTest {

    @Test
    void discoversDefinitionsInEveryCategoryAtEveryDepth(@TempDir Path root) throws Exception {
        Path weapons = Files.createDirectories(root.resolve("shotguns"));
        Path nested = Files.createDirectories(root.resolve("rifles/sniper/deep"));
        Files.writeString(weapons.resolve("spas12.json"), "{}");
        Files.writeString(nested.resolve("m40.json"), "{}");

        List<Path> files = WeaponDefinitionDiscovery.listDefinitionFiles(root);

        assertEquals(2, files.size(), "both categories and every depth must be scanned");
        assertTrue(files.stream().anyMatch(file -> file.getFileName().toString().equals("spas12.json")));
        assertTrue(files.stream().anyMatch(file -> file.getFileName().toString().equals("m40.json")));
    }

    @Test
    void ignoresEverythingThatIsNotAJsonFile(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("notes.txt"), "not a definition");
        Files.writeString(root.resolve("spas12.json.bak"), "{}");
        Files.writeString(root.resolve("spas12.json"), "{}");

        List<Path> files = WeaponDefinitionDiscovery.listDefinitionFiles(root);

        assertEquals(1, files.size());
        assertEquals("spas12.json", files.getFirst().getFileName().toString());
    }

    @Test
    void returnsAnEmptyListWhenTheWeaponsDirectoryDoesNotExist(@TempDir Path root) throws Exception {
        assertTrue(WeaponDefinitionDiscovery.listDefinitionFiles(root.resolve("missing")).isEmpty());
        assertTrue(WeaponDefinitionDiscovery.listDefinitionFiles(null).isEmpty());
    }

    @Test
    void ordersFilesDeterministically(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("shotguns"));
        Files.createDirectories(root.resolve("dmrs"));
        Files.writeString(root.resolve("shotguns/spas12.json"), "{}");
        Files.writeString(root.resolve("dmrs/m100_dmr.json"), "{}");
        Files.writeString(root.resolve("aaa.json"), "{}");

        List<String> first = WeaponDefinitionDiscovery.listDefinitionFiles(root).stream()
                .map(file -> root.relativize(file).toString()).toList();
        List<String> second = WeaponDefinitionDiscovery.listDefinitionFiles(root).stream()
                .map(file -> root.relativize(file).toString()).toList();

        assertEquals(first, second, "the same pack must always produce the same order");
        assertEquals(List.of("aaa.json", "dmrs/m100_dmr.json", "shotguns/spas12.json"), first);
    }

    @Test
    void theCategoryBecomesPartOfTheRegistryIdentity(@TempDir Path root) {
        Path file = root.resolve("shotguns/spas12.json");

        ResourceId identity = WeaponDefinitionDiscovery.registryIdentity("arg_pack", root, file);

        assertEquals("arg_pack", identity.namespace());
        assertEquals("weapons/shotguns/spas12", identity.path());
        assertEquals("spas12", identity.fileName());
    }

    @Test
    void aFilePathThatCannotFormAnIdentityIsRejected(@TempDir Path root) {
        Path file = root.resolve("shotguns/SPAS 12.json");

        assertThrows(IllegalArgumentException.class,
                () -> WeaponDefinitionDiscovery.registryIdentity("arg_pack", root, file));
    }

    @Test
    void discoverCombinesListingAndIdentity(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("shotguns"));
        Files.writeString(root.resolve("shotguns/spas12.json"), "{}");

        List<WeaponDefinitionDiscovery.Definition> definitions =
                WeaponDefinitionDiscovery.discover("arg_pack", root);

        assertEquals(1, definitions.size());
        assertEquals("arg_pack:weapons/shotguns/spas12", definitions.getFirst().identity().asString());
    }
}
