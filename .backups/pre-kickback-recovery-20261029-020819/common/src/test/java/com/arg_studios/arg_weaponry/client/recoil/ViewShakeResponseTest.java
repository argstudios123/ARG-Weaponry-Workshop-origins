package com.arg_studios.arg_weaponry.client.recoil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The per-shot screen-shake curve, verified against the behaviour read out of Point Blank's
 * {@code ViewShakeAnimationController}: {@code sin(π · speed · progress)} for yaw and roll, a per-shot
 * {@code ±[A, 2A)} magnitude on each channel, no pitch, and a defined end.
 */
class ViewShakeResponseTest {

    /** The amplitude and speed of this task's own weapon configuration, as a realistic reference point. */
    private static final double AMPLITUDE = 0.8;

    private static final double SPEED = 8.0;

    private static final double DURATION = ViewShakeResponse.DEFAULT_DURATION_SECONDS;

    @Test
    void absentOrZeroAmplitudeAsksForNoShake() {
        assertTrue(ViewShakeResponse.forShot(0.0, SPEED, new Random(1L)).isZero(), "zero amplitude must disable shake");
        assertTrue(ViewShakeResponse.forShot(-2.0, SPEED, new Random(1L)).isZero(), "negative amplitude disables shake");
        assertTrue(ViewShakeResponse.forShot(Double.NaN, SPEED, new Random(1L)).isZero(), "NaN amplitude disables shake");
        assertTrue(ViewShakeResponse.NONE.isZero());
    }

    @Test
    void aZeroResponseSamplesExactlyZeroEverywhere() {
        ViewShakeResponse response = ViewShakeResponse.forShot(0.0, SPEED, null);
        for (int step = 0; step <= 100; step++) {
            double time = DURATION * step / 100.0;
            assertEquals(0.0, response.yawAt(time));
            assertEquals(0.0, response.rollAt(time));
        }
    }

    @Test
    void eachShotDrawsItsOwnSignAndMagnitudeInsideTheReferenceRange() {
        Random random = new Random(7L);
        boolean yawPositive = false;
        boolean yawNegative = false;
        boolean rollPositive = false;
        boolean rollNegative = false;
        for (int shot = 0; shot < 200; shot++) {
            ViewShakeResponse response = ViewShakeResponse.forShot(AMPLITUDE, SPEED, random);
            double yaw = response.yawAmplitudeDegrees();
            double roll = response.rollAmplitudeDegrees();
            assertTrue(Math.abs(yaw) >= AMPLITUDE && Math.abs(yaw) < 2.0 * AMPLITUDE,
                    "yaw magnitude must be drawn from [A, 2A), was " + yaw);
            assertTrue(Math.abs(roll) >= AMPLITUDE && Math.abs(roll) < 2.0 * AMPLITUDE,
                    "roll magnitude must be drawn from [A, 2A), was " + roll);
            yawPositive |= yaw > 0.0;
            yawNegative |= yaw < 0.0;
            rollPositive |= roll > 0.0;
            rollNegative |= roll < 0.0;
        }
        assertTrue(yawPositive && yawNegative, "yaw direction must vary between shots");
        assertTrue(rollPositive && rollNegative, "roll direction must vary between shots");
    }

    @Test
    void theResponseStartsAndEndsAtExactlyZero() {
        ViewShakeResponse response = new ViewShakeResponse(1.0, -1.0, SPEED, DURATION);
        assertEquals(0.0, response.yawAt(0.0), "a shot's shake starts from zero");
        assertEquals(0.0, response.rollAt(0.0), "a shot's shake starts from zero");
        assertEquals(0.0, response.yawAt(DURATION), "the shake must be exactly zero at the end");
        assertEquals(0.0, response.rollAt(DURATION), "the shake must be exactly zero at the end");
        assertEquals(0.0, response.yawAt(DURATION + 1.0), "and stay zero afterwards");
        assertEquals(0.0, response.rollAt(DURATION * 10.0), "and stay zero afterwards");
        assertFalse(response.isFinished(DURATION * 0.5));
        assertTrue(response.isFinished(DURATION));
    }

    @Test
    void theEnvelopeFallsMonotonicallyToZero() {
        ViewShakeResponse response = new ViewShakeResponse(1.0, 1.0, SPEED, DURATION);
        assertEquals(1.0, response.envelope(0.0), 1.0E-12);
        assertEquals(0.875, response.envelope(0.5), 1.0E-12);
        assertEquals(0.0, response.envelope(1.0), 1.0E-12);
        double previous = 1.0;
        for (int step = 0; step <= 100; step++) {
            double envelope = response.envelope(step / 100.0);
            assertTrue(envelope <= previous, "the envelope must never rise; fell over at p=" + step / 100.0);
            assertTrue(envelope >= 0.0 && envelope <= 1.0, "the envelope must stay in [0, 1]");
            previous = envelope;
        }
    }

    @Test
    void speedChangesTheOscillationRateButNotTheDuration() {
        ViewShakeResponse slow = new ViewShakeResponse(1.0, 1.0, 2.0, DURATION);
        ViewShakeResponse fast = new ViewShakeResponse(1.0, 1.0, 8.0, DURATION);

        assertEquals(1, zeroCrossings(slow), "speed 2 is one full swing across the shake");
        assertEquals(7, zeroCrossings(fast), "speed 8 is four full swings across the shake");

        // A rate, not a decay constant: the shake lasts exactly as long at either speed.
        assertFalse(slow.isFinished(DURATION * 0.99));
        assertFalse(fast.isFinished(DURATION * 0.99));
        assertTrue(slow.isFinished(DURATION));
        assertTrue(fast.isFinished(DURATION));
    }

    @Test
    void theResponseNeverExceedsTwiceTheConfiguredAmplitude() {
        ViewShakeResponse response = new ViewShakeResponse(1.6, -1.9, 8.0, DURATION);
        double largest = 0.0;
        for (int step = 0; step <= 10_000; step++) {
            double time = DURATION * step / 10_000.0;
            largest = Math.max(largest, Math.abs(response.yawAt(time)));
            largest = Math.max(largest, Math.abs(response.rollAt(time)));
        }
        assertTrue(largest <= ViewShakeResponse.MAX_AMPLITUDE_DEGREES,
                "the response must stay bounded, was " + largest);
        assertTrue(largest > 1.0, "the response must actually move the camera, was " + largest);
    }

    @Test
    void samplingTheSameTimeTwiceGivesTheSameAngles() {
        ViewShakeResponse response = new ViewShakeResponse(1.3, -0.7, 6.0, DURATION);
        for (double time : new double[] {0.0, 0.01, 0.117, 0.2, 0.349}) {
            assertEquals(response.yawAt(time), response.yawAt(time), "yaw must be a pure function of time");
            assertEquals(response.rollAt(time), response.rollAt(time), "roll must be a pure function of time");
        }
    }

    @Test
    void theDefaultDurationIsTheReferenceAn94Value() {
        assertEquals(0.35, ViewShakeResponse.DEFAULT_DURATION_SECONDS);
        assertEquals(0.35, ViewShakeResponse.forShot(AMPLITUDE, SPEED, null).durationSeconds());
    }

    /**
     * @param response the response to sweep
     * @return how often its yaw changes sign between the shot and the end, i.e. how many half periods it completed
     */
    private static int zeroCrossings(ViewShakeResponse response) {
        int crossings = 0;
        double previous = 0.0;
        for (int step = 0; step < 1000; step++) {
            double value = response.yawAt(response.durationSeconds() * (step + 0.5) / 1000.0);
            if (previous != 0.0 && value != 0.0 && Math.signum(previous) != Math.signum(value)) {
                crossings++;
            }
            previous = value;
        }
        return crossings;
    }
}
