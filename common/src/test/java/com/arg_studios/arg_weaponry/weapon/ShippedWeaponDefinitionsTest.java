package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arg_studios.arg_weaponry.core.PackDiagnostic;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.weapon.reload.ReloadStage;
import com.arg_studios.arg_weaponry.weapon.reload.ReloadTimeline;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Every weapon definition the bundled content pack ships, checked against the discovery rules and the JSON layer.
 *
 * <p>This is the regression guard for "the new weapon is not registered": it proves that the file is discovered in its
 * own category folder, that it is valid JSON for the reader the loader uses, that every top-level property is one the
 * parser knows, and (for the SPAS-12) that its staged reload sequence maps onto the stage vocabulary.</p>
 *
 * <p>The full document is not parsed here: {@code WeaponDefinitionParser.parse} needs the game's {@code FireMode}, which
 * ModDevGradle keeps off the test classpath by design. The schema readers it uses are covered directly in
 * {@code WeaponDefinitionParserReloadSequenceTest}, and the parsed definition is verified in game.</p>
 */
class ShippedWeaponDefinitionsTest {

    private static final String PACK_DIRECTORY = "assets/arg_weaponry/weapon_packs/arg_default_pack";
    private static final String PACK_NAMESPACE = "arg_pack";
    private static final String WEAPONS_DIRECTORY = "assets/arg_pack/weapons_registry/weapons";

    @Test
    void theShotgunDefinitionIsDiscoveredNextToTheDmr() throws Exception {
        Path weapons = packRoot().resolve(WEAPONS_DIRECTORY);

        List<String> identities = WeaponDefinitionDiscovery.discover(PACK_NAMESPACE, weapons).stream()
                .map(definition -> definition.identity().path())
                .toList();

        assertTrue(identities.contains("weapons/dmrs/m100_dmr"), "the DMR keeps loading: " + identities);
        assertTrue(identities.contains("weapons/shotguns/spas12"),
                "a definition in a category folder must be discovered like any other: " + identities);
    }

    @Test
    void everyShippedDefinitionIsValidJsonWithPropertiesTheParserKnows() throws Exception {
        Path weapons = packRoot().resolve(WEAPONS_DIRECTORY);
        List<Path> files = WeaponDefinitionDiscovery.listDefinitionFiles(weapons);
        assertFalse(files.isEmpty(), "the bundled pack must ship at least one weapon");

        Set<String> known = WeaponDefinitionParser.knownProperties();
        for (Path file : files) {
            JsonObject document = document(file);
            for (String key : document.keySet()) {
                assertTrue(known.contains(key), "the shipped definition " + file.getFileName()
                        + " declares '" + key + "', which the parser does not know");
            }
            assertTrue(document.has("weapon_id"), file + " must declare weapon_id, or no item is registered");
            assertTrue(document.has("ammo_capacity"), file + " must declare ammo_capacity");
            assertTrue(document.has("display"), file + " must declare display");
        }
    }

    @Test
    void theShippedShotgunDeclaresAShellAmountAndAStagedReload() throws Exception {
        JsonObject document = document(packRoot().resolve(WEAPONS_DIRECTORY).resolve("shotguns/spas12.json"));

        assertEquals(8, document.get("ammo_capacity").getAsInt());
        assertEquals(1, document.get("shell_amount_reload").getAsInt(),
                "the SPAS-12 loads one shell per insertion cycle");

        ValidationReport report = new ValidationReport();
        WeaponDefinitionParser parser = new WeaponDefinitionParser("arg_default_pack",
                "spas12.json", new ResourceId(PACK_NAMESPACE, "weapons/shotguns/spas12"), report);
        List<WeaponDefinition.ReloadStep> steps = parser.readReloadSteps(document);

        List<ReloadStage> stages = new ArrayList<>();
        for (WeaponDefinition.ReloadStep step : steps) {
            ReloadStage stage = ReloadStage.lookup(step.phase());
            assertNotNull(stage, "the shipped stage '" + step.phase() + "' must be part of the vocabulary");
            stages.add(stage);
        }
        assertEquals(List.of(ReloadStage.PREPARE, ReloadStage.PREPARE, ReloadStage.INITIAL_LOAD,
                ReloadStage.REPEAT_LOAD, ReloadStage.FINISH), stages);
        assertTrue(steps.get(3).repeated(), "the repeating clip is the one that asks for repetition");
        assertTrue(report.diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.severity() != PackDiagnostic.Severity.INFO),
                "the shipped sequence must load without warnings: " + report.diagnostics());

        // The shipped conditions planned exactly as the server plans them: an empty magazine opens with the empty clip,
        // a partially loaded one with the standard clip, and the shells go in one cycle at a time until the magazine is
        // full - the behaviour the staged schema exists for.
        ReloadTimeline fromEmpty = ReloadTimeline.plan(steps, 0, 8, 28, 1);
        assertEquals("anim.model.reload_start_empty", fromEmpty.stages().getFirst().animation(),
                "ammo_count == -1 selects the empty-magazine opening clip");
        assertEquals("anim.model.load_first", fromEmpty.stages().get(1).animation(),
                "an empty magazine loads its first shell with the first-shell clip (iteration counter 0)");
        assertEquals(8, fromEmpty.plannedAmount(), "a capacity of eight takes eight shells");
        assertEquals(ReloadStage.FINISH, fromEmpty.stages().getLast().kind(), "the closing clip always comes last");

        ReloadTimeline fromPartial = ReloadTimeline.plan(steps, 3, 8, 28, 1);
        assertEquals("anim.model.reload_start", fromPartial.stages().getFirst().animation(),
                "ammo_count >= 0 selects the standard opening clip");
        assertEquals("anim.model.reload_loop", fromPartial.stages().get(1).animation(),
                "a magazine that already holds rounds starts its iterations at counter 3, so it repeats from the start");
        assertEquals(5, fromPartial.plannedAmount(), "five rounds of room, five shells loaded");
    }


    @Test
    void theShippedMuzzleFlashDeclaresItsSpriteGrid() throws Exception {
        ValidationReport report = new ValidationReport();
        WeaponDefinition.MuzzleFlash flash = new WeaponDefinitionParser("arg_default_pack",
                "weapons/shotguns/spas12.json", new ResourceId("arg_pack", "spas12"), report)
                .readMuzzleFlash(document(packRoot().resolve(WEAPONS_DIRECTORY).resolve("shotguns/spas12.json")));

        assertNotNull(flash, "the shipped shotgun declares a muzzle flash");
        assertNotNull(flash.sprites(), "a flash sheet must declare its grid: 2048x256 is eight cells in one row");
        assertEquals(1, flash.sprites().rows());
        assertEquals(8, flash.sprites().columns());
        assertEquals(200, flash.sprites().fps(),
                "the frame rate only controls how fast the eight sprites advance");
        assertEquals(WeaponDefinition.MuzzleFlash.SpriteType.RANDOM, flash.sprites().type(),
                "the shipped shotgun uses the random animation: the sheet is a bank of distinct flashes");
        assertEquals(80, flash.durationMs(),
                "the shipped shotgun declares its own visibility lifetime, independently of the frame rate");
        assertEquals(0.080D, flash.durationSeconds(), 1.0E-9D);
        assertTrue(report.diagnostics().isEmpty(),
                () -> "duration_ms is part of the schema, so the shipped file must parse cleanly: "
                        + report.diagnostics());
    }

    private static JsonObject document(Path file) throws Exception {
        JsonElement root = JsoncReader.parse(Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(root.isJsonObject(), file + " must contain a JSON object");
        return root.getAsJsonObject();
    }

    private static Path packRoot() throws Exception {
        URL url = ShippedWeaponDefinitionsTest.class.getClassLoader().getResource(PACK_DIRECTORY);
        if (url != null && "file".equals(url.getProtocol())) {
            return Path.of(url.toURI());
        }
        // Fallback for a test runner whose working directory is the module directory.
        Path fallback = Path.of("src/main/resources").resolve(PACK_DIRECTORY);
        assertTrue(Files.isDirectory(fallback), "the bundled content pack must be reachable from the test");
        return fallback;
    }
}
