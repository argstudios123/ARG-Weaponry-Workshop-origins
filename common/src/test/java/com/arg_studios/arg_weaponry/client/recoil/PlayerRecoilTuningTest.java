package com.arg_studios.arg_weaponry.client.recoil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The four tuning properties, as the existing controller applies them: {@code smoothing} shapes how gradually the target
 * offset reaches the player, {@code recovery_speed} stretches or shortens the existing recovery curve, and the two stance
 * multipliers scale the impulse before the controller ever sees it.
 *
 * <p>Everything here drives the real controller the way the game does - one 50 ms client tick at a time, with the M100
 * DMR's own values ({@code vertical_recoil} 2.5, 0.02 per horizontal direction, 300 RPM).</p>
 */
class PlayerRecoilTuningTest {

    private static final double TICK_SECONDS = 0.05;

    private static final int TICKS_PER_SHOT = 4;

    private static final double IMPULSE = 2.5;

    // ---------------------------------------------------------------- smoothing

    @Test
    void theDefaultSmoothingHandsTheWholeDifferenceOverAtOnce() {
        PlayerRecoilController controller = new PlayerRecoilController();

        controller.onShot(shot(profile(PlayerRecoilController.DEFAULT_SMOOTHING, RecoilProfile.DEFAULT_RECOVERY_SPEED)));
        assertEquals(PlayerRecoilController.DEFAULT_SMOOTHING, controller.smoothing());
        assertEquals(PlayerRecoilController.RECOVERY_SECONDS, controller.recoverySeconds(), 1.0E-12);

        controller.tick(TICK_SECONDS);

        assertEquals(controller.pitchOffset(), controller.appliedPitch(), 1.0E-12,
                "with smoothing 1.0 the applied offset is the target exactly - the pre-tuning behaviour");
        assertTrue(controller.appliedPitch() > 2.4, "the shot is still delivered to the aim");
    }

    @Test
    void halfSmoothingAppliesHalfTheRemainingDifferenceInFiftyMilliseconds() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(new ViewRecoilResponse(IMPULSE, 0.0, 0.5, PlayerRecoilController.RECOVERY_SECONDS));

        controller.tick(0.05);
        double firstTarget = controller.pitchOffset();
        assertEquals(0.5 * firstTarget, controller.appliedPitch(), 1.0E-9);

        double appliedAfterFirst = controller.appliedPitch();
        controller.tick(0.05);
        double secondTarget = controller.pitchOffset();
        assertEquals(appliedAfterFirst + (secondTarget - appliedAfterFirst) * 0.5, controller.appliedPitch(), 1.0E-9,
                "the second step applies half of what is left after the first");
    }

    @Test
    void theSmoothingShareIsNormalisedToTheElapsedTime() {
        assertEquals(0.5, PlayerRecoilController.smoothingShare(0.5, 0.05), 1.0E-12,
                "0.5 means 50 % in the 50 ms reference interval");
        assertEquals(1.0, PlayerRecoilController.smoothingShare(1.0, 0.05), 0.0);
        assertEquals(0.0, PlayerRecoilController.smoothingShare(0.5, 0.0), 0.0);
        assertEquals(0.0, PlayerRecoilController.smoothingShare(0.5, Double.NaN), 0.0);

        double halfStep = PlayerRecoilController.smoothingShare(0.5, 0.025);
        assertEquals(0.5, 1.0 - (1.0 - halfStep) * (1.0 - halfStep), 1.0E-12,
                "two half steps must compose into exactly one full step");
        assertTrue(halfStep > 0.0 && halfStep < 0.5, "half a step applies less than a whole one");

        // Heavier smoothing means a smaller share; 1 % is the slowest accepted value and never zero.
        assertTrue(PlayerRecoilController.smoothingShare(0.2, 0.05) < PlayerRecoilController.smoothingShare(0.5, 0.05));
        assertTrue(PlayerRecoilController.smoothingShare(0.0, 0.05) > 0.0, "a clamped 0 can never freeze the camera");
    }

    @Test
    void theSameElapsedTimeGivesTheSameSmoothedResultAtAnyTickRate() {
        // recovery_speed 0.1 keeps the target practically constant over the 100 ms being compared, so the only variable is
        // how the smoothing is stepped.
        double oneStep = appliedAfterSmoothing(0.1, 1);
        double twoSteps = appliedAfterSmoothing(0.05, 2);
        double fourSteps = appliedAfterSmoothing(0.025, 4);

        assertEquals(oneStep, twoSteps, 5.0E-3, "two 50 ms ticks must land where one 100 ms tick does");
        assertEquals(oneStep, fourSteps, 5.0E-3, "and so must four 25 ms ticks");
    }

    @Test
    void smoothedRecoilNeverOvershootsAndTurnsAroundOnlyOnce() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(shot(profile(0.4, RecoilProfile.DEFAULT_RECOVERY_SPEED)));

        double previous = 0.0;
        boolean turnedAround = false;
        for (int tick = 0; tick < 60 && controller.isActive(); tick++) {
            controller.tick(TICK_SECONDS);
            double applied = controller.appliedPitch();
            assertTrue(applied >= 0.0, "the smoothed aim may never cross neutral");
            assertTrue(applied <= IMPULSE + 1.0E-9, "smoothing can never overshoot the impulse, was " + applied);
            if (applied < previous) {
                turnedAround = true;
            } else if (turnedAround) {
                assertTrue(applied <= previous + 1.0E-12,
                        "once the smoothed aim starts coming down it must keep coming down, was " + applied);
            }
            previous = applied;
        }
        assertTrue(turnedAround, "the smoothed aim must come back down after the shot");
    }

    @Test
    void smoothingStillReturnsTheAimToExactlyZero() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(new ViewRecoilResponse(IMPULSE, 0.02, 0.5, PlayerRecoilController.RECOVERY_SECONDS));

        double totalPitch = 0.0;
        int ticks = 0;
        while (controller.isActive() && ticks++ < 400) {
            totalPitch += controller.tick(TICK_SECONDS).pitchDegrees();
        }

        assertEquals(0.0, controller.pitchOffset(), "the accumulated offset is exactly neutral");
        assertEquals(0.0, controller.appliedPitch(), "and so is what reached the player - no residual rotation");
        assertEquals(0.0, controller.yawOffset());
        assertEquals(0.0, controller.appliedYaw());
        assertEquals(0.0, totalPitch, 1.0E-9, "the deltas still sum to nothing, smoothing or not");
        assertFalse(controller.isActive());

        double seconds = ticks * TICK_SECONDS;
        assertTrue(seconds >= PlayerRecoilController.RECOVERY_SECONDS - TICK_SECONDS,
                "smoothing must not shorten the recovery, was " + seconds);
        assertTrue(seconds <= PlayerRecoilController.RECOVERY_SECONDS + 0.4,
                "the smoothed tail must stay short, was " + seconds);
    }

    @Test
    void aShotDuringRecoveryKeepsWhatIsStillOutstandingWhenSmoothing() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(shot(profile(0.5, RecoilProfile.DEFAULT_RECOVERY_SPEED)));
        for (int tick = 0; tick < TICKS_PER_SHOT; tick++) {
            controller.tick(TICK_SECONDS);
        }
        double appliedBefore = controller.appliedPitch();
        double targetBefore = controller.pitchOffset();

        controller.onShot(shot(profile(0.5, RecoilProfile.DEFAULT_RECOVERY_SPEED)));

        assertTrue(controller.pitchOffset() > targetBefore, "the new impulse adds to what is still outstanding");
        assertTrue(controller.pitchOffset() > IMPULSE,
                "a shot during recovery must not reset the accumulated recoil to one impulse");
        assertEquals(appliedBefore, controller.appliedPitch(), 1.0E-12,
                "the applied offset only moves on a tick, so the new shot cannot make the camera jump");
    }

    @Test
    void repeatedSamplesNeverAdvanceTheSmoothedState() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(new ViewRecoilResponse(IMPULSE, 0.02, 0.5, PlayerRecoilController.RECOVERY_SECONDS));
        controller.tick(TICK_SECONDS);

        double applied = controller.appliedPitch();
        double offset = controller.pitchOffset();
        for (int repeat = 0; repeat < 200; repeat++) {
            assertTrue(controller.tick(0.0).isZero());
            assertTrue(controller.tick(-1.0).isZero());
            assertTrue(controller.tick(Double.NaN).isZero());
        }
        assertEquals(applied, controller.appliedPitch(), 0.0, "sampling again must not smooth anything further");
        assertEquals(offset, controller.pitchOffset(), 0.0);
    }

    // ---------------------------------------------------------------- recovery speed

    @Test
    void slowBaselineAndFastRecoveryKeepTheirDocumentedDurations() {
        assertEquals(PlayerRecoilController.RECOVERY_SECONDS, recoverySeconds(1.0), 0.06,
                "recovery_speed 1.0 is the existing 0.8 s baseline");
        assertEquals(PlayerRecoilController.RECOVERY_SECONDS / 0.5, recoverySeconds(0.5), 0.06,
                "slower than the baseline takes twice as long");
        assertEquals(PlayerRecoilController.RECOVERY_SECONDS / 2.0, recoverySeconds(2.0), 0.06,
                "faster than the baseline takes half as long");
        assertEquals(PlayerRecoilController.RECOVERY_SECONDS / 0.15, recoverySeconds(0.15), 0.06,
                "recovery_speed 0.15 takes deliberately long - about 5.3 s");
    }

    @Test
    void recoverySpeedOnlyChangesTheTimingOfOneShotNotItsImpulse() {
        double baseline = impulseOfOneShot(1.0);
        double slow = impulseOfOneShot(0.15);
        double fast = impulseOfOneShot(2.0);

        assertEquals(IMPULSE, baseline, 1.0E-12, "the shot lands with the configured impulse");
        assertEquals(baseline, slow, 0.0, "the recovery speed may not change the impulse of a shot");
        assertEquals(baseline, fast, 0.0);
    }

    @Test
    void sustainedFireStaysBoundedAtEveryRecoverySpeed() {
        double slow = burstOffset(0.15);
        double baseline = burstOffset(1.0);
        double fast = burstOffset(2.0);

        assertTrue(slow <= PlayerRecoilController.MAX_TOTAL_DEGREES);
        assertTrue(baseline <= PlayerRecoilController.MAX_TOTAL_DEGREES);
        assertTrue(fast <= PlayerRecoilController.MAX_TOTAL_DEGREES);
        assertTrue(slow >= baseline, "a slower recovery must never recover more between shots, was " + slow);
        assertTrue(fast <= baseline, "a faster recovery must never recover less between shots, was " + fast);
        assertTrue(fast > 0.0, "even the fastest recovery still climbs while the trigger is held");
    }

    @Test
    void aSlowRecoveryDoesNotLoseAccumulatedRecoilOnASuccessiveShot() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(shot(profile(1.0, 0.15)));
        for (int tick = 0; tick < TICKS_PER_SHOT; tick++) {
            controller.tick(TICK_SECONDS);
        }
        double afterFirstShot = controller.pitchOffset();

        controller.onShot(shot(profile(1.0, 0.15)));

        assertTrue(controller.pitchOffset() > afterFirstShot + 2.0,
                "the second shot keeps nearly all of the first one at this rate, was " + controller.pitchOffset());
    }

    // ---------------------------------------------------------------- stance multipliers

    @Test
    void aimingAndCrouchingScaleTheImpulseTheControllerReceives() {
        RecoilProfile tuned = profile(PlayerRecoilController.DEFAULT_SMOOTHING, RecoilProfile.DEFAULT_RECOVERY_SPEED, 0.6,
                0.8);

        PlayerRecoilController standing = new PlayerRecoilController();
        standing.onShot(tuned.viewRecoilImpulse(null, false, false));
        assertEquals(IMPULSE, standing.pitchOffset(), 1.0E-12, "hip fire standing keeps the configured value");

        PlayerRecoilController aiming = new PlayerRecoilController();
        aiming.onShot(tuned.viewRecoilImpulse(null, true, false));
        assertEquals(IMPULSE * 0.6, aiming.pitchOffset(), 1.0E-12, "aiming applies 60 % of the camera impulse");

        PlayerRecoilController crouching = new PlayerRecoilController();
        crouching.onShot(tuned.viewRecoilImpulse(null, false, true));
        assertEquals(IMPULSE * 0.8, crouching.pitchOffset(), 1.0E-12, "crouching applies 80 % of the camera impulse");

        PlayerRecoilController both = new PlayerRecoilController();
        both.onShot(tuned.viewRecoilImpulse(null, true, true));
        assertEquals(IMPULSE * 0.6 * 0.8, both.pitchOffset(), 1.0E-12,
                "aiming and crouching multiply, they do not add");
    }

    @Test
    void theStanceMultipliersDoNotChangeTheRecoveryOrTheSmoothing() {
        RecoilProfile tuned = profile(0.5, 2.0, 0.6, 0.8);

        ViewRecoilResponse standing = tuned.viewRecoilImpulse(null, false, false);
        ViewRecoilResponse aiming = tuned.viewRecoilImpulse(null, true, true);

        assertEquals(standing.smoothing(), aiming.smoothing());
        assertEquals(standing.recoverySeconds(), aiming.recoverySeconds());
        assertEquals(standing.yawDegrees() * 0.6 * 0.8, aiming.yawDegrees(), 1.0E-12,
                "the horizontal impulse is scaled by exactly the same factor as the vertical one");
    }

    @Test
    void mouseLookStaysLiveThroughSmoothingAndMultipliers() {
        RecoilProfile tuned = profile(0.5, RecoilProfile.DEFAULT_RECOVERY_SPEED, 0.6, 0.8);
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(tuned.viewRecoilImpulse(null, true, true));

        ViewAngles live = new ViewAngles(10.0F, 30.0F);
        live = live.plus(controller.tick(TICK_SECONDS));
        // The player pulls the mouse down four degrees between two ticks.
        live = new ViewAngles(live.pitchDegrees() + 4.0F, live.yawDegrees());

        int ticks = 1;
        while (controller.isActive() && ticks++ < 400) {
            live = live.plus(controller.tick(TICK_SECONDS));
        }

        assertEquals(14.0F, live.pitchDegrees(), 1.0E-4,
                "the recoil is fully returned, so the aim is exactly where the player's mouse left it");
        assertEquals(30.0F, live.yawDegrees(), 1.0E-4);
    }

    @Test
    void theControllerRemembersTheTuningOfTheLastShotAndForgetsItOnReset() {
        PlayerRecoilController controller = new PlayerRecoilController();
        assertEquals(PlayerRecoilController.DEFAULT_SMOOTHING, controller.smoothing());
        assertEquals(PlayerRecoilController.RECOVERY_SECONDS, controller.recoverySeconds(), 0.0);

        controller.onShot(new ViewRecoilResponse(IMPULSE, 0.02, 0.25, 2.0));
        assertEquals(0.25, controller.smoothing(), 0.0);
        assertEquals(2.0, controller.recoverySeconds(), 0.0);

        controller.reset();
        assertEquals(PlayerRecoilController.DEFAULT_SMOOTHING, controller.smoothing());
        assertEquals(PlayerRecoilController.RECOVERY_SECONDS, controller.recoverySeconds(), 0.0);
        assertFalse(controller.isActive());
    }

    // ---------------------------------------------------------------- helpers

    private static RecoilProfile profile(double smoothing, double recoverySpeed) {
        return profile(smoothing, recoverySpeed, RecoilProfile.DEFAULT_AIM_MULTIPLIER,
                RecoilProfile.DEFAULT_CROUCH_MULTIPLIER);
    }

    @Test
    void smoothingAndASlowRecoveryCombineWithoutBreakingTheBounds() {
        RecoilProfile tuned = profile(0.5, 0.15);
        PlayerRecoilController controller = new PlayerRecoilController();
        double previousApplied = 0.0;

        for (int round = 0; round < 25; round++) {
            controller.onShot(tuned.viewRecoilImpulse(null, false, false));
            for (int tick = 0; tick < TICKS_PER_SHOT; tick++) {
                controller.tick(TICK_SECONDS);
                assertTrue(controller.pitchOffset() <= PlayerRecoilController.MAX_TOTAL_DEGREES,
                        "smoothing and a slow recovery must still stay bounded, round " + round);
                assertTrue(controller.appliedPitch() >= 0.0);
                assertTrue(controller.appliedPitch() <= controller.pitchOffset() + 0.5,
                        "the smoothed value may lag the target but never run away from it");
                previousApplied = controller.appliedPitch();
            }
        }
        assertTrue(previousApplied > IMPULSE, "the burst must still climb with smoothing enabled");

        int ticks = 0;
        while (controller.isActive() && ticks++ < 1000) {
            controller.tick(TICK_SECONDS);
        }
        assertEquals(0.0, controller.pitchOffset());
        assertEquals(0.0, controller.appliedPitch(), "even together, the two settings land exactly on zero");
        assertFalse(controller.isActive());
    }

    @Test
    void aZeroScaledImpulseLeavesAnExistingResponseAlone() {
        RecoilProfile tuned = profile(1.0, RecoilProfile.DEFAULT_RECOVERY_SPEED, 0.0,
                RecoilProfile.DEFAULT_CROUCH_MULTIPLIER);
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(shot(profile(1.0, RecoilProfile.DEFAULT_RECOVERY_SPEED)));
        controller.tick(TICK_SECONDS);
        double appliedBefore = controller.appliedPitch();

        ViewRecoilResponse aimingShot = tuned.viewRecoilImpulse(null, true, false);
        assertTrue(aimingShot.isZero(), "aim_multiplier 0 makes the aiming shot move nothing");
        controller.onShot(aimingShot);

        assertEquals(appliedBefore, controller.appliedPitch(), 0.0,
                "a shot that adds nothing must not disturb what is already recovering");
        assertTrue(controller.tick(TICK_SECONDS).pitchDegrees() < 0.0, "and the recovery keeps going");
    }

    /**
     * @param smoothing        the configured {@code smoothing}
     * @param recoverySpeed    the configured {@code recovery_speed}
     * @param aimMultiplier    the configured {@code aim_multiplier}
     * @param crouchMultiplier the configured {@code crouch_multiplier}
     * @return a profile with the reference weapon's impulse and kickback plus those four settings
     */
    private static RecoilProfile profile(double smoothing, double recoverySpeed, double aimMultiplier,
                                         double crouchMultiplier) {
        return new RecoilProfile(0.06, true, IMPULSE, 0.02, 0.02, smoothing, recoverySpeed, aimMultiplier,
                crouchMultiplier, RecoilProfile.Sustained.NONE);
    }

    /**
     * @param profile the weapon
     * @return one shot's camera impulse at that weapon's configured rates, hip fire and standing
     */
    private static ViewRecoilResponse shot(RecoilProfile profile) {
        return profile.viewRecoilImpulse(null, false, false);
    }

    /**
     * @param stepSeconds   the tick length
     * @param steps         how many ticks to run
     * @return the applied pitch after one shot and those ticks, with smoothing 0.5 and a very slow recovery
     */
    private static double appliedAfterSmoothing(double stepSeconds, int steps) {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(new ViewRecoilResponse(IMPULSE, 0.0, 0.5, PlayerRecoilController.RECOVERY_SECONDS / 0.1));
        for (int step = 0; step < steps; step++) {
            controller.tick(stepSeconds);
        }
        return controller.appliedPitch();
    }

    /**
     * @param recoverySpeed the configured speed
     * @return the time from the shot until the accumulated offset is exactly neutral, in seconds
     */
    private static double recoverySeconds(double recoverySpeed) {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(shot(profile(PlayerRecoilController.DEFAULT_SMOOTHING, recoverySpeed)));
        int ticks = 0;
        while (controller.isActive() && ticks < 1000) {
            controller.tick(TICK_SECONDS);
            ticks++;
        }
        assertFalse(controller.isActive(), "the recovery must finish");
        return ticks * TICK_SECONDS;
    }

    /**
     * @param recoverySpeed the configured speed
     * @return the accumulated pitch offset immediately after the shot, before any tick
     */
    private static double impulseOfOneShot(double recoverySpeed) {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(shot(profile(PlayerRecoilController.DEFAULT_SMOOTHING, recoverySpeed)));
        return controller.pitchOffset();
    }

    /**
     * @param recoverySpeed the configured speed
     * @return the accumulated pitch offset after twenty rounds at the weapon's 300 RPM
     */
    private static double burstOffset(double recoverySpeed) {
        PlayerRecoilController controller = new PlayerRecoilController();
        for (int round = 0; round < 20; round++) {
            controller.onShot(shot(profile(PlayerRecoilController.DEFAULT_SMOOTHING, recoverySpeed)));
            for (int tick = 0; tick < TICKS_PER_SHOT; tick++) {
                controller.tick(TICK_SECONDS);
            }
        }
        return controller.pitchOffset();
    }
}
