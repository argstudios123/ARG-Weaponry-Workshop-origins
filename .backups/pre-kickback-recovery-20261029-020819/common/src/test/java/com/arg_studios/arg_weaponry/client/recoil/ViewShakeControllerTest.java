package com.arg_studios.arg_weaponry.client.recoil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The screen-shake controller: optional, bounded, frame-rate independent, deterministic for a given time sample, and
 * isolated from the player's own view recoil.
 */
class ViewShakeControllerTest {

    private static final double AMPLITUDE = 0.8;

    private static final double SPEED = 8.0;

    private static final double DURATION = ViewShakeResponse.DEFAULT_DURATION_SECONDS;

    @Test
    void noShotMeansNoShakeAtAll() {
        ViewShakeController controller = new ViewShakeController();
        controller.onShot(null);
        controller.onShot(ViewShakeResponse.NONE);
        controller.onShot(ViewShakeResponse.forShot(0.0, SPEED, new Random(3L)));
        controller.tick(0.05);

        for (float partialTick : new float[] {0.0F, 0.5F, 1.0F}) {
            assertShakeEquals(ViewShakeController.ShakeAngles.NONE, controller.sample(partialTick));
        }
        assertFalse(controller.isActive(), "a weapon without an amplitude must never shake");
        assertEquals(0.0, controller.amplitude());
    }

    @Test
    void aShotProducesABoundedCameraOnlyResponse() {
        ViewShakeController controller = new ViewShakeController();
        controller.onShot(new ViewShakeResponse(1.4, -1.1, SPEED, DURATION));
        double largestYaw = 0.0;
        double largestRoll = 0.0;
        for (int tick = 0; tick < 40; tick++) {
            controller.tick(0.01);
            for (int frame = 0; frame <= 10; frame++) {
                ViewShakeController.ShakeAngles angles = controller.sample(frame / 10.0F);
                assertEquals(0.0, angles.pitchDegrees(), "the shake layer must never add pitch");
                largestYaw = Math.max(largestYaw, Math.abs(angles.yawDegrees()));
                largestRoll = Math.max(largestRoll, Math.abs(angles.rollDegrees()));
            }
        }
        assertTrue(largestYaw <= ViewShakeResponse.MAX_AMPLITUDE_DEGREES, "yaw must stay bounded, was " + largestYaw);
        assertTrue(largestRoll <= ViewShakeResponse.MAX_AMPLITUDE_DEGREES, "roll must stay bounded, was " + largestRoll);
        assertTrue(largestYaw > 0.5, "the shake must actually turn the view, was " + largestYaw);
        assertTrue(largestRoll > 0.5, "the shake must actually roll the view, was " + largestRoll);
    }

    @Test
    void theSameSampleReturnsTheSameAnglesAndChangesNothing() {
        ViewShakeController controller = new ViewShakeController();
        controller.onShot(new ViewShakeResponse(1.0, -0.6, 5.0, DURATION));
        controller.tick(0.05);

        ViewShakeController.ShakeAngles first = controller.sample(0.37F);
        double amplitude = controller.amplitude();
        for (int repeat = 0; repeat < 100; repeat++) {
            assertShakeEquals(first, controller.sample(0.37F));
            assertEquals(amplitude, controller.amplitude(), "sampling must not mutate the layer");
        }

        // The sample is the pure response evaluated at the interpolated elapsed time, not a running total.
        double interpolated = 0.05 * (double) 0.37F;
        assertEquals(controller.response().yawAt(interpolated), first.yawDegrees());
        assertEquals(controller.response().rollAt(interpolated), first.rollDegrees());
    }

    @Test
    void durationControlsWhenTheResponseFinishes() {
        ViewShakeController controller = new ViewShakeController();
        controller.onShot(new ViewShakeResponse(1.0, 1.0, 4.0, 0.7));
        controller.tick(0.25);
        controller.tick(0.25);

        assertTrue(controller.isActive(), "a 0.7 s shake is still running after 0.5 s");
        assertFalse(controller.sample(0.5F).isZero(), "and still moves the camera");

        controller.tick(0.2);
        assertFalse(controller.isActive(), "the shake is over once its duration is spent");
        assertEquals(0.0, controller.sample(1.0F).yawDegrees(), "a finished shake must be exactly zero");
        assertEquals(0.0, controller.sample(1.0F).rollDegrees(), "a finished shake must be exactly zero");
        assertEquals(0.0, controller.sample(1.0F).pitchDegrees());
    }

    @Test
    void aFinishedShakeIsDroppedInsteadOfLingering() {
        ViewShakeController controller = new ViewShakeController();
        controller.onShot(new ViewShakeResponse(1.0, 1.0, SPEED, DURATION));
        for (int tick = 0; tick < 30; tick++) {
            controller.tick(0.02);
        }
        assertTrue(controller.response().isZero(), "a finished shake must be dropped, not kept for another frame");
        assertShakeEquals(ViewShakeController.ShakeAngles.NONE, controller.sample(1.0F));
        assertFalse(controller.isActive());
    }

    @Test
    void repeatedShotsReplaceTheShakeInsteadOfAccumulating() {
        ViewShakeController controller = new ViewShakeController();
        Random random = new Random(11L);
        double largest = 0.0;
        for (int shot = 0; shot < 60; shot++) {
            controller.onShot(ViewShakeResponse.forShot(AMPLITUDE, SPEED, random));
            for (int tick = 0; tick < 2; tick++) {
                controller.tick(0.05);
                for (int frame = 0; frame <= 10; frame++) {
                    ViewShakeController.ShakeAngles angles = controller.sample(frame / 10.0F);
                    largest = Math.max(largest, Math.abs(angles.yawDegrees()));
                    largest = Math.max(largest, Math.abs(angles.rollDegrees()));
                }
            }
        }
        assertTrue(largest <= ViewShakeResponse.MAX_AMPLITUDE_DEGREES,
                "sixty shots must not push the camera past the per-shot bound, reached " + largest);

        for (int tick = 0; tick < 30; tick++) {
            controller.tick(0.05);
        }
        assertShakeEquals(ViewShakeController.ShakeAngles.NONE, controller.sample(0.5F));
        assertTrue(controller.response().isZero(), "the last shot's shake must be gone once its duration is spent");
    }

    @Test
    void automaticFireAtARealisticRateStaysStableAndBounded() {
        ViewShakeController controller = new ViewShakeController();
        Random random = new Random(23L);
        // 600 rounds per minute is one shot every 0.1 s, i.e. every other 20 Hz client tick.
        int ticksPerShot = 2;
        double largest = 0.0;
        for (int shot = 0; shot < 30; shot++) {
            controller.onShot(ViewShakeResponse.forShot(AMPLITUDE, SPEED, random));
            for (int tick = 0; tick < ticksPerShot; tick++) {
                controller.tick(0.05);
                for (int frame = 0; frame <= 10; frame++) {
                    ViewShakeController.ShakeAngles angles = controller.sample(frame / 10.0F);
                    assertTrue(Double.isFinite(angles.yawDegrees()) && Double.isFinite(angles.rollDegrees()),
                            "a sustained burst must never produce a non-finite angle");
                    largest = Math.max(largest, Math.abs(angles.yawDegrees()));
                    largest = Math.max(largest, Math.abs(angles.rollDegrees()));
                }
            }
        }
        assertTrue(largest > 0.0, "automatic fire must shake the camera");
        assertTrue(largest <= ViewShakeResponse.MAX_AMPLITUDE_DEGREES,
                "automatic fire must stay bounded, reached " + largest);
    }

    @Test
    void screenShakeNeverTouchesThePlayerRecoilController() {
        ViewRecoilResponse response = new ViewRecoilResponse(2.5, -0.02);
        PlayerRecoilController withoutShake = new PlayerRecoilController();
        PlayerRecoilController withShake = new PlayerRecoilController();
        withoutShake.onShot(response);
        withShake.onShot(response);

        ViewShakeController shake = new ViewShakeController();
        shake.onShot(ViewShakeResponse.forShot(AMPLITUDE, SPEED, new Random(5L)));

        double firstPitchDelta = 0.0;
        double totalPitchDelta = 0.0;
        for (int tick = 0; tick < 20; tick++) {
            shake.tick(0.05);
            PlayerRecoilController.Delta quiet = withoutShake.tick(0.05);
            PlayerRecoilController.Delta shaking = withShake.tick(0.05);
            assertEquals(quiet, shaking, "the screen shake must not change the player's recoil at all");
            if (tick == 0) {
                firstPitchDelta = quiet.pitchDegrees();
            }
            totalPitchDelta += quiet.pitchDegrees();
        }
        assertTrue(firstPitchDelta > 0.0, "player recoil must keep working while screen shake runs");
        // The view recoil hands the aim back when it has finished, which is why the deltas sum to nothing.
        assertTrue(Math.abs(totalPitchDelta) < 1.0E-6, "view recoil must recover exactly, sum was " + totalPitchDelta);
    }

    @Test
    void disablingScreenShakeLeavesPlayerRecoilWorking() {
        PlayerRecoilController view = new PlayerRecoilController();
        ViewShakeController shake = new ViewShakeController();
        shake.onShot(ViewShakeResponse.forShot(0.0, SPEED, null));

        view.onShot(new ViewRecoilResponse(2.5, -0.02));
        PlayerRecoilController.Delta delta = view.tick(0.05);

        assertTrue(delta.pitchDegrees() > 0.0, "vertical recoil must be unaffected by the shake setting");
        assertTrue(delta.yawDegrees() != 0.0, "horizontal recoil must be unaffected by the shake setting");
        assertShakeEquals(ViewShakeController.ShakeAngles.NONE, shake.sample(0.5F));
    }

    private static void assertShakeEquals(ViewShakeController.ShakeAngles expected,
                                          ViewShakeController.ShakeAngles actual) {
        assertEquals(expected.pitchDegrees(), actual.pitchDegrees(), "pitch");
        assertEquals(expected.yawDegrees(), actual.yawDegrees(), "yaw");
        assertEquals(expected.rollDegrees(), actual.rollDegrees(), "roll");
    }
}
