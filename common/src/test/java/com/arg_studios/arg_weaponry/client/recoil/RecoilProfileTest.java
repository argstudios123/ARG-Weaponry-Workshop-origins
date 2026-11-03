package com.arg_studios.arg_weaponry.client.recoil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The JSON-facing contract of the recoil block: the property names and their meaning, "absent" versus "configured zero",
 * the per-direction horizontal values, and the fact that player recoil and weapon kickback stay independent of one
 * another.
 */
class RecoilProfileTest {

    /** The configuration this milestone is protecting, verbatim: kickback 0.06, vertical 2.5, both directions 0.02. */
    private static final WeaponDefinition.Recoil CONFIGURED =
            new WeaponDefinition.Recoil(0.06, 2.5, null, 0.02, 0.02);

    @Test
    void theAuthoredConfigurationResolvesAsWritten() {
        RecoilProfile profile = RecoilProfile.of(CONFIGURED, null);

        assertEquals(0.06, profile.kickbackBlocks(), "kickback_recoil_amplitude must stay 0.06");
        assertEquals(2.5, profile.verticalDegrees(), "vertical_recoil must stay 2.5");
        assertEquals(0.02, profile.horizontalLeftDegrees(), "horizontal_recoil_left must stay 0.02");
        assertEquals(0.02, profile.horizontalRightDegrees(), "horizontal_recoil_right must stay 0.02");
        assertTrue(profile.hasKickback());
        assertTrue(profile.hasViewRecoil());
        assertFalse(profile.isNeutral());
    }

    @Test
    void aWeaponWithoutKickbackNeverGetsOneFromTheViewRecoil() {
        RecoilProfile profile = RecoilProfile.of(new WeaponDefinition.Recoil(null, 2.5, null, 0.02, 0.02), null);

        assertFalse(profile.hasKickback(), "no kickback property means no weapon movement at all");
        assertTrue(profile.hasViewRecoil(), "the aim still recoils");
    }

    @Test
    void aConfiguredZeroKickbackIsStillNotAWeaponMovement() {
        RecoilProfile configured = RecoilProfile.of(new WeaponDefinition.Recoil(0.0, 2.5, null, null, null), null);

        assertFalse(configured.hasKickback());
        assertEquals(0.0, configured.kickbackBlocks());
        assertTrue(configured.hasViewRecoil(), "kickback 0 must not disable the aim recoil");
    }

    @Test
    void theHorizontalDirectionKeepsTheTwoConfiguredMagnitudesIndependent() {
        RecoilProfile symmetric = RecoilProfile.of(CONFIGURED, null);

        assertEquals(0.02, symmetric.horizontalDirection(null), 1.0E-12, "without randomness the right value is used");
        assertEquals(0.02, symmetric.horizontalDirection(randomThatAlwaysPicksRight()), 1.0E-12);
        assertEquals(-0.02, symmetric.horizontalDirection(randomThatAlwaysPicksLeft()), 1.0E-12);

        RecoilProfile asymmetric = RecoilProfile.of(new WeaponDefinition.Recoil(0.0, 2.5, null, 0.03, 0.01), null);
        assertEquals(0.01, asymmetric.horizontalDirection(randomThatAlwaysPicksRight()), 1.0E-12,
                "right keeps its own magnitude");
        assertEquals(-0.03, asymmetric.horizontalDirection(randomThatAlwaysPicksLeft()), 1.0E-12,
                "left keeps its own magnitude");
    }

    @Test
    void aOneSidedConfigurationAlwaysRecoilsToThatSide() {
        RecoilProfile leftOnly = RecoilProfile.of(new WeaponDefinition.Recoil(0.0, 2.5, null, 0.04, null), null);
        assertEquals(-0.04, leftOnly.horizontalDirection(randomThatAlwaysPicksRight()), 1.0E-12,
                "a weapon configured for the left must always recoil to the left");

        RecoilProfile rightOnly = RecoilProfile.of(new WeaponDefinition.Recoil(0.0, 2.5, null, null, 0.04), null);
        assertEquals(0.04, rightOnly.horizontalDirection(randomThatAlwaysPicksLeft()), 1.0E-12,
                "a weapon configured for the right must always recoil to the right");
    }

    @Test
    void aWeaponWithoutHorizontalRecoilHasNoSidewaysImpulse() {
        RecoilProfile profile = RecoilProfile.of(new WeaponDefinition.Recoil(0.06, 2.5, null, null, null), null);

        assertEquals(0.0, profile.horizontalDirection(new Random(2L)));
        assertTrue(profile.hasViewRecoil(), "the vertical impulse keeps the aim recoil active");
    }

    @Test
    void theLegacySymmetricalHorizontalValueStillAppliesToBothDirections() {
        RecoilProfile legacy = RecoilProfile.of(new WeaponDefinition.Recoil(0.0, 2.5, 0.05, null, null), null);

        assertEquals(0.05, legacy.horizontalLeftDegrees());
        assertEquals(0.05, legacy.horizontalRightDegrees());
        assertEquals(0.05, legacy.horizontalDirection(randomThatAlwaysPicksRight()), 1.0E-12);
        assertEquals(-0.05, legacy.horizontalDirection(randomThatAlwaysPicksLeft()), 1.0E-12);
    }

    @Test
    void theDirectionalValuesWinOverTheLegacyValue() {
        RecoilProfile mixed = RecoilProfile.of(new WeaponDefinition.Recoil(0.0, 2.5, 0.05, 0.07, null), null);

        assertEquals(0.07, mixed.horizontalLeftDegrees(), "a configured direction wins over the legacy value");
        assertEquals(0.0, mixed.horizontalRightDegrees());
        assertEquals(-0.07, mixed.horizontalDirection(null), 1.0E-12);
    }

    @Test
    void aNeutralConfigurationDoesNothingAtAll() {
        RecoilProfile nothing = RecoilProfile.of((WeaponDefinition.Recoil) null, null);
        assertEquals(RecoilProfile.NONE, nothing);
        assertTrue(nothing.isNeutral());
        assertFalse(nothing.hasKickback());
        assertFalse(nothing.hasViewRecoil());

        assertEquals(RecoilProfile.NONE, RecoilProfile.of((WeaponDefinition) null));
        assertEquals(0.0, nothing.horizontalDirection(new Random(1L)));
    }

    @Test
    void everyValueIsClampedToTheDocumentedCaps() {
        RecoilProfile extreme =
                RecoilProfile.of(new WeaponDefinition.Recoil(1000.0, 1000.0, null, -5.0, 1.0E6), null);

        assertEquals(RecoilProfile.MAX_KICKBACK_AMPLITUDE_BLOCKS, extreme.kickbackBlocks());
        assertEquals(RecoilProfile.MAX_VIEW_IMPULSE_DEGREES, extreme.verticalDegrees());
        assertEquals(5.0, extreme.horizontalLeftDegrees(), "a magnitude is used as authored, never as a direction");
        assertEquals(RecoilProfile.MAX_VIEW_IMPULSE_DEGREES, extreme.horizontalRightDegrees());
    }

    // ---------------------------------------------------------------- tuning properties

    @Test
    void theFourTuningPropertiesResolveAsWritten() {
        RecoilProfile profile = RecoilProfile.of(
                new WeaponDefinition.Recoil(0.06, 2.5, null, 0.02, 0.02, 0.5, 0.15, 0.6, 0.8), null);

        assertEquals(0.5, profile.smoothing(), "smoothing is the configured share per 50 ms");
        assertEquals(0.15, profile.recoverySpeed(), "recovery_speed is the configured multiple of the baseline");
        assertEquals(0.6, profile.aimMultiplier());
        assertEquals(0.8, profile.crouchMultiplier());
        assertEquals(PlayerRecoilController.RECOVERY_SECONDS / 0.15, profile.recoverySeconds(), 1.0E-12,
                "recovery_speed 0.15 turns the 0.8 s baseline into about 5.3 s");
    }

    @Test
    void anOlderDefinitionKeepsExactlyThePreExistingBehaviour() {
        RecoilProfile older = RecoilProfile.of(new WeaponDefinition.Recoil(0.06, 2.5, null, 0.02, 0.02), null);
        RecoilProfile builtTheOldWay = new RecoilProfile(0.06, true, 2.5, 0.02, 0.02, RecoilProfile.Sustained.NONE);

        assertEquals(PlayerRecoilController.DEFAULT_SMOOTHING, older.smoothing());
        assertEquals(RecoilProfile.DEFAULT_RECOVERY_SPEED, older.recoverySpeed());
        assertEquals(RecoilProfile.DEFAULT_AIM_MULTIPLIER, older.aimMultiplier());
        assertEquals(RecoilProfile.DEFAULT_CROUCH_MULTIPLIER, older.crouchMultiplier());
        assertEquals(PlayerRecoilController.RECOVERY_SECONDS, older.recoverySeconds(), 0.0);
        assertEquals(older, builtTheOldWay, "the compatibility constructor and an untuned definition agree");
        assertEquals(new ViewRecoilResponse(2.5, 0.02), older.viewRecoilImpulse(null, false, false),
                "the impulse of an untuned weapon is the one it always produced");
    }

    @Test
    void outOfRangeTuningValuesAreClampedInsteadOfBreakingTheWeapon() {
        RecoilProfile extreme = RecoilProfile.of(
                new WeaponDefinition.Recoil(0.06, 2.5, null, 0.02, 0.02, 5.0, 1000.0, 9.0, -1.0), null);

        assertEquals(PlayerRecoilController.MAX_SMOOTHING, extreme.smoothing(), "more than 1 means 'apply it now'");
        assertEquals(RecoilProfile.MAX_RECOVERY_SPEED, extreme.recoverySpeed());
        assertEquals(1.0, extreme.aimMultiplier(), "a multiplier above 1 cannot amplify the camera impulse");
        assertEquals(0.0, extreme.crouchMultiplier(), "a negative multiplier floors at 'no camera impulse'");

        RecoilProfile inverted = RecoilProfile.of(
                new WeaponDefinition.Recoil(0.06, 2.5, null, 0.02, 0.02, 0.0, 0.0, 0.0, 0.0), null);

        assertEquals(PlayerRecoilController.MIN_SMOOTHING, inverted.smoothing(),
                "a zero smooths as slowly as allowed instead of freezing the camera");
        assertEquals(RecoilProfile.MIN_RECOVERY_SPEED, inverted.recoverySpeed());
        assertEquals(0.0, inverted.aimMultiplier());
        assertEquals(0.0, inverted.crouchMultiplier());
        assertTrue(inverted.recoverySeconds() <= PlayerRecoilController.MAX_RECOVERY_SECONDS);
    }

    @Test
    void aimingAndCrouchingScaleTheImpulseMultiplicatively() {
        RecoilProfile tuned = RecoilProfile.of(
                new WeaponDefinition.Recoil(0.06, 2.5, null, 0.02, 0.02, 1.0, 1.0, 0.6, 0.8), null);

        assertEquals(1.0, tuned.impulseScale(false, false), 0.0, "neither stance keeps the configured recoil");
        assertEquals(0.6, tuned.impulseScale(true, false), 1.0E-12);
        assertEquals(0.8, tuned.impulseScale(false, true), 1.0E-12);
        assertEquals(0.48, tuned.impulseScale(true, true), 1.0E-12, "both stances multiply, they do not add");

        assertEquals(2.5, tuned.viewRecoilImpulse(null, false, false).pitchDegrees(), 1.0E-12);
        assertEquals(1.5, tuned.viewRecoilImpulse(null, true, false).pitchDegrees(), 1.0E-12);
        assertEquals(2.0, tuned.viewRecoilImpulse(null, false, true).pitchDegrees(), 1.0E-12);
        assertEquals(1.2, tuned.viewRecoilImpulse(null, true, true).pitchDegrees(), 1.0E-12);
        assertEquals(0.02 * 0.48, tuned.viewRecoilImpulse(null, true, true).yawDegrees(), 1.0E-12,
                "the horizontal impulse is scaled by the same factor");
    }

    @Test
    void theStanceMultipliersNeverChangeTheKickback() {
        RecoilProfile tuned = RecoilProfile.of(
                new WeaponDefinition.Recoil(0.06, 2.5, null, 0.02, 0.02, 0.5, 0.15, 0.6, 0.8), null);
        RecoilProfile plain = RecoilProfile.of(new WeaponDefinition.Recoil(0.06, 2.5, null, 0.02, 0.02), null);

        assertEquals(0.06, tuned.kickbackBlocks(), "kickback_recoil_amplitude keeps its value and its meaning");
        assertTrue(tuned.hasKickback());

        WeaponKickbackController reference = new WeaponKickbackController();
        WeaponKickbackController withTuning = new WeaponKickbackController();
        reference.tick(0.05);
        withTuning.tick(0.05);
        reference.shot(plain);
        withTuning.shot(tuned);
        for (int tick = 0; tick < 12; tick++) {
            reference.tick(0.05);
            withTuning.tick(0.05);
            for (int frame = 0; frame <= 4; frame++) {
                float partial = frame / 4.0F;
                assertEquals(reference.sample(partial).backwardBlocks(),
                        withTuning.sample(partial).backwardBlocks(), 0.0F,
                        "the tuning properties must not move the weapon model");
            }
        }
    }

    @Test
    void theImpulseCarriesTheWeaponsOwnTimingSettings() {
        RecoilProfile tuned = RecoilProfile.of(
                new WeaponDefinition.Recoil(0.06, 2.5, null, 0.02, 0.02, 0.5, 0.15, 0.6, 0.8), null);

        ViewRecoilResponse impulse = tuned.viewRecoilImpulse(null, false, false);

        assertEquals(0.5, impulse.smoothing(), 0.0);
        assertEquals(tuned.recoverySeconds(), impulse.recoverySeconds(), 0.0);
        assertEquals(PlayerRecoilController.RECOVERY_SECONDS / 0.15, impulse.recoverySeconds(), 1.0E-12);
    }

    @Test
    void theKickbackModifierScalesOnlyTheWeaponWhileAiming() {
        RecoilProfile tuned = RecoilProfile.of(
                new WeaponDefinition.Recoil(0.06, 2.5, null, 0.02, 0.02, 1.0, 1.0, 0.6, 0.8, 0.25), null);

        assertEquals(0.06, tuned.kickbackBlocks(false), 0.0, "hip fire keeps the authored amplitude");
        assertEquals(0.015, tuned.kickbackBlocks(true), 1.0E-12, "ADS applies the modifier exactly once");
        assertEquals(2.5, tuned.verticalDegrees(), 0.0, "vertical recoil is never scaled by the kickback modifier");
        assertEquals(0.02, tuned.horizontalLeftDegrees(), 0.0);
        assertEquals(0.02, tuned.horizontalRightDegrees(), 0.0);
        assertEquals(2.5 * 0.6, tuned.viewRecoilImpulse(null, true, false).pitchDegrees(), 1.0E-12,
                "aim_multiplier still owns the camera impulse");
    }

    @Test
    void aZeroKickbackAimingModifierRemovesOnlyTheAdsKickback() {
        RecoilProfile tuned = RecoilProfile.of(
                new WeaponDefinition.Recoil(0.06, 2.5, null, 0.02, 0.02, 1.0, 1.0, 0.6, 0.8, 0.0), null);

        assertEquals(0.06, tuned.kickbackBlocks(false), 0.0);
        assertEquals(0.0, tuned.kickbackBlocks(true), 0.0);
        assertTrue(tuned.hasKickback(), "the weapon still kicks at hip fire");
        assertTrue(tuned.hasViewRecoil(), "and the camera recoil is untouched");
    }

    @Test
    void anOlderProfileKeepsTheUnmodifiedKickback() {
        RecoilProfile legacy = RecoilProfile.of(
                new WeaponDefinition.Recoil(0.06, 2.5, null, 0.02, 0.02, 1.0, 1.0, 0.6, 0.8), null);

        assertEquals(RecoilProfile.DEFAULT_KICKBACK_AIMING_MODIFIER, legacy.kickbackAimingModifier(), 0.0);
        assertEquals(legacy.kickbackBlocks(), legacy.kickbackBlocks(true), 0.0,
                "a missing property must preserve the exact previous ADS kickback");
    }

    @Test
    void theKickbackControllerReceivesTheAlreadyScaledAdsAmplitude() {
        RecoilProfile tuned = RecoilProfile.of(
                new WeaponDefinition.Recoil(0.06, 2.5, null, 0.02, 0.02, 1.0, 1.0, 0.6, 0.8, 0.25), null);
        WeaponKickbackController hip = new WeaponKickbackController();
        WeaponKickbackController ads = new WeaponKickbackController();
        hip.tick(0.05);
        ads.tick(0.05);
        hip.shot(tuned, false);
        ads.shot(tuned, true);
        hip.tick(0.05);
        ads.tick(0.05);

        assertEquals(0.25, ads.sample(0.5F).backwardBlocks() / hip.sample(0.5F).backwardBlocks(), 1.0E-6,
                "the modifier is applied once, by the profile, before the impulse curve");
    }

    /**
     * @return a {@link Random} whose first boolean draw is {@code false}, i.e. "the leftward side"
     */
    private static Random randomThatAlwaysPicksLeft() {
        return new Random(4L) {
            private boolean first = true;

            @Override
            public boolean nextBoolean() {
                if (first) {
                    first = false;
                    return false;
                }
                return super.nextBoolean();
            }
        };
    }

    /**
     * @return a {@link Random} whose first boolean draw is {@code true}, i.e. "the rightward side"
     */
    private static Random randomThatAlwaysPicksRight() {
        return new Random(4L) {
            private boolean first = true;

            @Override
            public boolean nextBoolean() {
                if (first) {
                    first = false;
                    return true;
                }
                return super.nextBoolean();
            }
        };
    }
}
