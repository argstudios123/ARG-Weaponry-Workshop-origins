package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * The locomotion state machine: the transition table ported from Vic's Point Blank.
 *
 * <p>Each case is one row of that table, driven through the public entry point so the ordering of the guards is exercised as
 * well: two transitions that both match must resolve to the earlier one, exactly as in the reference.</p>
 */
class LocomotionStateMachineTest {

    /** A tiny mutable builder, so each test states only the facts it is about. */
    private static final class F {
        private boolean applicable = true;
        private boolean movementIntent;
        private boolean sprintIntent;
        private boolean forward;
        private boolean backward;
        private boolean left;
        private boolean right;
        private boolean moving;
        private boolean onGround = true;
        private boolean sprinting;
        private boolean slow;
        private boolean aiming;
        private boolean firing;
        private boolean offGroundAllowed = true;
        private boolean postDrawGrace;
        private boolean prepareFinished = true;
        private boolean completeFinished = true;

        F move() {
            moving = true;
            movementIntent = true;
            return this;
        }

        F forward() {
            forward = true;
            return this;
        }

        F backward() {
            backward = true;
            return this;
        }

        F left() {
            left = true;
            return this;
        }

        F right() {
            right = true;
            return this;
        }

        F sprint() {
            sprintIntent = true;
            sprinting = true;
            return this;
        }

        F sprintIntentOnly() {
            sprintIntent = true;
            return this;
        }

        F airborne() {
            onGround = false;
            return this;
        }

        F aiming() {
            aiming = true;
            return this;
        }

        F firing() {
            firing = true;
            return this;
        }

        F postDrawGrace() {
            postDrawGrace = true;
            return this;
        }

        F prepareRunning() {
            prepareFinished = false;
            return this;
        }

        F completeRunning() {
            completeFinished = false;
            return this;
        }

        F applicable(boolean value) {
            applicable = value;
            return this;
        }

        LocomotionStateMachine.Facts build() {
            return new LocomotionStateMachine.Facts(applicable, movementIntent, sprintIntent, forward, backward, left,
                    right, moving, onGround, sprinting, slow, aiming, firing, offGroundAllowed, postDrawGrace,
                    prepareFinished, completeFinished);
        }
    }

    private static LocomotionState step(LocomotionStateMachine machine, F facts) {
        return machine.update(facts.build());
    }

    private static LocomotionStateMachine walkingForward() {
        LocomotionStateMachine machine = new LocomotionStateMachine();
        step(machine, new F());
        step(machine, new F().move().forward());
        return machine;
    }

    private static LocomotionStateMachine runningForward() {
        LocomotionStateMachine machine = walkingForward();
        step(machine, new F().move().forward().sprint());
        step(machine, new F().move().forward().sprint());
        return machine;
    }

    @Test
    void aSprintLostInTheAirReturnsToThePlainAirbornePose() {
        LocomotionStateMachine machine = runningForward();
        assertEquals(LocomotionState.RUNNING, machine.state(), "the sprint is running on the ground");

        // The player jumps: the sprinting airborne branch takes over.
        assertEquals(LocomotionState.OFF_GROUND_SPRINTING,
                step(machine, new F().move().forward().sprint().airborne()));

        // The sprint is released while still falling. The recovery belongs on the ground; in the air the plain airborne pose
        // takes over, so the weapon is not lowered to the hip mid-jump and the landing reaction is not skipped.
        assertEquals(LocomotionState.OFF_GROUND,
                step(machine, new F().move().forward().airborne()),
                "a sprint lost in the air is not a running-end recovery");

        // And the touch-down then goes to the ground state as usual.
        assertEquals(LocomotionState.WALKING, step(machine, new F().move().forward()));
    }

    @Test
    void aSprintKeptInTheAirStaysInTheSprintingBranch() {
        LocomotionStateMachine machine = runningForward();
        step(machine, new F().move().forward().sprint().airborne());

        assertEquals(LocomotionState.OFF_GROUND_SPRINTING, step(machine, new F().move().forward().sprint().airborne()),
                "holding the sprint keeps the sprinting airborne pose");
        assertEquals(LocomotionState.RUNNING, step(machine, new F().move().forward().sprint()),
                "and landing continues the run");
    }

    @Test
    void theFirstEntryIsStanding() {
        LocomotionStateMachine machine = new LocomotionStateMachine();

        assertEquals(LocomotionState.NONE, machine.state(), "nothing plays before the first update");
        assertEquals(LocomotionState.STANDING, step(machine, new F()));
    }

    @Test
    void standingWalksForwardAndReturnsToStanding() {
        LocomotionStateMachine machine = new LocomotionStateMachine();
        step(machine, new F());

        assertEquals(LocomotionState.WALKING, step(machine, new F().move().forward()));
        assertEquals(LocomotionState.STANDING, step(machine, new F()),
                "no movement intent means the idle pose, which is what removes the walk/idle flicker");
    }

    @Test
    void theOtherDirectionsHaveTheirOwnStates() {
        LocomotionStateMachine machine = new LocomotionStateMachine();
        step(machine, new F());

        assertEquals(LocomotionState.WALKING_BACKWARD, step(machine, new F().move().backward()));
        assertEquals(LocomotionState.WALKING_LEFT, step(machine, new F().move().left()));
        assertEquals(LocomotionState.WALKING_RIGHT, step(machine, new F().move().right()));
    }

    @Test
    void forwardWinsOverTheOtherDirections() {
        LocomotionStateMachine machine = new LocomotionStateMachine();
        step(machine, new F());

        assertEquals(LocomotionState.WALKING, step(machine, new F().move().forward().backward().left().right()),
                "the reference's transition order puts forward first");
    }

    @Test
    void walkingWhileAimingUsesTheAimingState() {
        LocomotionStateMachine machine = new LocomotionStateMachine();
        step(machine, new F());

        assertEquals(LocomotionState.WALKING_AIMING, step(machine, new F().move().forward().aiming()));
    }

    @Test
    void sprintingGoesThroughPrepareRunningThenRunning() {
        LocomotionStateMachine machine = new LocomotionStateMachine();
        step(machine, new F().move().forward());

        assertEquals(LocomotionState.PREPARE_RUNNING, step(machine, new F().move().forward().sprint()));
        assertEquals(LocomotionState.PREPARE_RUNNING, step(machine,
                new F().move().forward().sprint().prepareRunning()),
                "the prepare clip has not finished, so the run must not start");
        assertEquals(LocomotionState.RUNNING, step(machine, new F().move().forward().sprint()));
    }

    @Test
    void releasingSprintDuringPrepareGoesToCompleteRunning() {
        LocomotionStateMachine machine = new LocomotionStateMachine();
        step(machine, new F().move().forward());
        step(machine, new F().move().forward().sprint());

        assertEquals(LocomotionState.COMPLETE_RUNNING, step(machine, new F().move().forward()),
                "the prepare phase is cut short through the run-end clip, which carries the phase over");
    }

    @Test
    void completeRunningOwnsTheStateUntilItsClipFinished() {
        LocomotionStateMachine machine = new LocomotionStateMachine();
        step(machine, new F().move().forward());
        step(machine, new F().move().forward().sprint());
        step(machine, new F().move().forward());

        assertEquals(LocomotionState.COMPLETE_RUNNING, step(machine, new F().move().forward().completeRunning()),
                "no ground state is entered while run_end is still playing");
        assertEquals(LocomotionState.WALKING, step(machine, new F().move().forward()),
                "and once it finished the player continues walking, in one step");
    }

    @Test
    void walkingOffALedgeEntersTheAirborneStateAndLandingReturnsToWalking() {
        LocomotionStateMachine machine = walkingForward();

        assertEquals(LocomotionState.OFF_GROUND, step(machine, new F().move().forward().airborne()));
        assertEquals(LocomotionState.WALKING, step(machine, new F().move().forward()),
                "landing goes straight to the movement state, never through a landing clip");
    }

    @Test
    void anAirbornePlayerWithoutMovementIntentStandsOnLanding() {
        LocomotionStateMachine machine = new LocomotionStateMachine();
        step(machine, new F());
        step(machine, new F().move().forward().airborne());

        assertEquals(LocomotionState.STANDING, step(machine, new F()));
    }

    @Test
    void touchingDownAlwaysLeavesTheAirborneState() {
        LocomotionStateMachine machine = walkingForward();
        assertEquals(LocomotionState.OFF_GROUND, step(machine, new F().move().forward().airborne()),
                "the player leaves the ground");

        // Sprint intent with only a lateral input: every walking rule refuses a sprint intent
        // (!sprintIntent || slow) and the prepare-run rule needs a forward input, so no reference guard
        // matches. Before the touch-down safety net the machine stayed in OFF_GROUND while the player stood
        // on the ground: airTime kept advancing, the jump loop kept playing and the weapon kept floating.
        assertEquals(LocomotionState.WALKING_RIGHT, step(machine, new F().move().right().sprint()),
                "the feet are on the ground, so the pose must be a ground pose on the same tick");
    }

    @Test
    void touchingDownWhileAimingAndMovingBackwardsReturnsToTheAimWalk() {
        LocomotionStateMachine machine = walkingForward();
        assertEquals(LocomotionState.OFF_GROUND, step(machine, new F().move().forward().aiming().airborne()));

        assertEquals(LocomotionState.WALKING_AIMING, step(machine, new F().move().backward().aiming()),
                "the aim walk is the only ground destination an aiming player has");
    }

    @Test
    void aSprintJumpUsesTheSprintingAirborneStateAndResumesRunning() {
        LocomotionStateMachine machine = runningForward();
        assertEquals(LocomotionState.RUNNING, machine.state());

        assertEquals(LocomotionState.OFF_GROUND_SPRINTING,
                step(machine, new F().move().forward().sprint().airborne()));
        assertEquals(LocomotionState.RUNNING, step(machine, new F().move().forward().sprint()),
                "landing continues the sprint instead of ending it");
    }

    @Test
    void aFiringWeaponDoesNotStartASprint() {
        LocomotionStateMachine machine = walkingForward();

        assertEquals(LocomotionState.WALKING, step(machine, new F().move().forward().sprint().firing()),
                "the reference refuses the prepare phase while the gun is firing");
    }

    @Test
    void sprintingWithoutMovementIntentEndsTheRun() {
        LocomotionStateMachine machine = runningForward();

        assertEquals(LocomotionState.COMPLETE_RUNNING, step(machine, new F().sprintIntentOnly()));
    }

    @Test
    void thePostDrawGraceSuppressesWalkingButNotStanding() {
        LocomotionStateMachine machine = new LocomotionStateMachine();
        step(machine, new F());

        assertEquals(LocomotionState.STANDING, step(machine, new F().move().forward().postDrawGrace()),
                "a weapon that was just drawn does not start bobbing immediately");
    }

    @Test
    void aimingCancelsARunningSprintStraightToTheAimingWalk() {
        LocomotionStateMachine machine = runningForward();

        assertEquals(LocomotionState.WALKING_AIMING,
                step(machine, new F().move().forward().sprint().aiming()),
                "the run_end recovery must not play after ADS starts");
    }

    @Test
    void aimingFromASprintJumpUsesThePlainAirborneState() {
        LocomotionStateMachine machine = runningForward();
        step(machine, new F().move().forward().sprint().airborne());

        assertEquals(LocomotionState.OFF_GROUND,
                step(machine, new F().move().forward().sprint().aiming().airborne()),
                "an aimed airborne player never keeps the sprint-jump pose");
    }

    @Test
    void aSprintFactCannotStartARunWhileAiming() {
        LocomotionStateMachine machine = walkingForward();

        LocomotionState next = step(machine, new F().move().forward().sprint().aiming());

        assertEquals(LocomotionState.WALKING, next,
                "an inconsistent sprint fact still cannot enter a sprint state while aiming");
        assertFalse(next.isRunningSequence());
    }

    @Test
    void releasingAimAllowsTheSprintAgain() {
        LocomotionStateMachine machine = walkingForward();
        step(machine, new F().move().forward().aiming());

        assertEquals(LocomotionState.PREPARE_RUNNING, step(machine, new F().move().forward().sprint()),
                "the restriction is not permanent");
    }

    @Test
    void notApplicableForcesNone() {
        LocomotionStateMachine machine = walkingForward();

        assertEquals(LocomotionState.NONE, step(machine, new F().move().forward().applicable(false)));
        assertEquals(LocomotionState.STANDING, step(machine, new F()),
                "and the machine starts over when first person returns");
    }

    @Test
    void landingWhileAimingAndMovingReturnsToTheAimingWalk() {
        LocomotionStateMachine machine = walkingForward();
        assertEquals(LocomotionState.WALKING_AIMING, step(machine, new F().move().forward().aiming()));

        assertEquals(LocomotionState.OFF_GROUND,
                step(machine, new F().move().forward().aiming().airborne()), "the aiming walk can still jump");

        assertEquals(LocomotionState.WALKING_AIMING, step(machine, new F().move().forward().aiming()),
                "landing while aiming must continue in the aiming walk: every ordinary walking rule refuses an "
                        + "aiming player, so without an airborne source the weapon stays in the jump pose");
    }

    @Test
    void landingWhileAimingAtAStandstillReturnsToStanding() {
        LocomotionStateMachine machine = new LocomotionStateMachine();
        step(machine, new F());
        assertEquals(LocomotionState.OFF_GROUND, step(machine, new F().aiming().airborne()));

        assertEquals(LocomotionState.STANDING, step(machine, new F().aiming()),
                "a jump from a standstill lands back into the idle pose, aiming or not");
    }
}
