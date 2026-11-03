package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The shared blending arithmetic: a rate-limited approach that is independent of the frame rate, and the smoothstep used
 * for cross-fades.
 *
 * <p>These two functions are the reason a transition does not depend on how often the client ticks, so they are verified
 * directly instead of only through the layers that use them.</p>
 */
class AnimationBlendingTest {

    @Test
    void anApproachTakesTheSameTimeAtAnyTickRate() {
        double slow = moveTowardsBySteps(0.05, 20);
        double fast = moveTowardsBySteps(1.0 / 60.0, 60);

        assertEquals(slow, fast, 1.0E-9, "the same elapsed time reaches the same value at 20 and 60 ticks per second");
        assertEquals(1.0, slow, 1.0E-9, "a full duration reaches the target exactly");
    }

    @Test
    void anApproachNeverPassesItsTarget() {
        assertEquals(1.0, AnimationBlending.moveTowards(0.99, 1.0, 0.1, 0.1), 0.0);
        assertEquals(0.0, AnimationBlending.moveTowards(0.01, 0.0, 0.1, 0.1), 0.0);
        assertEquals(0.5, AnimationBlending.moveTowards(0.5, 1.0, 0.0, 0.1), 0.0,
                "a zero-length tick does not move anything");
        assertEquals(1.0, AnimationBlending.moveTowards(0.5, 1.0, 1.0, 0.0), 0.0,
                "a zero duration is an instant jump, not a division by zero");
    }

    @Test
    void nonFiniteInputsCannotCorruptAValue() {
        assertEquals(0.4, AnimationBlending.moveTowards(Double.NaN, 0.4, 0.05, 0.1), 0.0);
        assertEquals(0.4, AnimationBlending.moveTowards(0.4, Double.NaN, 0.05, 0.1), 0.0);
        assertEquals(0.4, AnimationBlending.moveTowards(0.4, 1.0, Double.NaN, 0.1), 0.0);
        assertEquals(0.0, AnimationBlending.clamp01(Double.NaN), 0.0);
        assertEquals(1.0, AnimationBlending.clamp01(Double.POSITIVE_INFINITY), 0.0);
    }

    @Test
    void smoothstepKeepsItsEndpointsExactAndItsVelocityZero() {
        assertEquals(0.0, AnimationBlending.smoothstep(0.0), 0.0);
        assertEquals(0.5, AnimationBlending.smoothstep(0.5), 1.0E-9);
        assertEquals(1.0, AnimationBlending.smoothstep(1.0), 0.0);
        assertEquals(0.0, AnimationBlending.smoothstep(-3.0), 0.0, "an out-of-range input is clamped");

        // A tiny step at either end moves the value less than a linear ramp would, which is what "zero velocity" means.
        double atStart = AnimationBlending.smoothstep(0.02);
        double atEnd = 1.0 - AnimationBlending.smoothstep(0.98);
        assertTrue(atStart < 0.02, "the fade starts slower than linear");
        assertTrue(atEnd < 0.02, "and ends slower than linear");
    }

    @Test
    void anExponentialApproachIsSmoothAndFrameRateIndependent() {
        double slow = approachBySteps(0.05, 20);
        double fast = approachBySteps(1.0 / 60.0, 60);

        assertEquals(slow, fast, 1.0E-9, "the same elapsed time reaches the same value at any frame rate");
        assertEquals(1.0 - Math.exp(-3.0), slow, 1.0E-9, "one documented duration covers about 95% of the move");
        assertEquals(1.0, AnimationBlending.approach(0.0, 1.0, 5.0, 1.0), 0.0,
                "and a settled value snaps exactly to its target");

        double firstStep = AnimationBlending.approach(0.0, 1.0, 0.05, 0.26);
        assertTrue(firstStep > 0.0 && firstStep < 0.5,
                "the follow starts smoothly instead of covering the whole distance in one step");
        double lateStep = AnimationBlending.approach(0.9, 1.0, 0.05, 0.26);
        assertTrue(lateStep > 0.9 && lateStep < 1.0, "and eases out as it approaches the target");
    }

    private static double approachBySteps(double deltaSeconds, int steps) {
        double value = 0.0;
        for (int step = 0; step < steps; step++) {
            value = AnimationBlending.approach(value, 1.0, deltaSeconds, 1.0);
        }
        return value;
    }

    private static double moveTowardsBySteps(double deltaSeconds, int steps) {
        double value = 0.0;
        for (int step = 0; step < steps; step++) {
            value = AnimationBlending.moveTowards(value, 1.0, deltaSeconds, 1.0);
        }
        return value;
    }
}
