package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.client.recoil.RecoilProfile;
import com.arg_studios.arg_weaponry.core.PackDiagnostic;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The retired screen-shake properties, as a content pack can still contain them.
 *
 * <p>The milestone removes the feature, not the tolerance: an older pack that still declares
 * {@code screen_shake_Recoil_value} and/or {@code screen_shake_recoil_speed} must keep loading, those keys must be ignored,
 * and they must not be able to switch any camera effect on - which is now a property of the data model itself, since the
 * parsed recoil block no longer has a field for them.</p>
 *
 * <p>The recoil reader is exercised directly rather than through a whole weapon definition, because a full parse also
 * builds the firing modes and those need the game's own classes; the recoil schema itself is plain JSON, and it is the
 * part this milestone changed.</p>
 */
class WeaponDefinitionParserRetiredRecoilTest {

    /** The milestone's own configuration, with the two retired properties still present, as an old pack would have them. */
    private static final String WITH_RETIRED_PROPERTIES = """
            {
              "recoil": {
                "kickback_recoil_amplitude": 0.06,
                "screen_shake_Recoil_value": 0.8,
                "screen_shake_recoil_speed": 2.0,
                "vertical_recoil": 2.5,
                "horizontal_recoil_left": 0.02,
                "horizontal_recoil_right": 0.02
              }
            }
            """;

    /** The same configuration after the creator deleted the two retired lines. */
    private static final String WITHOUT_RETIRED_PROPERTIES = """
            {
              "recoil": {
                "kickback_recoil_amplitude": 0.06,
                "vertical_recoil": 2.5,
                "horizontal_recoil_left": 0.02,
                "horizontal_recoil_right": 0.02
              }
            }
            """;

    /** A pack whose recoil block contains nothing but the retired properties. */
    private static final String ONLY_RETIRED_PROPERTIES = """
            {
              "recoil": {
                "screen_shake_Recoil_value": 0.8,
                "screen_shake_recoil_speed": 2.0
              }
            }
            """;

    /** The same retired properties written at the old top level, outside the {@code recoil} object. */
    private static final String TOP_LEVEL_RETIRED_PROPERTIES = """
            {
              "screen_shake_Recoil_value": 0.8,
              "screen_shake_recoil_speed": 2.0,
              "recoil": {
                "kickback_recoil_amplitude": 0.06,
                "vertical_recoil": 2.5,
                "horizontal_recoil_left": 0.02,
                "horizontal_recoil_right": 0.02
              }
            }
            """;

    @Test
    void theRetiredPropertiesAreIgnoredAndTheLiveValuesParseAsWritten() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.Recoil recoil = read(WITH_RETIRED_PROPERTIES, report);

        assertNotNull(recoil, "the retired screen-shake keys must never make the recoil block unusable");
        assertEquals(0, report.count(PackDiagnostic.Severity.ERROR), errorsOf(report));
        assertTrue(report.diagnostics().isEmpty(), "nothing about the retired keys is worth a finding");

        assertEquals(0.06, recoil.kickbackAmplitude(), "kickback_recoil_amplitude must parse as authored");
        assertEquals(2.5, recoil.vertical(), "vertical_recoil must parse as authored");
        assertEquals(0.02, recoil.horizontalLeft(), "horizontal_recoil_left must parse as authored");
        assertEquals(0.02, recoil.horizontalRight(), "horizontal_recoil_right must parse as authored");

        RecoilProfile profile = RecoilProfile.of(recoil, null);
        assertTrue(profile.hasKickback(), "the weapon kickback is unaffected");
        assertTrue(profile.hasViewRecoil(), "the camera recoil is unaffected");
        assertEquals(0.06, profile.kickbackBlocks(), "kickback_recoil_amplitude must stay 0.06");
        assertEquals(2.5, profile.verticalDegrees(), "vertical_recoil must stay 2.5");
        assertEquals(0.02, profile.horizontalLeftDegrees(), "horizontal_recoil_left must stay 0.02");
        assertEquals(0.02, profile.horizontalRightDegrees(), "horizontal_recoil_right must stay 0.02");
    }

    @Test
    void aPackWithTheRetiredPropertiesResolvesExactlyLikeOneWithoutThem() {
        WeaponDefinition.Recoil retired = read(WITH_RETIRED_PROPERTIES, new ValidationReport());
        WeaponDefinition.Recoil cleaned = read(WITHOUT_RETIRED_PROPERTIES, new ValidationReport());

        assertEquals(cleaned, retired,
                "the retired keys may not change a single recoil value, and they add nothing of their own");
        assertEquals(RecoilProfile.of(cleaned, null), RecoilProfile.of(retired, null));
    }

    @Test
    void aRecoilBlockOfOnlyRetiredPropertiesProducesNoRecoilAtAll() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.Recoil recoil = read(ONLY_RETIRED_PROPERTIES, report);

        assertNull(recoil, "nothing but retired keys means no recoil configuration - and certainly no screen shake");
        assertEquals(0, report.count(PackDiagnostic.Severity.ERROR), errorsOf(report));
        assertTrue(RecoilProfile.of((WeaponDefinition.Recoil) null, null).isNeutral(),
                "a retired property can never activate anything");
    }

    @Test
    void theRetiredPropertiesAreAlsoIgnoredAtTheOldTopLevel() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.Recoil recoil = read(TOP_LEVEL_RETIRED_PROPERTIES, report);

        assertNotNull(recoil);
        assertEquals(0, report.count(PackDiagnostic.Severity.ERROR), errorsOf(report));
        assertEquals(0.06, recoil.kickbackAmplitude());
        assertEquals(2.5, recoil.vertical());
        assertEquals(0.02, recoil.horizontalLeft());
        assertEquals(0.02, recoil.horizontalRight());
    }

    @Test
    void theRetiredKeysDoNotEvenProduceAnUnknownPropertyFinding() {
        ValidationReport report = new ValidationReport();

        read(WITH_RETIRED_PROPERTIES, report);
        read(TOP_LEVEL_RETIRED_PROPERTIES, report);

        assertTrue(report.diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.message().contains("Unknown property")),
                "the retired keys are tolerated on purpose, so an old pack loads exactly as quietly as a new one");
    }

    // ---------------------------------------------------------------- helpers

    private static WeaponDefinition.Recoil read(String json, ValidationReport report) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new WeaponDefinitionParser("arg_pack", "weapons/retired_props.json",
                new ResourceId("arg_pack", "weapons/retired_props"), report).readRecoil(root);
    }

    private static String errorsOf(ValidationReport report) {
        StringBuilder message = new StringBuilder();
        for (PackDiagnostic diagnostic : report.diagnostics()) {
            message.append(diagnostic.format()).append('\n');
        }
        return message.toString();
    }
}
