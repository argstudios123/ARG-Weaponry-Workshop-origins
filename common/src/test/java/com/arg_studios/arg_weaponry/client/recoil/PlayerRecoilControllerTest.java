package com.arg_studios.arg_weaponry.client.recoil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The player-camera recoil: the impulse of one accepted shot, accumulation during a burst, saturation, and a recovery that
 * reaches exactly neutral over a documented interval - driven the way the game drives it (one client tick every 50 ms).
 *
 * <p>The numbers are the M100 DMR's real configuration: {@code vertical_recoil} 2.5 and 0.02 for each horizontal
 * direction, fired at its 300 RPM, i.e. one shot every four ticks.</p>
 */
class PlayerRecoilControllerTest {

    private static final double TICK_SECONDS = 0.05;

    private static final int TICKS_PER_SHOT = 4;

    /** The configured impulse of one shot of the reference weapon: 2.5 degrees up, 0.02 degrees right. */
    private static final ViewRecoilResponse IMPULSE = new ViewRecoilResponse(2.5, 0.02);

    // ---------------------------------------------------------------- one shot

    @Test
    void aShotRaisesTheAccumulatedOffsetByTheConfiguredImpulse() {
        PlayerRecoilController controller = new PlayerRecoilController();

        controller.onShot(IMPULSE);

        assertEquals(2.5, controller.pitchOffset(), 1.0E-12, "vertical_recoil is the per-shot impulse, in degrees");
        assertEquals(0.02, controller.yawOffset(), 1.0E-12, "the horizontal value is the per-shot impulse too");
        assertEquals(0.0, controller.appliedPitch(), "nothing has been handed to the player before the tick runs");
        assertTrue(controller.isActive());
    }

    @Test
    void theNextTickHandsTheKickToThePlayerAndAppliesItOnce() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(IMPULSE);

        PlayerRecoilController.Delta delta = controller.tick(TICK_SECONDS);

        assertTrue(delta.pitchDegrees() > 2.4, "the shot must be delivered to the aim, was " + delta.pitchDegrees());
        assertTrue(delta.pitchDegrees() <= 2.5, "one shot can never add more than its configured impulse");
        assertTrue(delta.yawDegrees() > 0.0, "a rightward impulse must turn the aim right");
        assertEquals(delta.pitchDegrees(), controller.appliedPitch(), 1.0E-12,
                "the bookkeeping records exactly what was handed over");
        assertEquals(2.5 * PlayerRecoilController.remainingShare(TICK_SECONDS / PlayerRecoilController.RECOVERY_SECONDS),
                controller.pitchOffset(), 1.0E-12,
                "the offset is the impulse times the recovery curve - no bump, no oscillation");
    }

    @Test
    void horizontalRecoilKeepsTheConfiguredLeftAndRightDirections() {
        PlayerRecoilController left = new PlayerRecoilController();
        left.onShot(new ViewRecoilResponse(0.0, -0.02));
        assertTrue(left.tick(TICK_SECONDS).yawDegrees() < 0.0, "a negative horizontal impulse turns the aim left");

        PlayerRecoilController right = new PlayerRecoilController();
        right.onShot(new ViewRecoilResponse(0.0, 0.02));
        assertTrue(right.tick(TICK_SECONDS).yawDegrees() > 0.0, "a positive horizontal impulse turns the aim right");

        // Asymmetric magnitudes stay asymmetric: this is why the two JSON fields are not collapsed into one.
        PlayerRecoilController asymmetricLeft = new PlayerRecoilController();
        asymmetricLeft.onShot(new ViewRecoilResponse(0.0, -0.05));
        PlayerRecoilController asymmetricRight = new PlayerRecoilController();
        asymmetricRight.onShot(new ViewRecoilResponse(0.0, 0.02));
        assertEquals(2.5, Math.abs(asymmetricLeft.tick(TICK_SECONDS).yawDegrees())
                / Math.abs(asymmetricRight.tick(TICK_SECONDS).yawDegrees()), 1.0E-9);
    }

    @Test
    void aZeroOrAbsentImpulseNeverMovesTheAim() {
        PlayerRecoilController controller = new PlayerRecoilController();

        controller.onShot(null);
        controller.onShot(ViewRecoilResponse.NONE);
        controller.onShot(new ViewRecoilResponse(0.0, 0.0));

        assertEquals(0.0, controller.pitchOffset());
        assertEquals(0.0, controller.yawOffset());
        assertTrue(controller.tick(TICK_SECONDS).isZero());
        assertFalse(controller.isActive());
    }

    // ---------------------------------------------------------------- sustained fire

    @Test
    void successiveShotsAccumulateInsteadOfReplacingEachOther() {
        PlayerRecoilController controller = new PlayerRecoilController();

        double afterOneShot = fire(controller, 1);
        double afterTwoShots = fire(controller, 1);
        double afterThreeShots = fire(controller, 1);

        assertTrue(afterTwoShots > afterOneShot, "the second shot of a burst must climb further");
        assertTrue(afterThreeShots > afterTwoShots, "the third shot of a burst must climb further");
        assertTrue(afterThreeShots > 2.5, "a three-round burst must climb past a single impulse, was " + afterThreeShots);
        assertTrue(afterThreeShots < 3 * 2.5, "a burst must never simply sum its impulses forever");
    }

    @Test
    void theImpulseOfAShotShrinksAsTheOffsetApproachesTheCap() {
        PlayerRecoilController controller = new PlayerRecoilController();

        double previousIncrement = Double.MAX_VALUE;
        double previousOffset = 0.0;
        for (int shot = 0; shot < 12; shot++) {
            controller.onShot(IMPULSE);
            double offset = controller.pitchOffset();
            double increment = offset - previousOffset;
            assertTrue(increment > 0.0, "every shot must still add something, shot " + shot);
            assertTrue(increment <= 2.5, "no shot may add more than the configured impulse");
            assertTrue(increment < previousIncrement, "the addition must saturate, shot " + shot);
            previousIncrement = increment;
            previousOffset = offset;
        }
    }

    @Test
    void sustainedFireSaturatesInsteadOfSummingAnglesForever() {
        PlayerRecoilController controller = new PlayerRecoilController();

        double offset = 0.0;
        for (int shot = 0; shot < 400; shot++) {
            controller.onShot(IMPULSE);
            for (int tick = 0; tick < TICKS_PER_SHOT; tick++) {
                controller.tick(TICK_SECONDS);
            }
            offset = controller.pitchOffset();
            assertTrue(offset <= PlayerRecoilController.MAX_TOTAL_DEGREES,
                    "the accumulated offset must stay bounded, shot " + shot + " reached " + offset);
            assertTrue(Math.abs(controller.appliedPitch()) <= PlayerRecoilController.MAX_TOTAL_DEGREES,
                    "nothing beyond the cap may ever reach the player's aim");
        }
        assertTrue(offset > 0.0, "sustained fire must still raise the aim");
        assertNotEquals(0.0, controller.appliedPitch(), "sustained fire must still be applied to the aim");
    }

    @Test
    void continuousFireNeverJumpsAndNeverPushesTheAimBelowNeutral() {
        PlayerRecoilController controller = new PlayerRecoilController();
        double previousApplied = 0.0;
        double largestStep = 0.0;

        // Twelve rounds at 300 RPM, sampled after every 50 ms tick.
        for (int shot = 0; shot < 12; shot++) {
            controller.onShot(IMPULSE);
            for (int tick = 0; tick < TICKS_PER_SHOT; tick++) {
                PlayerRecoilController.Delta delta = controller.tick(TICK_SECONDS);
                double applied = controller.appliedPitch();
                largestStep = Math.max(largestStep, Math.abs(applied - previousApplied));
                previousApplied = applied;
                assertTrue(applied >= 0.0, "recoil must never push the aim below the neutral offset");
                assertTrue(Math.abs(delta.pitchDegrees()) <= PlayerRecoilController.MAX_DELTA_PER_TICK_DEGREES);
            }
        }

        assertTrue(largestStep <= 2.5 + 1.0E-9,
                "one shot may never move the aim by more than its own impulse, largest step was " + largestStep);
    }

    @Test
    void rapidRepeatedShotsStayCoherent() {
        PlayerRecoilController controller = new PlayerRecoilController();
        double previous = 0.0;

        for (int shot = 0; shot < 30; shot++) {
            // 900 RPM: one shot every 66 ms, faster than this weapon, to exercise the overlap case.
            controller.onShot(IMPULSE);
            controller.tick(0.033);
            controller.tick(0.033);
            double offset = controller.pitchOffset();
            assertTrue(Double.isFinite(offset), "a rapid burst must never produce a non-finite offset");
            assertTrue(offset >= 0.0, "the offset must stay on the side the weapon configured");
            previous = offset;
        }
        assertTrue(previous > 2.5, "a rapid burst must accumulate");
        assertTrue(previous <= PlayerRecoilController.MAX_TOTAL_DEGREES);
    }

    // ---------------------------------------------------------------- recovery

    @Test
    void recoveryReturnsTheAimToExactlyWhereThePlayerLeftIt() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(IMPULSE);

        PlayerRecoilController.Delta kick = controller.tick(TICK_SECONDS);
        assertTrue(kick.pitchDegrees() > 2.0, "the shot must have raised the aim first, was " + kick.pitchDegrees());
        double totalPitch = kick.pitchDegrees();
        double totalYaw = kick.yawDegrees();

        for (int tick = 0; tick < 32; tick++) {
            PlayerRecoilController.Delta delta = controller.tick(TICK_SECONDS);
            totalPitch += delta.pitchDegrees();
            totalYaw += delta.yawDegrees();
        }

        assertEquals(0.0, totalPitch, 1.0E-9, "the deltas must sum to nothing: the aim returns to the neutral offset");
        assertEquals(0.0, totalYaw, 1.0E-9, "the sideways offset is returned just as exactly");
        assertEquals(0.0, controller.pitchOffset());
        assertEquals(0.0, controller.appliedPitch());
        assertEquals(0.0, controller.yawOffset());
        assertEquals(0.0, controller.appliedYaw());
        assertFalse(controller.isActive());
    }

    @Test
    void recoveryIsMonotoneAndTakesTheDocumentedInterval() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(IMPULSE);
        controller.tick(TICK_SECONDS);

        double previous = controller.pitchOffset();
        double elapsed = TICK_SECONDS;
        int guard = 0;
        while (controller.pitchOffset() > 0.0 && guard++ < 200) {
            controller.tick(TICK_SECONDS);
            elapsed += TICK_SECONDS;
            assertTrue(controller.pitchOffset() <= previous,
                    "the recovery must never rise again (no oscillation), was " + controller.pitchOffset()
                            + " after " + previous);
            previous = controller.pitchOffset();
        }

        assertEquals(0.0, controller.pitchOffset(), "the recovery must reach exactly zero");
        assertTrue(elapsed >= PlayerRecoilController.RECOVERY_SECONDS - TICK_SECONDS,
                "the recovery must not be shorter than the documented interval, was " + elapsed);
        assertTrue(elapsed <= PlayerRecoilController.RECOVERY_SECONDS + TICK_SECONDS,
                "the recovery must not be longer than the documented interval, was " + elapsed);
    }

    @Test
    void aShotDuringRecoveryKeepsWhatIsStillOutstanding() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(IMPULSE);
        for (int tick = 0; tick < TICKS_PER_SHOT; tick++) {
            controller.tick(TICK_SECONDS);
        }

        double remaining = controller.pitchOffset();
        assertTrue(remaining > 1.5 && remaining < 2.5, "the offset must be partially recovered, was " + remaining);

        controller.onShot(IMPULSE);

        assertTrue(controller.pitchOffset() > remaining, "the new impulse must add to what is still outstanding");
        assertTrue(controller.pitchOffset() > 2.5,
                "a shot during recovery must not reset the accumulated recoil to a single impulse, was "
                        + controller.pitchOffset());
    }

    @Test
    void recoveryStopsCompletelyOnceNeutralIsReached() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(IMPULSE);

        for (int tick = 0; tick < 40; tick++) {
            controller.tick(TICK_SECONDS);
        }

        for (int repeat = 0; repeat < 20; repeat++) {
            assertTrue(controller.tick(TICK_SECONDS).isZero(),
                    "nothing may keep recovering once the offset is already neutral");
        }
        assertEquals(0.0, controller.pitchOffset());
        assertEquals(0.0, controller.appliedPitch());
    }

    @Test
    void theRecoveryCurveIsFrameRateIndependent() {
        // 300 RPM: shots at 0, 0.2 and 0.4 seconds; both controllers are sampled at every 0.2 s boundary until 1.2 s.
        double[] slow = trace(0.05);
        double[] fast = trace(0.025);

        for (int sample = 0; sample < slow.length; sample++) {
            assertEquals(slow[sample], fast[sample], 1.0E-9,
                    "the same elapsed time must give the same offset, sample " + sample);
        }
    }

    @Test
    void aRepeatedSampleOfTheSameInstantChangesNothing() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(IMPULSE);
        controller.tick(TICK_SECONDS);

        double pitchOffset = controller.pitchOffset();
        double yawOffset = controller.yawOffset();
        double appliedPitch = controller.appliedPitch();
        double appliedYaw = controller.appliedYaw();

        for (int repeat = 0; repeat < 500; repeat++) {
            assertTrue(controller.tick(0.0).isZero(), "a zero step must not produce a rotation");
            assertTrue(controller.tick(-1.0).isZero(), "a negative step must not rewind anything");
            assertTrue(controller.tick(Double.NaN).isZero(), "a non-finite step must not advance anything");
            assertTrue(controller.tick(Double.POSITIVE_INFINITY).isZero());
        }

        assertEquals(pitchOffset, controller.pitchOffset());
        assertEquals(yawOffset, controller.yawOffset());
        assertEquals(appliedPitch, controller.appliedPitch());
        assertEquals(appliedYaw, controller.appliedYaw());
    }

    @Test
    void aLongStallCannotSkipTheWholeRecoveryInOneStep() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(IMPULSE);

        PlayerRecoilController.Delta delta = controller.tick(10.0);

        assertTrue(Math.abs(delta.pitchDegrees()) <= PlayerRecoilController.MAX_DELTA_PER_TICK_DEGREES,
                "a stalled frame must not fling the view, delta was " + delta.pitchDegrees());
        assertTrue(controller.pitchOffset() > 0.0, "the capped step must not have consumed the whole interval");
    }

    @Test
    void aPathologicalImpulseCannotSpinThePlayerAround() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(new ViewRecoilResponse(1.0E6, -1.0E6));

        assertEquals(PlayerRecoilController.MAX_TOTAL_DEGREES, controller.pitchOffset());
        assertEquals(-PlayerRecoilController.MAX_TOTAL_DEGREES, controller.yawOffset());

        double applied = 0.0;
        for (int tick = 0; tick < 10; tick++) {
            PlayerRecoilController.Delta delta = controller.tick(TICK_SECONDS);
            assertTrue(Math.abs(delta.pitchDegrees()) <= PlayerRecoilController.MAX_DELTA_PER_TICK_DEGREES);
            assertTrue(Math.abs(delta.yawDegrees()) <= PlayerRecoilController.MAX_DELTA_PER_TICK_DEGREES);
            applied += delta.pitchDegrees();
            assertTrue(applied <= PlayerRecoilController.MAX_TOTAL_DEGREES);
        }
    }

    @Test
    void resetReturnsToNeutral() {
        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(IMPULSE);
        controller.tick(TICK_SECONDS);

        controller.reset();

        assertEquals(0.0, controller.pitchOffset());
        assertEquals(0.0, controller.yawOffset());
        assertEquals(0.0, controller.appliedPitch());
        assertEquals(0.0, controller.appliedYaw());
        assertFalse(controller.isActive());
        assertTrue(controller.tick(TICK_SECONDS).isZero());
    }

    @Test
    void theConfiguredValuesAreTheOnesTheMilestoneProtects() {
        // The reference weapon's recoil block, verbatim: vertical 2.5, left 0.02, right 0.02, kickback 0.06.
        RecoilProfile profile = new RecoilProfile(0.06, true, 2.5, 0.02, 0.02, RecoilProfile.Sustained.NONE);

        assertEquals(0.06, profile.kickbackBlocks(), "kickback_recoil_amplitude must stay 0.06");
        assertEquals(2.5, profile.verticalDegrees(), "vertical_recoil must stay 2.5");
        assertEquals(0.02, profile.horizontalLeftDegrees(), "horizontal_recoil_left must stay 0.02");
        assertEquals(0.02, profile.horizontalRightDegrees(), "horizontal_recoil_right must stay 0.02");

        PlayerRecoilController controller = new PlayerRecoilController();
        controller.onShot(new ViewRecoilResponse(profile.verticalDegrees(), profile.horizontalDirection(null)));
        assertEquals(2.5, controller.pitchOffset(), 1.0E-12);
        assertTrue(controller.tick(TICK_SECONDS).pitchDegrees() > 0.0);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Fires one shot and advances this weapon's 300 RPM interval, the way the client does.
     *
     * @param controller the controller to drive
     * @param shots      how many shots to fire
     * @return the accumulated pitch offset after the last shot's interval
     */
    private static double fire(PlayerRecoilController controller, int shots) {
        for (int shot = 0; shot < shots; shot++) {
            controller.onShot(IMPULSE);
            for (int tick = 0; tick < TICKS_PER_SHOT; tick++) {
                controller.tick(TICK_SECONDS);
            }
        }
        return controller.pitchOffset();
    }

    /**
     * Fires the same three-shot schedule at two different tick rates and records the offset at every 0.2 s boundary, up to
     * 1.2 s (the burst plus its recovery).
     *
     * @param stepSeconds the tick length to drive the controller with
     * @return the sampled offsets, six of them, spaced 0.2 s apart
     */
    private static double[] trace(double stepSeconds) {
        PlayerRecoilController controller = new PlayerRecoilController();
        int stepsPerShot = (int) Math.round(0.2 / stepSeconds);
        int sampleCount = 6;
        double[] samples = new double[sampleCount];
        int sample = 0;
        int shot = 0;
        for (int step = 1; sample < sampleCount; step++) {
            if (shot < 3 && (step - 1) % stepsPerShot == 0) {
                controller.onShot(IMPULSE);
                shot++;
            }
            controller.tick(stepSeconds);
            if (step % stepsPerShot == 0) {
                samples[sample++] = controller.pitchOffset();
            }
        }
        return samples;
    }
}
