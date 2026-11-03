package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The stabiliser between the raw per-tick locomotion sample and the state machine: the reference's two intent graces, its
 * startup airborne gate, the one-tick airborne confirmation and the walk speed provider.
 *
 * <p>Every case here is a reported jitter source: a one-tick gap in the input, a sprint boundary, a step down a block, a draw
 * followed immediately by a jump, and a walk cycle that does not follow the player's speed.</p>
 */
class LocomotionStabilizerTest {

    /** One client tick, in seconds. */
    private static final double TICK = 0.05;

    private static final double REFERENCE_SPEED = 0.13;

    private static LocomotionSample sample(double forward, boolean onGround, double verticalVelocity,
                                           boolean sprintKey, boolean sprinting, boolean keysHeld,
                                           double secondsSinceReset) {
        return new LocomotionSample(forward, 0.0, keysHeld, false, false, false, sprintKey, sprinting, onGround,
                verticalVelocity, false, REFERENCE_SPEED, REFERENCE_SPEED, secondsSinceReset);
    }

    /** A player holding forward, on the ground, not sprinting, long settled. */
    private static LocomotionSample walking() {
        return sample(1.0, true, 0.0, false, false, true, 99.0);
    }

    /** A player who released everything. */
    private static LocomotionSample still() {
        return sample(0.0, true, 0.0, false, false, false, 99.0);
    }

    @Test
    void aOneTickGapKeepsTheMovementIntent() {
        LocomotionStabilizer stabilizer = new LocomotionStabilizer();
        stabilizer.update(walking(), TICK);

        MovementState released = stabilizer.update(still(), TICK);

        assertTrue(released.intent().movementIntent(),
                "a single tick without input still counts as movement, so the walk does not restart");
    }

    @Test
    void theMovementIntentEndsAfterItsGrace() {
        LocomotionStabilizer stabilizer = new LocomotionStabilizer();
        stabilizer.update(walking(), TICK);

        MovementState state = null;
        for (int tick = 0; tick < 4; tick++) {
            state = stabilizer.update(still(), TICK);
        }

        assertFalse(state.intent().movementIntent(), "after 120 ms the player is really standing");
    }

    @Test
    void aKeyAloneCountsAsMovementIntent() {
        LocomotionStabilizer stabilizer = new LocomotionStabilizer();

        MovementState state = stabilizer.update(sample(0.0, true, 0.0, false, false, true, 99.0), TICK);

        assertTrue(state.intent().movementIntent(),
                "the reference's intent accepts the keys as well as the analog vector");
        assertTrue(state.intent().forwardKey());
    }

    @Test
    void theSprintIntentSurvivesAShortDropOfTheKey() {
        LocomotionStabilizer stabilizer = new LocomotionStabilizer();
        stabilizer.update(sample(1.0, true, 0.0, true, true, true, 99.0), TICK);

        MovementState boundary = stabilizer.update(sample(1.0, true, 0.0, false, false, true, 99.0), TICK);

        assertTrue(boundary.intent().sprintIntent(),
                "a one-tick drop of the sprint key must not send the run through its end clip");
    }

    @Test
    void theSprintIntentEndsAfterItsGrace() {
        LocomotionStabilizer stabilizer = new LocomotionStabilizer();
        stabilizer.update(sample(1.0, true, 0.0, true, true, true, 99.0), TICK);

        MovementState state = null;
        for (int tick = 0; tick < 4; tick++) {
            state = stabilizer.update(sample(1.0, true, 0.0, false, false, true, 99.0), TICK);
        }

        assertFalse(state.intent().sprintIntent());
    }

    @Test
    void aJumpCountsAsAirborneImmediately() {
        LocomotionStabilizer stabilizer = new LocomotionStabilizer();
        stabilizer.update(walking(), TICK);

        MovementState jump = stabilizer.update(sample(1.0, false, 0.42, false, false, true, 99.0), TICK);

        assertTrue(jump.airborne(), "a real jump has a large vertical velocity and enters the airborne state at once");
        assertEquals(0.42, jump.verticalVelocity(), 1.0E-9,
                "the player's real vertical velocity reaches the animation layer, which is what makes the jump "
                        + "reaction follow the actual movement instead of a clock");
    }

    @Test
    void steppingDownABlockIsFilteredForOneTick() {
        LocomotionStabilizer stabilizer = new LocomotionStabilizer();
        stabilizer.update(walking(), TICK);

        MovementState step = stabilizer.update(sample(1.0, false, -0.02, false, false, true, 99.0), TICK);
        assertFalse(step.airborne(), "a small downward velocity is a step, not a jump");

        MovementState falling = stabilizer.update(sample(1.0, false, -0.02, false, false, true, 99.0), TICK);
        assertTrue(falling.airborne(), "a loss of contact that lasts is airborne after one tick of confirmation");
    }

    @Test
    void theStartupGateKeepsADrawFromProducingAJumpPose() {
        LocomotionStabilizer stabilizer = new LocomotionStabilizer();
        LocomotionSample justDrew = sample(1.0, false, 0.0, false, false, true, 0.0);

        MovementState state = null;
        for (int tick = 0; tick < 5; tick++) {
            state = stabilizer.update(justDrew, TICK);
        }

        assertFalse(state.airborne(),
                "within 450 ms of a reset a moving player is not treated as airborne without a real jump");
        assertFalse(state.intent().offGroundAllowed());
    }

    @Test
    void theStartupGateOpensOnceTheResetIsOldEnough() {
        LocomotionStabilizer stabilizer = new LocomotionStabilizer();
        LocomotionSample settled = sample(1.0, false, 0.0, false, false, true, 1.0);

        MovementState state = null;
        for (int tick = 0; tick < 2; tick++) {
            state = stabilizer.update(settled, TICK);
        }

        assertTrue(state.airborne());
        assertTrue(state.intent().offGroundAllowed());
    }

    @Test
    void thePlaybackRateFollowsThePlayersSpeed() {
        assertEquals(1.0, LocomotionStabilizer.speedScale(REFERENCE_SPEED, REFERENCE_SPEED, false, false), 1.0E-9,
                "walking at the reference speed plays the clip at its authored rate");
        assertEquals(1.0, LocomotionStabilizer.speedScale(REFERENCE_SPEED * 1.3, REFERENCE_SPEED, true, false), 1.0E-9,
                "sprinting raises the reference speed by the same 30% the reference uses");
        assertEquals(Math.sqrt(0.5), LocomotionStabilizer.speedScale(REFERENCE_SPEED * 0.5, REFERENCE_SPEED, false,
                false), 1.0E-9, "half speed plays the clip at sqrt(0.5), not at half rate");
        assertEquals(Math.sqrt(0.1), LocomotionStabilizer.speedScale(0.0, REFERENCE_SPEED, false, false), 1.0E-9,
                "a stationary player is clamped instead of freezing the clip");
    }

    @Test
    void movingSlowlyScalesThePlaybackRateDown() {
        double normal = LocomotionStabilizer.speedScale(REFERENCE_SPEED, REFERENCE_SPEED, false, false);
        double slow = LocomotionStabilizer.speedScale(REFERENCE_SPEED, REFERENCE_SPEED, false, true);

        assertEquals(normal * Math.sqrt(0.6), slow, 1.0E-9);
    }

    @Test
    void resetForgetsEveryGrace() {
        LocomotionStabilizer stabilizer = new LocomotionStabilizer();
        stabilizer.update(walking(), TICK);

        stabilizer.reset();

        assertEquals(Double.MAX_VALUE, stabilizer.secondsSinceMovementIntent());
        assertEquals(0.0, stabilizer.offGroundSeconds(), 1.0E-9);
        assertFalse(stabilizer.update(still(), 0.0).intent().movementIntent(),
                "with no time passed the intent is empty until the next sample");
    }
}
