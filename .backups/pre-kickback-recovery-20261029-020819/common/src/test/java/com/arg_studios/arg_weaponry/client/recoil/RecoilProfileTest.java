package com.arg_studios.arg_weaponry.client.recoil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The JSON-facing contract of the recoil block: the property names and their meaning, "absent" versus "configured zero",
 * and the fact that screen shake, player recoil and weapon kickback stay independent of one another.
 */
class RecoilProfileTest {

    /** The configuration this task is verifying, verbatim (kickback deliberately absent, i.e. commented out). */
    private static final WeaponDefinition.Recoil CONFIGURED =
            new WeaponDefinition.Recoil(null, 0.8, 0.5, 2.5, null, 0.02, 0.02);

    @Test
    void theAuthoredConfigurationResolvesAsWritten() {
        RecoilProfile profile = RecoilProfile.of(CONFIGURED, null);

        assertTrue(profile.hasShake(), "screen_shake_Recoil_value 0.8 must enable the shake");
        assertEquals(0.8, profile.shakeDegrees());
        assertEquals(0.5, profile.shakeSpeed(), "screen_shake_recoil_speed must be kept as authored");
        assertEquals(2.5, profile.verticalDegrees());
        assertEquals(0.02, profile.horizontalLeftDegrees());
        assertEquals(0.02, profile.horizontalRightDegrees());
        assertFalse(profile.hasKickback(), "screen shake must never switch weapon kickback on");
    }

    @Test
    void anAbsentAmplitudeDisablesScreenShake() {
        RecoilProfile profile = RecoilProfile.of(
                new WeaponDefinition.Recoil(null, null, 0.5, 2.5, null, 0.02, 0.02), null);

        assertFalse(profile.hasShake(), "an absent screen_shake_Recoil_value must disable the shake");
        assertEquals(0.0, profile.shakeDegrees());
        assertTrue(ViewShakeResponse.forShot(profile.shakeDegrees(), profile.shakeSpeed(), new Random(1L)).isZero(),
                "no response may be built from an absent amplitude");
    }

    @Test
    void aZeroAmplitudeDisablesScreenShake() {
        RecoilProfile profile = RecoilProfile.of(
                new WeaponDefinition.Recoil(null, 0.0, 0.5, 2.5, null, 0.02, 0.02), null);

        assertFalse(profile.hasShake(), "a configured 0.0 must disable the shake");
        ViewShakeResponse response = ViewShakeResponse.forShot(profile.shakeDegrees(), profile.shakeSpeed(),
                new Random(1L));
        assertTrue(response.isZero());
        assertEquals(0.0, response.yawAt(0.1));
        assertEquals(0.0, response.rollAt(0.1));
    }

    @Test
    void anAbsentSpeedFallsBackToTheReferenceDefault() {
        RecoilProfile profile = RecoilProfile.of(
                new WeaponDefinition.Recoil(null, 0.8, null, 2.5, null, 0.02, 0.02), null);

        assertTrue(profile.hasShake());
        assertEquals(RecoilProfile.DEFAULT_SHAKE_SPEED, profile.shakeSpeed(),
                "an absent speed must use the documented default, never zero");
        assertEquals(8.0, RecoilProfile.DEFAULT_SHAKE_SPEED, "the default is Point Blank's shakeRecoilSpeed");
        ViewShakeResponse response = ViewShakeResponse.forShot(profile.shakeDegrees(), profile.shakeSpeed(),
                new Random(1L));
        assertFalse(response.isZero(), "amplitude 0.8 with the default speed must still produce a shake");
        assertEquals(8.0, response.speed());
    }

    @Test
    void aNonPositiveOrExcessiveSpeedFallsBackOrClamps() {
        assertEquals(RecoilProfile.DEFAULT_SHAKE_SPEED,
                RecoilProfile.of(new WeaponDefinition.Recoil(null, 0.8, 0.0, 2.5, null, null, null), null)
                        .shakeSpeed());
        assertEquals(RecoilProfile.DEFAULT_SHAKE_SPEED,
                RecoilProfile.of(new WeaponDefinition.Recoil(null, 0.8, -3.0, 2.5, null, null, null), null)
                        .shakeSpeed());
        assertEquals(RecoilProfile.MAX_SHAKE_SPEED,
                RecoilProfile.of(new WeaponDefinition.Recoil(null, 0.8, 1.0E9, 2.5, null, null, null), null)
                        .shakeSpeed());
    }

    @Test
    void missingKickbackAndShakeSettingsStayIndependent() {
        RecoilProfile shakeOnly = RecoilProfile.of(
                new WeaponDefinition.Recoil(null, 0.8, 0.5, 0.0, null, null, null), null);
        assertTrue(shakeOnly.hasShake());
        assertFalse(shakeOnly.hasKickback(), "no kickback property means no kickback, whatever the shake says");
        assertFalse(shakeOnly.hasViewRecoil());

        RecoilProfile kickbackOnly = RecoilProfile.of(
                new WeaponDefinition.Recoil(0.06, null, null, 0.0, null, null, null), null);
        assertTrue(kickbackOnly.hasKickback(), "kickback stays independently optional and functional");
        assertFalse(kickbackOnly.hasShake(), "kickback must never switch screen shake on");

        RecoilProfile nothing = RecoilProfile.of((WeaponDefinition.Recoil) null, null);
        assertEquals(RecoilProfile.NONE, nothing);
        assertTrue(nothing.isNeutral());

        assertEquals(RecoilProfile.NONE, RecoilProfile.of((WeaponDefinition) null));
    }

    @Test
    void playerRecoilKeepsWorkingWithoutScreenShake() {
        RecoilProfile profile = RecoilProfile.of(
                new WeaponDefinition.Recoil(null, null, null, 2.5, null, 0.02, 0.02), null);

        assertFalse(profile.hasShake(), "no amplitude means no screen shake");
        assertTrue(profile.hasViewRecoil(), "vertical and horizontal recoil must be unaffected");
        assertEquals(2.5, profile.verticalDegrees());
        assertEquals(0.02, profile.horizontalLeftDegrees());
        assertEquals(0.02, profile.horizontalRightDegrees());
    }

    @Test
    void amplitudeAndSpeedAreClampedToTheDocumentedCaps() {
        RecoilProfile profile = RecoilProfile.of(
                new WeaponDefinition.Recoil(null, 100.0, 1.0E9, 0.0, null, null, null), null);

        assertEquals(RecoilProfile.MAX_SHAKE_DEGREES, profile.shakeDegrees());
        assertEquals(RecoilProfile.MAX_SHAKE_SPEED, profile.shakeSpeed());

        ViewShakeResponse response = ViewShakeResponse.forShot(profile.shakeDegrees(), profile.shakeSpeed(),
                new Random(2L));
        assertTrue(Math.abs(response.yawAt(0.05)) <= ViewShakeResponse.MAX_AMPLITUDE_DEGREES);
        assertTrue(Math.abs(response.rollAt(0.05)) <= ViewShakeResponse.MAX_AMPLITUDE_DEGREES);
    }
}
