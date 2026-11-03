package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.client.recoil.PlayerRecoilController;
import com.arg_studios.arg_weaponry.client.recoil.RecoilProfile;
import com.arg_studios.arg_weaponry.core.PackDiagnostic;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The four optional tuning properties, read through the existing recoil reader.
 *
 * <p>They resolve exactly like the existing values (nested {@code recoil} object first, legacy top level second), an
 * absent value stays absent in the parsed block so {@link RecoilProfile} can apply the documented default, and a
 * non-numeric value is reported and ignored instead of failing the weapon - the same policy the parser already applies to
 * every other optional number.</p>
 */
class WeaponDefinitionParserRecoilTuningTest {

    /** The milestone's own configuration, exactly as a creator would write it. */
    private static final String TUNED = """
            {
              "recoil": {
                "kickback_recoil_amplitude": 0.06,
                "vertical_recoil": 2.5,
                "horizontal_recoil_left": 0.02,
                "horizontal_recoil_right": 0.02,
                "smoothing": 0.5,
                "recovery_speed": 0.15,
                "aim_multiplier": 0.6,
                "crouch_multiplier": 0.8,
                "kickback_aiming_modifier": 0.06
              }
            }
            """;

    /** The same weapon without any of the four properties, i.e. an older definition. */
    private static final String UNTUNED = """
            {
              "recoil": {
                "kickback_recoil_amplitude": 0.06,
                "vertical_recoil": 2.5,
                "horizontal_recoil_left": 0.02,
                "horizontal_recoil_right": 0.02
              }
            }
            """;

    @Test
    void allFourTuningPropertiesParseAsWritten() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.Recoil recoil = read(TUNED, report);

        assertNotNull(recoil);
        assertTrue(report.diagnostics().isEmpty(), "a valid tuning block must not produce a finding");
        assertEquals(0.06, recoil.kickbackAmplitude(), "the existing properties are untouched");
        assertEquals(2.5, recoil.vertical());
        assertEquals(0.02, recoil.horizontalLeft());
        assertEquals(0.02, recoil.horizontalRight());
        assertEquals(0.5, recoil.smoothing());
        assertEquals(0.15, recoil.recoverySpeed());
        assertEquals(0.6, recoil.aimMultiplier());
        assertEquals(0.8, recoil.crouchMultiplier());
        assertEquals(0.06, recoil.kickbackAimingModifier());
    }

    @Test
    void absentTuningPropertiesStayAbsentInTheParsedBlock() {
        WeaponDefinition.Recoil recoil = read(UNTUNED, new ValidationReport());

        assertNotNull(recoil);
        assertNull(recoil.smoothing(), "an older definition simply does not declare the property");
        assertNull(recoil.recoverySpeed());
        assertNull(recoil.aimMultiplier());
        assertNull(recoil.crouchMultiplier());
        assertNull(recoil.kickbackAimingModifier(), "an older definition does not declare the new modifier either");

        RecoilProfile profile = RecoilProfile.of(recoil, null);
        assertEquals(PlayerRecoilController.DEFAULT_SMOOTHING, profile.smoothing());
        assertEquals(RecoilProfile.DEFAULT_RECOVERY_SPEED, profile.recoverySpeed());
        assertEquals(RecoilProfile.DEFAULT_AIM_MULTIPLIER, profile.aimMultiplier());
        assertEquals(RecoilProfile.DEFAULT_CROUCH_MULTIPLIER, profile.crouchMultiplier());
        assertEquals(RecoilProfile.DEFAULT_KICKBACK_AIMING_MODIFIER, profile.kickbackAimingModifier(),
                "the missing property must preserve the previous kickback exactly");
        assertEquals(PlayerRecoilController.RECOVERY_SECONDS, profile.recoverySeconds(), 1.0E-12,
                "an older definition keeps exactly the recovery duration it had before");
    }

    @Test
    void theTuningPropertiesAlsoResolveFromTheLegacyTopLevel() {
        String json = """
                {
                  "smoothing": 0.25,
                  "recovery_speed": 2.0,
                  "aim_multiplier": 0.5,
                  "crouch_multiplier": 0.9,
                  "recoil": {
                    "vertical_recoil": 2.5
                  }
                }
                """;

        WeaponDefinition.Recoil recoil = read(json, new ValidationReport());

        assertNotNull(recoil);
        assertEquals(0.25, recoil.smoothing(), "the top-level value resolves like every other legacy property");
        assertEquals(2.0, recoil.recoverySpeed());
        assertEquals(0.5, recoil.aimMultiplier());
        assertEquals(0.9, recoil.crouchMultiplier());
    }

    @Test
    void aNonNumericTuningValueIsReportedAndIgnored() {
        String json = """
                {
                  "recoil": {
                    "vertical_recoil": 2.5,
                    "smoothing": "slow"
                  }
                }
                """;
        ValidationReport report = new ValidationReport();

        WeaponDefinition.Recoil recoil = read(json, report);

        assertNotNull(recoil, "one bad optional value must not make the block unusable");
        assertNull(recoil.smoothing(), "the invalid value is ignored, not guessed");
        assertEquals(1, report.count(PackDiagnostic.Severity.WARNING), errorsOf(report));
        assertEquals(0, report.count(PackDiagnostic.Severity.ERROR));
        assertEquals(PlayerRecoilController.DEFAULT_SMOOTHING, RecoilProfile.of(recoil, null).smoothing(),
                "the default then applies, so the weapon keeps working");
    }

    @Test
    void aBlockThatDeclaresOnlyTuningPropertiesIsStillAConfiguredBlock() {
        String json = """
                {
                  "recoil": {
                    "smoothing": 0.5,
                    "recovery_speed": 0.5,
                    "aim_multiplier": 0.5,
                    "crouch_multiplier": 0.5
                  }
                }
                """;

        WeaponDefinition.Recoil recoil = read(json, new ValidationReport());

        assertNotNull(recoil, "the four properties are part of the recoil block, so it is no longer 'empty'");
        assertFalse(recoil.isEmpty());
        assertTrue(RecoilProfile.of(recoil, null).isNeutral(),
                "but with no impulse and no kickback there is still nothing to apply");
    }

    // ---------------------------------------------------------------- helpers

    private static WeaponDefinition.Recoil read(String json, ValidationReport report) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new WeaponDefinitionParser("arg_pack", "weapons/tuned_weapon.json",
                new ResourceId("arg_pack", "weapons/tuned_weapon"), report).readRecoil(root);
    }

    private static String errorsOf(ValidationReport report) {
        StringBuilder message = new StringBuilder();
        for (PackDiagnostic diagnostic : report.diagnostics()) {
            message.append(diagnostic.format()).append('\n');
        }
        return message.toString();
    }
}
