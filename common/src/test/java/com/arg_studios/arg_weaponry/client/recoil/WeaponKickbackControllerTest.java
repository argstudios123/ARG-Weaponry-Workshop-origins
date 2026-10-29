package com.arg_studios.arg_weaponry.client.recoil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The weapon-model kickback: optional, amplitude-driven, backwards only, a two-phase impulse per shot, and no single-frame
 * discontinuity at the shot, during overlap, or at the end of the response.
 *
 * <p>Every timing test drives the controller the way the game does: one client tick every 50 ms with three render frames per
 * tick (60 fps), and the shot registered before the tick that delivers it.</p>
 */
class WeaponKickbackControllerTest {

    private static final double TICK_SECONDS = 0.05;

    private static final int FRAMES_PER_TICK = 3;

    private static final double AMPLITUDE = 0.02;

    // ---------------------------------------------------------------- configuration contract

    @Test
    void anAbsentAmplitudeProducesNoKickback() {
        RecoilProfile profile = RecoilProfile.of(
                new WeaponDefinition.Recoil(null, 0.0, null, 0.02, 0.02), null);
        assertFalse(profile.hasKickback(), "an absent kickback_recoil_amplitude must disable kickback");

        WeaponKickbackController controller = new WeaponKickbackController();
        controller.tick(TICK_SECONDS);
        controller.shot(profile);
        assertExactlyNeutral(controller);
    }

    @Test
    void aZeroAmplitudeProducesNoKickback() {
        RecoilProfile profile = RecoilProfile.of(
                new WeaponDefinition.Recoil(0.0, 0.0, null, null, null), null);
        assertFalse(profile.hasKickback(), "a configured 0.0 must disable kickback");

        WeaponKickbackController controller = new WeaponKickbackController();
        controller.tick(TICK_SECONDS);
        controller.shot(profile);
        assertExactlyNeutral(controller);
    }

    @Test
    void theSustainedBlockAloneNeverStartsKickback() {
        // The sustained block used to add a stepped displacement of its own. It is still parsed and preserved, but it no
        // longer drives the weapon transform, so on its own it cannot move the weapon at all.
        RecoilProfile profile = RecoilProfile.of(
                new WeaponDefinition.Recoil(null, 0.0, null, null, null),
                new WeaponDefinition.SustainedRecoil(0.32, 0.05, -0.04, 0.3));

        WeaponKickbackController controller = new WeaponKickbackController();
        controller.tick(TICK_SECONDS);
        controller.shot(profile);
        controller.tick(TICK_SECONDS);
        assertExactlyNeutral(controller);
    }

    @Test
    void aShotMovesTheWeaponBackwardsByTheConfiguredAmplitude() {
        Trace trace = simulate(12, 1, AMPLITUDE);

        assertEquals(AMPLITUDE, trace.peak(), AMPLITUDE * 0.01,
                "the peak displacement must be the configured amplitude, was " + trace.peak());
        assertTrue(trace.peak() <= AMPLITUDE + 1.0E-9,
                "one shot must never exceed its amplitude, was " + trace.peak());
        for (double drawn : trace.values()) {
            assertTrue(drawn >= 0.0, "kickback is backwards only and can never go negative");
        }
    }

    @Test
    void theInitialKickIsSmoothAndIsRenderedAsARamp() {
        Trace trace = simulate(12, 1, AMPLITUDE);

        // The frame the shot is delivered draws nothing: the pose must not change in that instant.
        assertEquals(0.0, trace.values().get(3), 1.0E-12, "the shot itself must not move the weapon");

        int partialInRise = 0;
        for (double drawn : trace.values()) {
            if (drawn > 0.0 && drawn < trace.peak() * 0.99) {
                partialInRise++;
            }
        }
        assertTrue(partialInRise >= 2, "the kick must be a ramp of at least two frames, not a one-frame teleport");
        assertTrue(trace.largestStep() < trace.peak() * 0.6,
                "no frame may carry more than 60% of the peak during the kick, largest was " + trace.largestStep());
    }

    // ---------------------------------------------------------------- the curve itself

    @Test
    void theCurveStartsAndEndsAtRestWithZeroVelocity() {
        assertEquals(0.0, WeaponKickbackController.impulseAt(0.0), "the curve starts at zero");
        assertEquals(1.0, WeaponKickbackController.impulseAt(WeaponKickbackController.KICK_SECONDS), 1.0E-12,
                "the curve peaks at exactly one amplitude at the end of the kick phase");
        assertEquals(0.0, WeaponKickbackController.impulseAt(WeaponKickbackController.IMPULSE_DURATION_SECONDS),
                "the curve ends at exactly zero");

        // Zero velocity is a statement about the derivative, so it is checked by shrinking the step: the average slope over
        // the first h seconds must itself tend to zero (a curve that started at speed would keep a constant slope).
        double steepestKick = 1.5 / WeaponKickbackController.KICK_SECONDS;
        double startSlope = WeaponKickbackController.impulseAt(1.0E-4) / 1.0E-4;
        double finerStartSlope = WeaponKickbackController.impulseAt(1.0E-5) / 1.0E-5;
        assertTrue(startSlope < steepestKick * 0.01,
                "the kick must begin far slower than its steepest point, was " + startSlope);
        assertTrue(finerStartSlope < startSlope * 0.2,
                "the starting slope must tend to zero, " + startSlope + " -> " + finerStartSlope);

        double steepestRecovery = 1.5 / WeaponKickbackController.RECOVERY_SECONDS;
        double end = WeaponKickbackController.IMPULSE_DURATION_SECONDS;
        double endSlope = WeaponKickbackController.impulseAt(end - 1.0E-4) / 1.0E-4;
        assertTrue(endSlope < steepestRecovery * 0.01,
                "the recovery must arrive with zero velocity, was " + endSlope);
    }

    @Test
    void theCurveRisesThenFallsMonotonicallyWithoutOvershoot() {
        double previous = 0.0;
        double peak = 0.0;
        double peakSeconds = 0.0;
        for (int step = 1; step <= 20_000; step++) {
            double seconds = WeaponKickbackController.IMPULSE_DURATION_SECONDS * step / 20_000.0;
            double value = WeaponKickbackController.impulseAt(seconds);
            assertTrue(value >= 0.0 && value <= 1.0, "the curve must stay in [0, 1], was " + value);
            if (value > peak) {
                peak = value;
                peakSeconds = seconds;
            }
            if (seconds > peakSeconds) {
                assertTrue(value <= peak, "the curve must not rise again after its peak, at " + seconds);
            }
            previous = value;
        }
        assertEquals(1.0, peak, 1.0E-9, "the curve must reach exactly one amplitude");
        assertEquals(WeaponKickbackController.KICK_SECONDS, peakSeconds, 0.001,
                "the peak must sit at the end of the kick phase");
        assertEquals(0.0, previous, 1.0E-12, "the curve must end at zero");
    }

    @Test
    void theCurveIsContinuousAcrossThePhaseBoundary() {
        double kick = WeaponKickbackController.KICK_SECONDS;
        double epsilon = 1.0E-4;

        assertEquals(1.0, WeaponKickbackController.impulseAt(kick - epsilon), 1.0E-3);
        assertEquals(1.0, WeaponKickbackController.impulseAt(kick + epsilon), 1.0E-3);
    }

    @Test
    void theResponseEndsExactlyAtZeroWithoutASnap() {
        Trace trace = simulate(20, 1, AMPLITUDE);

        List<Double> values = trace.values();
        double lastNonZero = 0.0;
        int lastNonZeroIndex = -1;
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index) > 0.0) {
                lastNonZero = values.get(index);
                lastNonZeroIndex = index;
            }
        }
        assertTrue(lastNonZeroIndex >= 0, "the kickback must have run at all");
        assertTrue(lastNonZero <= trace.peak() * 0.05,
                "the last visible displacement must be under 5% of the peak, was " + lastNonZero);
        assertTrue(lastNonZero <= trace.peak() * 0.02,
                "the final step into the resting pose must be under 2% of the peak, was " + lastNonZero);
        for (int index = lastNonZeroIndex + 1; index < values.size(); index++) {
            assertEquals(0.0, values.get(index), "the response must stay exactly at zero once it has ended");
        }
    }

    @Test
    void theSamplerEvaluatesTheCurveAtTheFramesOwnInstant() {
        WeaponKickbackController controller = new WeaponKickbackController();
        controller.tick(TICK_SECONDS);
        controller.shot(kickback(AMPLITUDE));
        controller.tick(TICK_SECONDS);
        controller.tick(TICK_SECONDS);

        // After the two ticks the impulse's age is one step, and a frame at partialTick p must draw the analytic curve at
        // age + step * p - no accumulated amplitude, no decay factor, nothing that depends on the frame rate.
        double age = TICK_SECONDS;
        for (float partial : new float[] {0.0F, 0.25F, 0.5F, 0.75F, 1.0F}) {
            double expected = WeaponKickbackController.impulseAt(age + TICK_SECONDS * partial) * AMPLITUDE;
            assertEquals(expected, controller.sample(partial).backwardBlocks(), 1.0E-6,
                    "partial tick " + partial);
        }
    }

    @Test
    void samplingTheSameInstantTwiceChangesNothing() {
        WeaponKickbackController controller = new WeaponKickbackController();
        controller.tick(TICK_SECONDS);
        controller.shot(kickback(AMPLITUDE));
        controller.tick(TICK_SECONDS);

        WeaponKickbackController.Kickback first = controller.sample(0.37F);
        double backward = controller.backwardBlocks();
        int active = controller.activeImpulses();
        for (int repeat = 0; repeat < 100; repeat++) {
            assertEquals(first.backwardBlocks(), controller.sample(0.37F).backwardBlocks(),
                    "a repeated frame must draw the same displacement");
        }
        assertEquals(backward, controller.backwardBlocks(), "sampling must not advance the response");
        assertEquals(active, controller.activeImpulses(), "sampling must not consume impulses");
    }

    // ---------------------------------------------------------------- overlapping shots

    @Test
    void aSecondShotAddsToTheRemainingDisplacementInsteadOfReplacingIt() {
        WeaponKickbackController single = new WeaponKickbackController();
        WeaponKickbackController retriggered = new WeaponKickbackController();
        List<Double> singleValues = new ArrayList<>();
        List<Double> retriggeredValues = new ArrayList<>();
        double beforeShot = 0.0;
        double afterShot = 0.0;

        for (int tick = 0; tick < 16; tick++) {
            if (tick == 1) {
                single.shot(kickback(AMPLITUDE));
                retriggered.shot(kickback(AMPLITUDE));
            }
            if (tick == 3) {
                // A second shot arrives while the first one is still recovering.
                beforeShot = retriggered.sample(0.5F).backwardBlocks();
                retriggered.shot(kickback(AMPLITUDE));
                afterShot = retriggered.sample(0.5F).backwardBlocks();
            }
            single.tick(TICK_SECONDS);
            retriggered.tick(TICK_SECONDS);
            for (int frame = 0; frame < FRAMES_PER_TICK; frame++) {
                float partial = (float) frame / FRAMES_PER_TICK;
                singleValues.add((double) single.sample(partial).backwardBlocks());
                retriggeredValues.add((double) retriggered.sample(partial).backwardBlocks());
            }
        }

        assertEquals(beforeShot, afterShot, 1.0E-12,
                "a new shot must not move the weapon in the instant it is registered");

        double largestExtra = 0.0;
        for (int index = 0; index < singleValues.size(); index++) {
            double extra = retriggeredValues.get(index) - singleValues.get(index);
            assertTrue(extra >= -1.0E-12,
                    "a second shot must never reduce the displacement left by the first, index " + index);
            assertTrue(extra <= AMPLITUDE + 1.0E-9,
                    "a second shot may add at most its own amplitude, index " + index + " added " + extra);
            largestExtra = Math.max(largestExtra, extra);
        }
        assertTrue(largestExtra > AMPLITUDE * 0.9,
                "the second shot must actually contribute to the pose, largest was " + largestExtra);
    }

    @Test
    void repeatedShotsStayBoundedAndLeaveNoDrift() {
        WeaponKickbackController controller = new WeaponKickbackController();
        controller.tick(TICK_SECONDS);
        double peak = 0.0;
        for (int shot = 0; shot < 60; shot++) {
            controller.shot(kickback(AMPLITUDE));
            for (int half = 0; half < 2; half++) {
                controller.tick(0.02);
                for (int frame = 0; frame < FRAMES_PER_TICK; frame++) {
                    peak = Math.max(peak, controller.sample((float) frame / FRAMES_PER_TICK).backwardBlocks());
                }
            }
        }
        assertTrue(peak >= AMPLITUDE, "sixty shots must still move the weapon, peak was " + peak);
        assertTrue(peak <= AMPLITUDE * WeaponKickbackController.MAX_OVERLAP_FACTOR + 1.0E-9,
                "sixty overlapping shots must stay inside the overlap ceiling, peak was " + peak);

        for (int tick = 0; tick < 40; tick++) {
            controller.tick(TICK_SECONDS);
        }
        assertEquals(0.0, controller.backwardBlocks(), "the weapon must return to exactly zero displacement");
        assertEquals(0, controller.activeImpulses(), "every impulse must have expired");
        assertTrue(controller.sample(0.5F).isZero());
    }

    @Test
    void automaticFireAt300RpmIsContinuousAndLeavesNoDrift() {
        WeaponKickbackController controller = new WeaponKickbackController();
        double largestStep = 0.0;
        double previous = 0.0;
        double afterRelease = 0.0;
        for (int tick = 0; tick < 60; tick++) {
            // 300 RPM: one shot every 0.2 s, i.e. every fourth 50 ms tick, for the first 1.2 s.
            if (tick < 24 && tick % 4 == 1) {
                controller.shot(kickback(AMPLITUDE));
            }
            controller.tick(TICK_SECONDS);
            for (int frame = 0; frame < FRAMES_PER_TICK; frame++) {
                double drawn = controller.sample((float) frame / FRAMES_PER_TICK).backwardBlocks();
                largestStep = Math.max(largestStep, Math.abs(drawn - previous));
                previous = drawn;
                if (tick >= 24) {
                    afterRelease = drawn;
                }
            }
        }
        assertTrue(largestStep < AMPLITUDE * 0.6,
                "automatic fire must never jump by more than 60% of one shot's amplitude, largest was "
                        + largestStep);
        assertEquals(0.0, afterRelease, "the weapon must be exactly neutral after the burst");
        assertEquals(0, controller.activeImpulses());
    }

    @Test
    void kickbackIsIndependentOfPlayerRecoil() {
        WeaponKickbackController alone = new WeaponKickbackController();
        WeaponKickbackController alongside = new WeaponKickbackController();
        PlayerRecoilController view = new PlayerRecoilController();
        view.onShot(new ViewRecoilResponse(2.5, 0.02));

        boolean viewMoved = false;
        for (int tick = 0; tick < 20; tick++) {
            if (tick == 1) {
                alone.shot(kickback(AMPLITUDE));
                alongside.shot(kickback(AMPLITUDE));
            }
            alone.tick(TICK_SECONDS);
            alongside.tick(TICK_SECONDS);
            viewMoved |= !view.tick(TICK_SECONDS).isZero();
            for (int frame = 0; frame < FRAMES_PER_TICK; frame++) {
                float partial = (float) frame / FRAMES_PER_TICK;
                assertEquals(alone.sample(partial).backwardBlocks(),
                        alongside.sample(partial).backwardBlocks(),
                        "the player's aim recoil must not change the weapon kickback");
            }
        }
        assertTrue(viewMoved, "the view recoil must have run during the test");
    }

    @Test
    void resetReturnsTheWeaponToNeutral() {
        WeaponKickbackController controller = new WeaponKickbackController();
        controller.tick(TICK_SECONDS);
        controller.shot(kickback(AMPLITUDE));
        controller.tick(TICK_SECONDS);
        controller.tick(TICK_SECONDS);
        assertTrue(controller.sample(0.5F).backwardBlocks() > 0.0F, "the weapon must be displaced first");

        controller.reset();

        assertExactlyNeutral(controller);
    }

    // ---------------------------------------------------------------- helpers

    private static RecoilProfile kickback(double amplitude) {
        return new RecoilProfile(amplitude, true, 0.0, 0.0, 0.0, RecoilProfile.Sustained.NONE);
    }

    private static void assertExactlyNeutral(WeaponKickbackController controller) {
        assertEquals(0.0, controller.backwardBlocks(), "the displacement must be exactly zero");
        assertEquals(0, controller.activeImpulses(), "no shot may be contributing");
        for (int frame = 0; frame <= FRAMES_PER_TICK; frame++) {
            assertTrue(controller.sample((float) frame / FRAMES_PER_TICK).isZero(),
                    "every frame must draw the neutral transform");
        }
    }

    /**
     * @param ticks     how many client ticks to run
     * @param shotTick  the tick whose start delivers the shot, or {@code -1} for none
     * @param amplitude the shot's amplitude
     * @return the drawn displacement of every frame, its peak, and the largest frame-to-frame step
     */
    private static Trace simulate(int ticks, int shotTick, double amplitude) {
        WeaponKickbackController controller = new WeaponKickbackController();
        List<Double> values = new ArrayList<>();
        double previous = 0.0;
        double largestStep = 0.0;
        double peak = 0.0;
        for (int tick = 0; tick < ticks; tick++) {
            if (tick == shotTick) {
                controller.shot(kickback(amplitude));
            }
            controller.tick(TICK_SECONDS);
            for (int frame = 0; frame < FRAMES_PER_TICK; frame++) {
                double drawn = controller.sample((float) frame / FRAMES_PER_TICK).backwardBlocks();
                values.add(drawn);
                largestStep = Math.max(largestStep, Math.abs(drawn - previous));
                peak = Math.max(peak, drawn);
                previous = drawn;
            }
        }
        return new Trace(values, peak, largestStep);
    }

    private record Trace(List<Double> values, double peak, double largestStep) {
    }
}
