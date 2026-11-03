package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.PackDiagnostic;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * {@code glowing_weapon_parts}, read through the definition parser.
 *
 * <p>The reader is exercised directly, like the recoil reader, because a whole-document parse also builds the firing
 * modes and those need the game's own classes. Everything this feature added to the glowing-part schema is plain JSON:
 * the property name, the absence of any other property, duplicate handling and the tolerance towards broken entries.</p>
 */
class WeaponDefinitionParserGlowingPartsTest {

    /** The documented shape: three independent parent bones, one property each. */
    private static final String THREE_SECTIONS = """
            {
              "glowing_weapon_parts": [
                { "part_name": "glowing_part" },
                { "part_name": "glowing_part_2" },
                { "part_name": "glowing_part_3" }
              ]
            }
            """;

    @Test
    void everyPartNameIsReadInAuthoredOrder() {
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.GlowingPart> parts = read(THREE_SECTIONS, report);

        assertEquals(3, parts.size(), "three sections are three independent glowing parents");
        assertEquals("glowing_part", parts.get(0).name());
        assertEquals("glowing_part_2", parts.get(1).name());
        assertEquals("glowing_part_3", parts.get(2).name());
        assertTrue(report.diagnostics().isEmpty(),
                () -> "a valid configuration must not produce a finding: " + report.diagnostics());
    }

    @Test
    void anAbsentPropertyLeavesTheFeatureDisabled() {
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.GlowingPart> parts = read("{}", report);

        assertTrue(parts.isEmpty());
        assertTrue(report.diagnostics().isEmpty(), "an absent optional property is not a finding");
    }

    @Test
    void anEmptyArrayMakesNothingGlow() {
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.GlowingPart> parts = read("{ \"glowing_weapon_parts\": [] }", report);

        assertTrue(parts.isEmpty());
        assertTrue(report.diagnostics().isEmpty(), "an empty list is a valid configuration");
    }

    @Test
    void theLegacyNameSpellingIsStillAccepted() {
        List<WeaponDefinition.GlowingPart> parts = read("""
                { "glowing_weapon_parts": [ { "name": "older_spelling" } ] }
                """, new ValidationReport());

        assertEquals(1, parts.size());
        assertEquals("older_spelling", parts.get(0).name());
    }

    @Test
    void partNameWinsWhenOneEntryDeclaresBothSpellings() {
        List<WeaponDefinition.GlowingPart> parts = read("""
                { "glowing_weapon_parts": [ { "part_name": "part_name_wins", "name": "ignored" } ] }
                """, new ValidationReport());

        assertEquals(1, parts.size(), "one entry describes one bone, never two");
        assertEquals("part_name_wins", parts.get(0).name());
    }

    @Test
    void surroundingWhitespaceIsTrimmedSoTheBoneNameMatchesTheModel() {
        List<WeaponDefinition.GlowingPart> parts = read("""
                { "glowing_weapon_parts": [ { "part_name": "  glowing_part  " } ] }
                """, new ValidationReport());

        assertEquals("glowing_part", parts.get(0).name());
    }

    @Test
    void duplicatesCollapseIntoOneSection() {
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.GlowingPart> parts = read("""
                {
                  "glowing_weapon_parts": [
                    { "part_name": "glowing_part" },
                    { "part_name": "glowing_part" },
                    { "part_name": "glowing_part_2" }
                  ]
                }
                """, report);

        assertEquals(2, parts.size(), "the same bone must never be processed twice");
        assertEquals("glowing_part", parts.get(0).name());
        assertEquals("glowing_part_2", parts.get(1).name());
        assertEquals(0, report.count(PackDiagnostic.Severity.WARNING) + report.count(PackDiagnostic.Severity.ERROR),
                "a duplicate is informational, not a warning or an error");
        assertEquals(1, report.count(PackDiagnostic.Severity.INFO),
                () -> "the duplicate must be reported: " + report.diagnostics());
    }

    @Test
    void blankAndMalformedEntriesAreSkippedAndReported() {
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.GlowingPart> parts = read("""
                {
                  "glowing_weapon_parts": [
                    { "part_name": "  " },
                    { "part_name": 42 },
                    "glowing_part",
                    { "part_name": "kept" }
                  ]
                }
                """, report);

        assertEquals(1, parts.size(), "only the one usable entry survives");
        assertEquals("kept", parts.get(0).name());
        // Three broken entries, four findings: a wrongly typed 'part_name' is reported by the typed reader and then by the
        // entry reader, which is the same double report every optional sub-property in this parser produces.
        assertEquals(4, report.count(PackDiagnostic.Severity.WARNING),
                () -> "each broken entry is reported: " + report.diagnostics());
        assertEquals(0, report.count(PackDiagnostic.Severity.ERROR), "a broken entry never rejects the weapon");
    }

    @Test
    void theSectionCountIsNotCapped() {
        StringBuilder entries = new StringBuilder();
        for (int index = 0; index < 25; index++) {
            if (index > 0) {
                entries.append(',');
            }
            entries.append("{ \"part_name\": \"glowing_part_").append(index).append("\" }");
        }

        List<WeaponDefinition.GlowingPart> parts =
                read("{ \"glowing_weapon_parts\": [" + entries + "] }", new ValidationReport());

        assertEquals(25, parts.size(), "creators may declare as many independent sections as they need");
        assertFalse(parts.isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private static List<WeaponDefinition.GlowingPart> read(String json, ValidationReport report) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new WeaponDefinitionParser("arg_pack", "weapons/glowing_weapon.json",
                new ResourceId("arg_pack", "weapons/glowing_weapon"), report).readGlowingParts(root);
    }
}
