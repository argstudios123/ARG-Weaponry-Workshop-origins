package com.arg_studios.arg_weaponry.client.recoil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * How the recoil delta reaches the player's view: added to the angles the player has <em>at that moment</em>, never to a
 * remembered base orientation. This is the camera-jitter fix expressed as a contract - the numbers below are the
 * difference between "the mouse works during recoil" and "the view answers at 20 Hz".
 */
class ViewAnglesTest {

    private static final float TOLERANCE = 1.0E-4F;

    @Test
    void aDeltaIsAddedToTheLiveAnglesThePlayerHasRightNow() {
        ViewAngles live = new ViewAngles(10.0F, 30.0F);

        ViewAngles afterRecoil = live.plus(new PlayerRecoilController.Delta(2.0, -0.02));

        // A positive delta pitch means the aim rises, which is a decrease of Minecraft's pitch.
        assertEquals(8.0F, afterRecoil.pitchDegrees(), TOLERANCE);
        assertEquals(29.98F, afterRecoil.yawDegrees(), TOLERANCE);
    }

    @Test
    void mouseMovementBetweenTwoTicksSurvivesTheRecoil() {
        PlayerRecoilController.Delta kick = new PlayerRecoilController.Delta(2.0, 0.0);
        ViewAngles afterFirstTick = new ViewAngles(10.0F, 30.0F).plus(kick);
        assertEquals(8.0F, afterFirstTick.pitchDegrees(), TOLERANCE);

        // Between the two ticks the player pulls the mouse down by five degrees.
        ViewAngles afterMouseMove = new ViewAngles(afterFirstTick.pitchDegrees() + 5.0F, afterFirstTick.yawDegrees());
        ViewAngles afterSecondTick = afterMouseMove.plus(new PlayerRecoilController.Delta(-1.0, 0.0));

        assertEquals(14.0F, afterSecondTick.pitchDegrees(), TOLERANCE,
                "the five degrees of mouse movement plus the one degree of recovery");
        assertEquals(6.0F, afterSecondTick.pitchDegrees() - afterFirstTick.pitchDegrees(), TOLERANCE,
                "the player's own movement must be preserved, not overwritten by a stored angle");
    }

    @Test
    void theResultDependsOnlyOnTheLiveAnglesAndTheDelta() {
        PlayerRecoilController.Delta delta = new PlayerRecoilController.Delta(2.5, 0.02);

        ViewAngles fromTen = new ViewAngles(10.0F, 30.0F).plus(delta);
        ViewAngles fromForty = new ViewAngles(40.0F, 30.0F).plus(delta);

        assertEquals(30.0F, fromForty.pitchDegrees() - fromTen.pitchDegrees(), TOLERANCE,
                "there is no hidden base orientation: two different live views differ exactly as they did before");
        assertEquals(fromTen.yawDegrees(), fromForty.yawDegrees(), TOLERANCE);
    }

    @Test
    void pitchStaysInsideVanillasOwnLimits() {
        assertEquals(ViewAngles.MAX_PITCH_DEGREES,
                new ViewAngles(85.0F, 0.0F).plus(new PlayerRecoilController.Delta(-20.0, 0.0)).pitchDegrees(),
                TOLERANCE, "no recoil configuration may push the view past straight down");
        assertEquals(ViewAngles.MIN_PITCH_DEGREES,
                new ViewAngles(-85.0F, 0.0F).plus(new PlayerRecoilController.Delta(20.0, 0.0)).pitchDegrees(),
                TOLERANCE, "no recoil configuration may push the view past straight up");
    }

    @Test
    void yawWrapsTheWayVanillaWrapsIt() {
        assertEquals(-176.0F, new ViewAngles(0.0F, 179.0F).plus(new PlayerRecoilController.Delta(0.0, 5.0))
                .yawDegrees(), TOLERANCE);
        assertEquals(176.0F, new ViewAngles(0.0F, -179.0F).plus(new PlayerRecoilController.Delta(0.0, -5.0))
                .yawDegrees(), TOLERANCE);
        assertEquals(-180.0F, ViewAngles.wrapDegrees(180.0F), TOLERANCE);
        assertEquals(-180.0F, ViewAngles.wrapDegrees(540.0F), TOLERANCE);
        assertEquals(-180.0F, ViewAngles.wrapDegrees(-180.0F), TOLERANCE);
    }

    @Test
    void aZeroDeltaLeavesTheAnglesExactlyAsTheyAre() {
        ViewAngles live = new ViewAngles(12.5F, -170.0F);

        assertEquals(live, live.plus(PlayerRecoilController.Delta.NONE));
        assertEquals(live, live.plus(null));
    }

    @Test
    void aNonFiniteDeltaIsIgnored() {
        ViewAngles live = new ViewAngles(12.5F, 40.0F);

        ViewAngles next = live.plus(new PlayerRecoilController.Delta(Double.NaN, 0.0));

        assertEquals(live, next, "an invalid delta must not corrupt the player's view");
    }
}
