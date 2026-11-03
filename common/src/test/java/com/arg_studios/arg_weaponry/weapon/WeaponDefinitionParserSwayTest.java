package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponSwayStyle;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The optional {@code weapons_sway} block.
 *
 * <p>The block is optional in every direction: absent, partial and complete must all parse, an unknown property is reported
 * without failing the weapon, and a wrongly typed value falls back to the documented default instead of failing the pack.</p>
 */
class WeaponDefinitionParserSwayTest {

    private static final String FULL = """
            {
              "weapons_sway": {
                "enabled": false,
                "intensity": 2.5,
                "smoothing": 8.0,
                "return_speed": 12.0,
                "aim_multiplier": 0.15
              }
            }
            """;

    private static final String PARTIAL = """
            {
              "weapons_sway": {
                "intensity": 0.5
              }
            }
            """;

    @Test
    void aCompleteBlockIsReadAsWritten() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.Sway sway = read(FULL, report);

        assertTrue(report.diagnostics().isEmpty(), "a valid sway block must not produce a finding");
        assertFalse(sway.enabled());
        assertEquals(2.5, sway.intensity());
        assertEquals(8.0, sway.smoothing());
        assertEquals(12.0, sway.returnSpeed());
        assertEquals(0.15, sway.aimMultiplier());
    }

    @Test
    void anAbsentBlockUsesTheDocumentedDefaults() {
        WeaponDefinition.Sway sway = read("{}", new ValidationReport());

        assertEquals(WeaponDefinition.Sway.DEFAULT, sway, "a pack without the block keeps the natural default sway");
        assertTrue(sway.enabled(), "the default is on, which is what 'sensible defaults' means for this feature");
    }

    @Test
    void aPartialBlockFillsTheMissingPropertiesFromTheDefaults() {
        WeaponDefinition.Sway sway = read(PARTIAL, new ValidationReport());

        assertEquals(0.5, sway.intensity(), "the declared property is used");
        assertEquals(WeaponDefinition.Sway.DEFAULT.smoothing(), sway.smoothing());
        assertEquals(WeaponDefinition.Sway.DEFAULT.returnSpeed(), sway.returnSpeed());
        assertEquals(WeaponDefinition.Sway.DEFAULT.aimMultiplier(), sway.aimMultiplier());
        assertTrue(sway.enabled());
    }

    @Test
    void aWronglyTypedPropertyFallsBackToItsDefaultAndIsReported() {
        ValidationReport report = new ValidationReport();
        WeaponDefinition.Sway sway = read("""
                {"weapons_sway": {"enabled": "yes", "intensity": "loud"}}
                """, report);

        assertTrue(sway.enabled(), "a non-boolean 'enabled' must not silently switch the effect off");
        assertEquals(WeaponDefinition.Sway.DEFAULT.intensity(), sway.intensity());
        assertFalse(report.diagnostics().isEmpty(), "the creator is told about the mistake");
    }

    @Test
    void outOfRangeValuesAreClampedInsteadOfRejected() {
        WeaponDefinition.Sway sway = read("""
                {"weapons_sway": {"intensity": -3.0, "smoothing": 0.0, "sway_sensitivity": -2.0, "return_speed": 1000.0, "aim_multiplier": 4.0}}
                """, new ValidationReport());

        assertEquals(0.0, sway.intensity(), "a negative strength means no visible sway, never a negative rotation");
        assertEquals(WeaponDefinition.Sway.MIN_RATE, sway.smoothing(), "a zero rate would freeze the spring");
        assertEquals(0.0, sway.sensitivity(), "a negative gain means the mouse cannot move the weapon");
        assertEquals(WeaponDefinition.Sway.MAX_RATE, sway.returnSpeed());
        assertEquals(4.0, sway.aimMultiplier(),
                "an aim multiplier above one is a heavier aim, not an error; it is kept up to its own cap");
    }

    @Test
    void theStyleAndSensitivityAreReadFromTheDocumentedShape() {
        WeaponDefinition.Sway sway = read("""
                {
                  "weapons_sway": {
                    "enabled": true,
                    "weapon_sway_type": "type_1",
                    "intensity": 0.2,
                    "smoothing": 3.0,
                    "sway_sensitivity": 3.0,
                    "return_speed": 6.0,
                    "aim_multiplier": 2.9
                  }
                }
                """, new ValidationReport());

        assertEquals(WeaponSwayStyle.TYPE_1, sway.style());
        assertEquals(0.2, sway.intensity());
        assertEquals(3.0, sway.smoothing());
        assertEquals(3.0, sway.sensitivity());
        assertEquals(6.0, sway.returnSpeed());
        assertEquals(2.9, sway.aimMultiplier());
    }

    @Test
    void anUnknownStyleFallsBackToVanillaAndIsReported() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.Sway sway = read("""
                {"weapons_sway": {"weapon_sway_type": "wobbly"}}
                """, report);

        assertEquals(WeaponSwayStyle.VANILLA, sway.style(), "a typo cannot disable sway");
        assertFalse(report.diagnostics().isEmpty(), "the creator is told about the typo");
    }

    @Test
    void aMissingStyleUsesTheDocumentedDefault() {
        WeaponDefinition.Sway sway = read("""
                {"weapons_sway": {"intensity": 0.5}}
                """, new ValidationReport());

        assertEquals(WeaponSwayStyle.VANILLA, sway.style());
        assertEquals(WeaponDefinition.Sway.DEFAULT.sensitivity(), sway.sensitivity());
    }

    @Test
    void anUnknownPropertyIsReportedButTheBlockStillParses() {
        ValidationReport report = new ValidationReport();
        WeaponDefinition.Sway sway = read("""
                {"weapons_sway": {"intensity": 1.5, "wobble": true}}
                """, report);

        assertEquals(1.5, sway.intensity());
        assertFalse(report.diagnostics().isEmpty(), "an unknown key is reported so a typo is visible");
    }

    private static WeaponDefinition.Sway read(String json, ValidationReport report) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new WeaponDefinitionParser("arg_pack", "weapons/sway_weapon.json",
                new ResourceId("arg_pack", "weapons/sway_weapon"), report).readSway(root);
    }
}
