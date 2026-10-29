package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.PackDiagnostic;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.weapon.equip.EquipAnimationResolver;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The two accepted spellings of the first-draw clip, read through the real parser method.
 *
 * <p>Both normalize into one {@link WeaponDefinition.DrawSelection} model, the boolean flag stops being an unknown
 * property, a broken entry is reported and skipped instead of failing the pack, and the end-to-end rule holds on real
 * JSON: the ordinary draw selection never picks the first-draw entry, even though it declares no {@code when}.</p>
 */
class WeaponDefinitionParserDrawSelectionTest {

    /** The shape the shipped weapon uses now. */
    private static final String FLAG_FORM = """
            {
              "draw_anims": [
                { "name": "anim.model.draw_first", "first_draw": true, "duration": 1.2 },
                { "name": "anim.model.draw", "duration": 0.87, "when": "ammoCount > 0" },
                { "name": "anim.model.draw_empty", "duration": 0.89, "when": "ammoCount == 0" }
              ]
            }
            """;

    /** The older key-only shape, which must keep working. */
    private static final String KEY_ONLY_FORM = """
            {
              "draw_anims": [
                { "draw_first": "anim.model.draw_first", "duration": 1.2 },
                { "name": "anim.model.draw", "duration": 0.87, "when": "ammoCount > 0" },
                { "name": "anim.model.draw_empty", "duration": 0.89, "when": "ammoCount == 0" }
              ]
            }
            """;

    @Test
    void theFlagFormIsReadAsAFirstDrawEntryWithoutAnUnknownPropertyFinding() {
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.DrawSelection> entries = read(FLAG_FORM, report);

        assertEquals(3, entries.size());
        WeaponDefinition.DrawSelection first = entries.get(0);
        assertEquals("anim.model.draw_first", first.name());
        assertEquals(1.2, first.duration());
        assertTrue(first.firstDraw(), "first_draw: true marks the first-draw clip");
        assertFalse(first.declaredWithDrawFirstKey());
        assertTrue(report.diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.message().contains("Unknown property")),
                "first_draw is a known key now, so a valid pack produces no finding at all");
        assertEquals(0, report.count(PackDiagnostic.Severity.WARNING));
        assertEquals(0, report.count(PackDiagnostic.Severity.ERROR));
    }

    @Test
    void bothSpellingsNormalizeIntoTheSameModel() {
        WeaponDefinition.DrawSelection fromFlag = read(FLAG_FORM, new ValidationReport()).get(0);
        WeaponDefinition.DrawSelection fromKey = read(KEY_ONLY_FORM, new ValidationReport()).get(0);

        assertEquals(fromFlag.name(), fromKey.name());
        assertEquals(fromFlag.duration(), fromKey.duration());
        assertEquals(fromFlag.when(), fromKey.when());
        assertEquals(fromFlag.firstDraw(), fromKey.firstDraw(), "the normalized flag is identical");
        assertTrue(fromKey.declaredWithDrawFirstKey(),
                "only the diagnostic pointer remembers which spelling the creator wrote");
        assertFalse(fromFlag.declaredWithDrawFirstKey());
    }

    @Test
    void firstDrawFalseIsAnOrdinaryDrawEntry() {
        String json = """
                {
                  "draw_anims": [
                    { "name": "anim.model.draw_first", "first_draw": false, "duration": 1.2 }
                  ]
                }
                """;

        WeaponDefinition.DrawSelection entry = read(json, new ValidationReport()).get(0);

        assertFalse(entry.firstDraw());
        assertEquals("anim.model.draw_first", entry.name());
    }

    @Test
    void aNonBooleanFirstDrawIsReportedAndTheEntryStaysAnOrdinaryDraw() {
        String json = """
                {
                  "draw_anims": [
                    { "name": "anim.model.draw", "first_draw": "yes", "duration": 0.87 }
                  ]
                }
                """;
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.DrawSelection> entries = read(json, report);

        assertEquals(1, entries.size(), "a bad flag must not drop the entry");
        assertFalse(entries.get(0).firstDraw());
        assertEquals(1, report.count(PackDiagnostic.Severity.WARNING));
        assertEquals(0, report.count(PackDiagnostic.Severity.ERROR));
    }

    @Test
    void theNamePropertyWinsOverTheLegacyKeyInTheSameEntry() {
        String json = """
                {
                  "draw_anims": [
                    { "name": "anim.model.draw", "draw_first": "anim.model.draw_first", "duration": 0.87 }
                  ]
                }
                """;

        WeaponDefinition.DrawSelection entry = read(json, new ValidationReport()).get(0);

        assertEquals("anim.model.draw", entry.name(), "the established precedence is unchanged");
        assertFalse(entry.firstDraw(), "the legacy key did not supply the name, so it marks nothing");
    }

    @Test
    void brokenEntriesAreReportedAndSkippedInsteadOfFailingThePack() {
        String json = """
                {
                  "draw_anims": [
                    { "name": "anim.model.draw_first", "first_draw": true },
                    { "name": "anim.model.draw", "duration": -1.0 },
                    "not an object",
                    { "name": "anim.model.draw_empty", "duration": 0.89, "when": "ammoCount == 0" }
                  ]
                }
                """;
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.DrawSelection> entries = read(json, report);

        assertEquals(1, entries.size(), "only the valid entry survives");
        assertEquals("anim.model.draw_empty", entries.get(0).name());
        assertEquals(3, report.count(PackDiagnostic.Severity.WARNING), errorsOf(report));
        assertEquals(0, report.count(PackDiagnostic.Severity.ERROR), "a draw entry never rejects a weapon");
    }

    @Test
    void theOrdinaryDrawSelectionNeverPicksTheFirstDrawEntryOnRealJson() {
        List<WeaponDefinition.DrawSelection> entries = read(FLAG_FORM, new ValidationReport());

        assertEquals("anim.model.draw", EquipAnimationResolver.selectDraw(entries, 10, false).clip(),
                "the first-draw entry has no condition and is still not the normal draw clip");
        assertEquals("anim.model.draw_empty", EquipAnimationResolver.selectDraw(entries, 0, false).clip());
        assertEquals("anim.model.draw_first", EquipAnimationResolver.selectDraw(entries, 10, true).clip(),
                "while a never-drawn stack does get it");
    }

    // ---------------------------------------------------------------- the shipped pack

    /**
     * The shipped M100 DMR definition, read from the resource that ships in this jar.
     *
     * <p>This is the "existing weapon JSON files continue to work" check: the real file (JSONC, with comments) is parsed by
     * the real reader and the real parser method, and the draw/holster selection it produces is the one the feature drives.
     * It also pins the two facts the client relies on: the empty draw entry exists in the configuration, and the general draw
     * entry follows it as the playback fallback, because the animation document does not contain {@code draw_empty}.</p>
     */
    @Test
    void theShippedWeaponDefinitionStillParsesAndSelectsItsConfiguredClips() throws Exception {
        JsonObject root = shippedWeapon("assets/arg_weaponry/weapon_packs/arg_default_pack/assets/arg_pack/"
                + "weapons_registry/weapons/dmrs/m100_dmr.json");
        ValidationReport report = new ValidationReport();
        WeaponDefinitionParser parser = new WeaponDefinitionParser("arg_pack",
                "weapons/dmrs/m100_dmr.json", new ResourceId("arg_pack", "weapons/dmrs/m100_dmr"), report);

        List<WeaponDefinition.DrawSelection> draws = parser.readDrawSelections(root);

        assertEquals(0, report.count(PackDiagnostic.Severity.ERROR), errorsOf(report));
        assertEquals("anim.model.draw_first", EquipAnimationResolver.selectDraw(draws, 10, true).clip(),
                "the first draw of the shipped weapon is its configured first-draw clip");
        assertEquals("anim.model.draw", EquipAnimationResolver.selectDraw(draws, 10, false).clip(),
                "a loaded magazine draws with the loaded clip");
        assertEquals("anim.model.draw_empty", EquipAnimationResolver.selectDraw(draws, 0, false).clip(),
                "an empty magazine selects the empty clip");
        List<EquipAnimationResolver.Selection> candidates = EquipAnimationResolver.drawCandidates(draws, 0, false);
        assertEquals(2, candidates.size(), "the empty clip and the loaded one, and nothing else");
        assertEquals("anim.model.draw", candidates.get(1).clip(),
                "the loaded clip stays available as the playback fallback, since the animation document has no draw_empty");
    }

    private static JsonObject shippedWeapon(String classpathResource) throws Exception {
        try (java.io.InputStream stream = WeaponDefinitionParserDrawSelectionTest.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {
            if (stream == null) {
                throw new IllegalStateException("The shipped weapon resource " + classpathResource
                        + " is not on the test classpath");
            }
            String text = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return com.arg_studios.arg_weaponry.json.JsoncReader.parse(text).getAsJsonObject();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static List<WeaponDefinition.DrawSelection> read(String json, ValidationReport report) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new WeaponDefinitionParser("arg_pack", "weapons/draw_test.json",
                new ResourceId("arg_pack", "weapons/draw_test"), report).readDrawSelections(root);
    }

    private static String errorsOf(ValidationReport report) {
        StringBuilder message = new StringBuilder();
        for (PackDiagnostic diagnostic : report.diagnostics()) {
            message.append(diagnostic.format()).append('\n');
        }
        return message.toString();
    }
}
