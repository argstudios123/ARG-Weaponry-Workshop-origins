package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.PackDiagnostic;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@code muzzle_flash} block, read through the definition parser.
 *
 * <p>The reader is exercised directly, like the recoil reader, because a whole-document parse also builds the firing
 * modes and those need the game's own classes. What is verified here is only what the schema promises: the two
 * mutually exclusive attachment spellings, the deterministic fallback when a pack declares both, the absence of a
 * duration property, the sprite sheet reference and the documented bounds.</p>
 */
class WeaponDefinitionParserMuzzleFlashTest {

    /** The single-attachment example from the schema. */
    private static final String SINGLE_BONE = """
            {
              "muzzle_flash": {
                "enabled": true,
                "bone": "muzzle_flash",
                "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png",
                "size": 0.35
              }
            }
            """;

    /** The multiple-attachment example from the schema. */
    private static final String MULTIPLE_BONES = """
            {
              "muzzle_flash": {
                "enabled": true,
                "bones": [ "muzzle_flash", "muzzle_flash_2", "muzzle_flash_3" ],
                "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png",
                "size": 0.35
              }
            }
            """;

    @Test
    void anAbsentBlockDisablesTheFeature() {
        ValidationReport report = new ValidationReport();

        assertNull(read("{}", report), "a weapon without the property has no muzzle flash at all");
        assertTrue(report.diagnostics().isEmpty(), "an absent optional block is not a finding");
    }

    @Test
    void theSingleAttachmentExampleParsesAsOneAttachmentPoint() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.MuzzleFlash flash = read(SINGLE_BONE, report);

        assertNotNull(flash);
        assertTrue(flash.isUsable());
        assertTrue(flash.enabled());
        assertEquals(List.of("muzzle_flash"), flash.bones(), "one bone is a one-element list");
        assertEquals(new ResourceId("arg_pack", "weapons/special/muzzle_flash/universal_flash.png"), flash.texture());
        assertEquals(0.35D, flash.size(), 1.0E-9D);
        assertTrue(report.diagnostics().isEmpty(),
                () -> "the documented example must parse cleanly: " + report.diagnostics());
    }

    @Test
    void theMultipleAttachmentExampleParsesAsThreeAttachmentPoints() {
        WeaponDefinition.MuzzleFlash flash = read(MULTIPLE_BONES, new ValidationReport());

        assertNotNull(flash);
        assertTrue(flash.isUsable());
        assertEquals(List.of("muzzle_flash", "muzzle_flash_2", "muzzle_flash_3"), flash.bones());
    }

    @Test
    void anOmittedEnabledFlagKeepsTheFeatureOn() {
        WeaponDefinition.MuzzleFlash flash = read("""
                {
                  "muzzle_flash": {
                    "bone": "muzzle_flash",
                    "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png"
                  }
                }
                """, new ValidationReport());

        assertNotNull(flash);
        assertTrue(flash.enabled(), "the presence of the block is the opt-in");
        assertEquals(WeaponDefinition.MuzzleFlash.DEFAULT_SIZE, flash.size(), 1.0E-9D, "size falls back to the default");
    }

    @Test
    void anExplicitFalseDisablesRenderingButKeepsTheConfiguration() {
        WeaponDefinition.MuzzleFlash flash = read("""
                {
                  "muzzle_flash": {
                    "enabled": false,
                    "bone": "muzzle_flash",
                    "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png",
                    "size": 0.35
                  }
                }
                """, new ValidationReport());

        assertNotNull(flash, "the block is still part of the immutable definition");
        assertFalse(flash.enabled());
        assertFalse(flash.isUsable(), "a disabled flash renders nothing");
        assertEquals(List.of("muzzle_flash"), flash.bones());
    }

    @Test
    void bothSpellingsAreMutuallyExclusiveAndTheSingleBoneWins() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.MuzzleFlash flash = read("""
                {
                  "muzzle_flash": {
                    "bone": "muzzle_flash",
                    "bones": [ "muzzle_flash_2", "muzzle_flash_3" ],
                    "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png",
                    "size": 0.35
                  }
                }
                """, report);

        assertNotNull(flash);
        assertEquals(List.of("muzzle_flash"), flash.bones(),
                "the fallback is deterministic and never a combination of the two properties");
        assertEquals(1, report.count(PackDiagnostic.Severity.WARNING),
                () -> "the conflict must be reported: " + report.diagnostics());
        assertEquals(0, report.count(PackDiagnostic.Severity.ERROR), "the weapon keeps working");
    }

    @Test
    void anEmptyBoneListDisablesTheEffectAndIsReported() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.MuzzleFlash flash = read("""
                {
                  "muzzle_flash": {
                    "enabled": true,
                    "bones": [],
                    "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png",
                    "size": 0.35
                  }
                }
                """, report);

        assertNotNull(flash);
        assertTrue(flash.bones().isEmpty());
        assertFalse(flash.isUsable(), "without an attachment point there is nothing to draw");
        assertEquals(1, report.count(PackDiagnostic.Severity.WARNING),
                () -> "the empty list must be reported: " + report.diagnostics());
    }

    @Test
    void theAttachmentLimitIsTen() {
        StringBuilder bones = new StringBuilder();
        for (int index = 0; index < 14; index++) {
            if (index > 0) {
                bones.append(',');
            }
            bones.append("\"muzzle_flash_").append(index).append('"');
        }
        ValidationReport report = new ValidationReport();

        WeaponDefinition.MuzzleFlash flash = read("""
                {
                  "muzzle_flash": {
                    "bones": [ %s ],
                    "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png"
                  }
                }
                """.formatted(bones), report);

        assertNotNull(flash);
        assertEquals(WeaponDefinition.MuzzleFlash.MAX_BONES, flash.bones().size(), "the schema caps the list at ten");
        assertEquals("muzzle_flash_0", flash.bones().get(0), "the first entries are the ones that are kept");
        assertEquals(1, report.count(PackDiagnostic.Severity.WARNING),
                () -> "the truncation must be reported: " + report.diagnostics());
    }

    @Test
    void anInvalidSpriteSheetReferenceDisablesTheEffect() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.MuzzleFlash flash = read("""
                {
                  "muzzle_flash": {
                    "bone": "muzzle_flash",
                    "texture": "not a resource id",
                    "size": 0.35
                  }
                }
                """, report);

        assertNotNull(flash);
        assertNull(flash.texture());
        assertFalse(flash.isUsable(), "an invalid texture disables the effect instead of crashing");
        assertTrue(report.count(PackDiagnostic.Severity.WARNING) >= 1,
                () -> "the broken reference must be reported: " + report.diagnostics());
    }

    @Test
    void anAbsentSpriteSheetDisablesTheEffect() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.MuzzleFlash flash = read("""
                { "muzzle_flash": { "bone": "muzzle_flash" } }
                """, report);

        assertNotNull(flash);
        assertNull(flash.texture());
        assertFalse(flash.isUsable());
        assertEquals(1, report.count(PackDiagnostic.Severity.WARNING),
                () -> "the missing texture must be reported: " + report.diagnostics());
    }

    @Test
    void anOutOfRangeSizeFallsBackToTheDefault() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.MuzzleFlash flash = read("""
                {
                  "muzzle_flash": {
                    "bone": "muzzle_flash",
                    "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png",
                    "size": 900
                  }
                }
                """, report);

        assertNotNull(flash);
        assertEquals(WeaponDefinition.MuzzleFlash.DEFAULT_SIZE, flash.size(), 1.0E-9D);
        assertEquals(1, report.count(PackDiagnostic.Severity.WARNING),
                () -> "the rejected size must be reported: " + report.diagnostics());
    }

    @Test
    void aWronglyTypedBlockIsReportedAndIgnored() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.MuzzleFlash flash = read("{ \"muzzle_flash\": 5 }", report);

        assertNull(flash, "a non-object block cannot describe a flash");
        assertEquals(1, report.count(PackDiagnostic.Severity.WARNING),
                () -> "the wrong type must be reported: " + report.diagnostics());
        assertEquals(0, report.count(PackDiagnostic.Severity.ERROR), "presentation never rejects the weapon");
    }

    @Test
    void aDeclaredSpriteGridIsReadAndDefaultsWhenAbsent() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.MuzzleFlash declared = read("""
                { "muzzle_flash": {
                    "bone": "muzzle_flash",
                    "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png",
                    "sprites": { "rows": 1, "columns": 8, "fps": 60, "type": "play_once" }
                } }
                """, report);

        assertNotNull(declared);
        assertNotNull(declared.sprites(), "the grid is what turns an eighth of the sheet into one frame");
        assertEquals(1, declared.sprites().rows());
        assertEquals(8, declared.sprites().columns());
        assertEquals(8, declared.sprites().frameCount());
        assertEquals(60, declared.sprites().fps());
        assertEquals(WeaponDefinition.MuzzleFlash.SpriteType.PLAY_ONCE, declared.sprites().type());
        assertTrue(report.diagnostics().isEmpty(), () -> "the block is part of the schema: " + report.diagnostics());

        WeaponDefinition.MuzzleFlash undeclared = read(SINGLE_BONE, new ValidationReport());
        assertNotNull(undeclared);
        assertNull(undeclared.sprites(),
                "a pack that declares no grid keeps deriving a horizontal strip, so it renders as it always did");
    }

    @Test
    void anUnusableGridValueFallsBackInsteadOfRejectingTheWeapon() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.MuzzleFlash flash = read("""
                { "muzzle_flash": {
                    "bone": "muzzle_flash",
                    "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png",
                    "sprites": { "rows": 0, "columns": 8, "fps": -5, "type": "sideways" }
                } }
                """, report);

        assertNotNull(flash);
        assertNotNull(flash.sprites(), "a broken value is repaired, never turned into a dropped block");
        assertEquals(1, flash.sprites().rows());
        assertEquals(8, flash.sprites().columns());
        assertEquals(WeaponDefinition.MuzzleFlash.Sprites.DEFAULT_FPS, flash.sprites().fps());
        assertEquals(WeaponDefinition.MuzzleFlash.Sprites.DEFAULT_TYPE, flash.sprites().type());
        assertTrue(report.count(PackDiagnostic.Severity.WARNING) >= 3,
                () -> "every repaired value must be reported: " + report.diagnostics());
        assertEquals(0, report.count(PackDiagnostic.Severity.ERROR), "presentation never rejects the weapon");
    }

    @Test
    void theOptionalDurationIsReadAsMilliseconds() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.MuzzleFlash flash = read("""
                { "muzzle_flash": {
                    "bone": "muzzle_flash",
                    "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png",
                    "duration_ms": 80,
                    "sprites": { "rows": 1, "columns": 8, "fps": 200, "type": "random" }
                } }
                """, report);

        assertNotNull(flash);
        assertEquals(80, flash.durationMs());
        assertTrue(flash.hasDuration());
        assertEquals(0.080D, flash.durationSeconds(), 1.0E-9D, "milliseconds are stored as seconds for the renderer");
        assertEquals(200, flash.sprites().fps(), "duration_ms and fps are independent properties");
        assertTrue(report.diagnostics().isEmpty(), () -> "the property is part of the schema: " + report.diagnostics());
    }

    @Test
    void aBlockWithoutDurationKeepsTheAnimationDrivenLifetime() {
        WeaponDefinition.MuzzleFlash flash = read(SINGLE_BONE, new ValidationReport());

        assertNotNull(flash);
        assertNull(flash.durationMs(), "absent means 'the animation's frames decide', as before");
        assertFalse(flash.hasDuration());
        assertEquals(0.0D, flash.durationSeconds(), 1.0E-9D);
    }

    @Test
    void aDurationThatCannotBeUsedIsReportedAndTreatedAsAbsent() {
        for (String value : new String[] {"0", "-5", "2.5e-9"}) {
            ValidationReport report = new ValidationReport();
            WeaponDefinition.MuzzleFlash flash = read("""
                    { "muzzle_flash": {
                        "bone": "muzzle_flash",
                        "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png",
                        "duration_ms": %s
                    } }
                    """.formatted(value), report);

            assertNotNull(flash, "presentation never rejects the weapon: " + value);
            assertNull(flash.durationMs(), "an unusable duration is ignored: " + value);
            assertEquals(1, report.count(PackDiagnostic.Severity.WARNING),
                    () -> "the unusable value must be reported: " + value + " -> " + report.diagnostics());
            assertEquals(0, report.count(PackDiagnostic.Severity.ERROR), "and never rejects the weapon");
        }

        ValidationReport wrongType = new ValidationReport();
        WeaponDefinition.MuzzleFlash flash = read("""
                { "muzzle_flash": {
                    "bone": "muzzle_flash",
                    "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png",
                    "duration_ms": "eighty"
                } }
                """, wrongType);
        assertNotNull(flash);
        assertNull(flash.durationMs(), "a string is not a duration");
        assertTrue(wrongType.count(PackDiagnostic.Severity.WARNING) >= 1, "and it is reported");
    }

    @Test
    void aDurationAboveTheCeilingIsClamped() {
        ValidationReport report = new ValidationReport();

        WeaponDefinition.MuzzleFlash flash = read("""
                { "muzzle_flash": {
                    "bone": "muzzle_flash",
                    "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png",
                    "duration_ms": 250000
                } }
                """, report);

        assertNotNull(flash);
        assertEquals(WeaponDefinition.MuzzleFlash.MAX_DURATION_MS, flash.durationMs(),
                "a typo cannot leave a flash on screen for minutes");
        assertEquals(0, report.count(PackDiagnostic.Severity.ERROR));
        assertTrue(report.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("clamped")),
                "the clamp is explained");
    }

    // ---------------------------------------------------------------- helpers

    private static WeaponDefinition.MuzzleFlash read(String json, ValidationReport report) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new WeaponDefinitionParser("arg_pack", "weapons/muzzle_flash.json",
                new ResourceId("arg_pack", "weapons/muzzle_flash"), report).readMuzzleFlash(root);
    }
}
