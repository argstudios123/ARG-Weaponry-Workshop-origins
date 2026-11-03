package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * The first-person locomotion state machine, a port of Vic's Point Blank's
 * {@code FirstPersonWalkingAnimationHandler#createFirstPersonAnimationStateMachine()} (26.1.2 / 2.2.0).
 *
 * <h2>What was ported, and how it was read</h2>
 * <p>The reference builds its machine from twenty-four {@code withTransition(from, to, guard)} registrations whose guards
 * are lambdas over a {@code PlayerWalkingStateContext}. The guards below are that table, in the same order, with the same
 * meaning; the grace windows, the direction keys, the sprint-intent semantics and the draw gates are taken from it rather
 * than invented. The important, non-obvious properties are:</p>
 * <ul>
 *   <li><strong>One-shots own the state until they finish.</strong> Every transition that leaves {@code COMPLETE_RUNNING}
 *   carries {@code !isPlayingAnimation(complete_running)}, and the transition into {@code RUNNING} carries
 *   {@code !isPlayingAnimation(prepare_running)}. Here that is {@code completeFinished} / {@code prepareFinished}: the
 *   terminal clip plays out, and only then does the machine move on. This is what makes a sprint read as one movement
 *   instead of a stack of interruptions.</li>
 *   <li><strong>Sprint intent outranks the walking states.</strong> All four walking states require
 *   {@code !sprintIntent || slow}, so holding the sprint key can never flicker between walk and run.</li>
 *   <li><strong>Landing goes straight to a movement state.</strong> {@code OFF_GROUND} transitions to
 *   {@code WALKING}/{@code WALKING_*}/{@code STANDING}/{@code PREPARE_RUNNING} directly - never through a "landing" state
 *   that would restart a cycle.</li>
 *   <li><strong>Airborne sprinting is its own state</strong> ({@code OFF_GROUND_SPRINTING}) and returns to
 *   {@code RUNNING} on contact, so a sprint-jump continues the running cycle instead of ending it.</li>
 *   <li><strong>A draw suppresses walking for a moment</strong> ({@code postDrawGrace}, 90 ms in the reference), which keeps
 *   the weapon from bobbing before it is in the hands.</li>
 * </ul>
 *
 * <p>Pure and clock-free: the caller passes the facts and receives the state, so the whole table is unit tested without a
 * game.</p>
 */
public final class LocomotionStateMachine {

    /**
     * The facts one evaluation needs.
     *
     * @param applicable       whether a first-person locomotion layer may exist at all (a held weapon in first person);
     *                         {@code false} forces {@link LocomotionState#NONE}
     * @param movementIntent   movement intent with the grace period, see {@link LocomotionIntent}
     * @param sprintIntent     sprint intent with the grace period, see {@link LocomotionIntent}
     * @param forward          whether the forward key is held
     * @param backward         whether the backward key is held
     * @param left             whether the left key is held
     * @param right            whether the right key is held
     * @param moving           whether the analog move vector is non-zero
     * @param onGround         whether the player is on the ground
     * @param sprinting        whether the player is sprinting (the stabilised flag, not the raw key)
     * @param slow             whether the player is moving slowly in vanilla's sense
     * @param aiming           whether the weapon is aiming
     * @param firing           whether a shot clip is playing
     * @param offGroundAllowed whether an airborne state may be entered, see {@link LocomotionIntent#offGroundAllowed()}
     * @param postDrawGrace    whether the post-draw locomotion grace is still running
     * @param prepareFinished  whether the prepare-running clip has finished (or does not exist)
     * @param completeFinished whether the complete-running clip has finished (or does not exist)
     */
    public record Facts(boolean applicable, boolean movementIntent, boolean sprintIntent, boolean forward,
                        boolean backward, boolean left, boolean right, boolean moving, boolean onGround,
                        boolean sprinting, boolean slow, boolean aiming, boolean firing, boolean offGroundAllowed,
                        boolean postDrawGrace, boolean prepareFinished, boolean completeFinished) {
    }

    private LocomotionState state = LocomotionState.NONE;

    /** @return the current state, never {@code null}. */
    public LocomotionState state() {
        return state;
    }

    /**
     * Evaluates one tick and advances the machine if a transition fires.
     *
     * @param facts the facts of this tick
     * @return the state after this tick, never {@code null}
     */
    public LocomotionState update(Facts facts) {
        state = next(facts, state);
        return state;
    }

    /** Forgets the current state, so the next update starts from {@link LocomotionState#NONE}. */
    public void reset() {
        state = LocomotionState.NONE;
    }

    /**
     * Adopts a state without evaluating a tick.
     *
     * <p>Used when a locomotion track is carried from one weapon to another: the incoming weapon's machine has to continue
     * from the state the outgoing one ended in, so the transition table sees the same {@code from} as before and cannot fire
     * a spurious entry (or skip the guard that keeps a one-shot clip owned).</p>
     *
     * @param adopted the state to continue from, {@code null} means {@link LocomotionState#NONE}
     */
    public void seed(LocomotionState adopted) {
        state = adopted == null ? LocomotionState.NONE : adopted;
    }

    /**
     * The transition table, in the reference's registration order.
     *
     * @param facts the facts of this tick
     * @param from  the current state
     * @return the state this tick ends in
     */
    static LocomotionState next(Facts facts, LocomotionState from) {
        if (!facts.applicable()) {
            return LocomotionState.NONE;
        }
        // 1. First entry: the idle pose. The machine only exists inside first person with a weapon in hand.
        if (from == LocomotionState.NONE) {
            return LocomotionState.STANDING;
        }
        // 1a. Aiming cancels the running branch immediately. Waiting for a run-end recovery would leave sprint animation on
        // screen after ADS started; the aiming walk (or the idle pose when standing) is the only valid destination. This
        // also covers a sprint that was cancelled on the same tick by the player-side restriction.
        if (facts.aiming() && (from.isRunningSequence() || from == LocomotionState.OFF_GROUND_SPRINTING)) {
            if (!facts.onGround()) {
                return LocomotionState.OFF_GROUND;
            }
            return facts.moving() && !facts.postDrawGrace() ? LocomotionState.WALKING_AIMING : LocomotionState.STANDING;
        }

        boolean grounded = from == LocomotionState.STANDING || from.isWalking();
        boolean airborneSource = grounded || from == LocomotionState.OFF_GROUND;

        // 2. Left the ground while standing or walking, and the sprint does not own this jump.
        if (grounded && !facts.onGround() && !facts.sprinting() && facts.offGroundAllowed()) {
            return LocomotionState.OFF_GROUND;
        }
        // 3. The last running step took the player off the ground forward.
        if (from == LocomotionState.COMPLETE_RUNNING && facts.moving() && !facts.onGround()
                && (!facts.sprinting() || facts.slow()) && facts.forward() && !facts.aiming()
                && facts.offGroundAllowed() && facts.completeFinished()) {
            return LocomotionState.OFF_GROUND;
        }
        // 4. A sprint-jump keeps the sprinting branch.
        if ((from == LocomotionState.RUNNING || from == LocomotionState.OFF_GROUND_SPRINTING)
                && !facts.onGround() && facts.sprinting() && !facts.aiming() && facts.offGroundAllowed()) {
            return LocomotionState.OFF_GROUND_SPRINTING;
        }
        // 5. Jumping out of the prepare phase: the run has not started, so the airborne sprint pose takes over.
        if (from == LocomotionState.PREPARE_RUNNING && facts.sprintIntent() && !facts.aiming() && !facts.onGround()
                && !facts.slow() && facts.offGroundAllowed() && facts.prepareFinished()) {
            return LocomotionState.OFF_GROUND_SPRINTING;
        }
        // 6. Walking while aiming has its own pose.
        // <p>The airborne state is a source here as well, and it must be: every ordinary walking rule below refuses an
        // aiming player, so the aim walk is the <em>only</em> ground destination an aiming player has. Without the
        // airborne source, a player who lands while still aiming and moving matches no rule at all, the machine stays in
        // {@code OFF_GROUND} with the player already on the ground - the jump pose keeps playing (its airtime clock keeps
        // running, so the loop never ends), the landing reaction can never start because no touch-down edge is crossed,
        // and the aim walk only resumes once the aim or the movement stops. That is the jump glitch that was reported as
        // "worse while aiming". The reference's directional from-lists contain {@code off_ground} for exactly this reason;
        // this is the aiming branch of the same rule.</p>
        if ((grounded || from == LocomotionState.OFF_GROUND) && facts.moving() && facts.onGround()
                && !facts.sprintIntent() && !facts.postDrawGrace() && facts.aiming()) {
            return LocomotionState.WALKING_AIMING;
        }
        if (from == LocomotionState.COMPLETE_RUNNING && facts.moving() && facts.onGround() && !facts.sprintIntent()
                && facts.aiming() && facts.completeFinished()) {
            return LocomotionState.WALKING_AIMING;
        }
        // 7. Directional walking, in the reference's priority order: forward, backward, left, right.
        if (walkingFrom(from, LocomotionState.WALKING) && facts.moving() && facts.onGround()
                && (!facts.sprintIntent() || facts.slow()) && !facts.postDrawGrace() && facts.forward()
                && !facts.aiming()) {
            return LocomotionState.WALKING;
        }
        if (from == LocomotionState.COMPLETE_RUNNING && facts.moving() && facts.onGround()
                && (!facts.sprintIntent() || facts.slow()) && facts.forward() && !facts.aiming()
                && facts.completeFinished()) {
            return LocomotionState.WALKING;
        }
        if (walkingFrom(from, LocomotionState.WALKING_BACKWARD) && facts.moving() && facts.onGround()
                && (!facts.sprinting() || facts.slow()) && !facts.postDrawGrace() && facts.backward()
                && !facts.aiming()) {
            return LocomotionState.WALKING_BACKWARD;
        }
        if (from == LocomotionState.COMPLETE_RUNNING && facts.moving() && facts.onGround()
                && (!facts.sprinting() || facts.slow()) && facts.backward() && !facts.aiming()
                && facts.completeFinished()) {
            return LocomotionState.WALKING_BACKWARD;
        }
        if (walkingFrom(from, LocomotionState.WALKING_LEFT) && facts.moving() && facts.onGround()
                && (!facts.sprinting() || facts.slow()) && !facts.postDrawGrace() && facts.left() && !facts.aiming()) {
            return LocomotionState.WALKING_LEFT;
        }
        if (from == LocomotionState.COMPLETE_RUNNING && facts.moving() && facts.onGround()
                && (!facts.sprinting() || facts.slow()) && facts.left() && !facts.aiming()
                && facts.completeFinished()) {
            return LocomotionState.WALKING_LEFT;
        }
        if (walkingFrom(from, LocomotionState.WALKING_RIGHT) && facts.moving() && facts.onGround()
                && (!facts.sprinting() || facts.slow()) && !facts.postDrawGrace() && facts.right() && !facts.aiming()) {
            return LocomotionState.WALKING_RIGHT;
        }
        if (from == LocomotionState.COMPLETE_RUNNING && facts.moving() && facts.onGround()
                && (!facts.sprinting() || facts.slow()) && facts.right() && !facts.aiming()
                && facts.completeFinished()) {
            return LocomotionState.WALKING_RIGHT;
        }
        // 8. Nothing held any more: the idle pose.
        if (grounded && from != LocomotionState.STANDING && facts.onGround() && !facts.movementIntent()) {
            return LocomotionState.STANDING;
        }
        if (from == LocomotionState.OFF_GROUND && facts.onGround() && !facts.movementIntent()) {
            return LocomotionState.STANDING;
        }
        if (from == LocomotionState.COMPLETE_RUNNING && facts.onGround() && !facts.movementIntent()
                && facts.completeFinished()) {
            return LocomotionState.STANDING;
        }
        // 9. Sprinting forward: the prepare phase, from the ground or from the air.
        if (airborneSource && facts.sprintIntent() && !facts.aiming() && facts.moving() && !facts.postDrawGrace()
                && facts.forward() && !facts.slow() && !facts.firing()) {
            return LocomotionState.PREPARE_RUNNING;
        }
        if (from == LocomotionState.COMPLETE_RUNNING && facts.sprintIntent() && !facts.aiming() && facts.moving()
                && !facts.postDrawGrace() && facts.forward() && !facts.slow() && !facts.firing()
                && facts.completeFinished()) {
            return LocomotionState.PREPARE_RUNNING;
        }
        // 10. The running sequence itself.
        if (from == LocomotionState.OFF_GROUND_SPRINTING && facts.onGround() && !facts.aiming()) {
            return LocomotionState.RUNNING;
        }
        // 10a. A sprint lost while still airborne is not a running-end recovery.
        // <p>The recovery is a ground transition: playing it in the air lowers the weapon to the hip while the player is
        // still falling, freezes the airborne clip mid-jump and skips the landing reaction, because the touch-down then
        // comes from this state instead of an airborne one. The player keeps the airborne pose (the sprinting and the
        // plain one share the airborne clock, so the jump does not restart) and the ground decides what comes next.</p>
        if (from == LocomotionState.OFF_GROUND_SPRINTING && !facts.onGround() && !shouldKeepRunning(facts)) {
            return LocomotionState.OFF_GROUND;
        }
        if (from == LocomotionState.PREPARE_RUNNING && facts.sprintIntent() && facts.movementIntent() && facts.onGround()
                && facts.forward() && !facts.slow() && facts.prepareFinished()) {
            return LocomotionState.RUNNING;
        }
        if (from == LocomotionState.PREPARE_RUNNING && !shouldKeepRunning(facts)) {
            return LocomotionState.COMPLETE_RUNNING;
        }
        if ((from == LocomotionState.RUNNING || from == LocomotionState.OFF_GROUND_SPRINTING)
                && !shouldKeepRunning(facts)) {
            return LocomotionState.COMPLETE_RUNNING;
        }
        // 11. Touch-down safety net: an airborne state may never survive the tick the player is back on the ground.
        // <p>The rules above encode the reference's guards, and a guard combination with no match used to leave the
        // machine in {@code OFF_GROUND} while the player stood on the ground - a sprint intent held with only a strafe
        // input is one such combination, because every walking rule refuses a sprint intent and the prepare-run rule
        // requires a forward input. In that stale state {@code airTime} kept advancing, so the jump loop kept playing and
        // the weapon stayed lifted above the crosshair although no jump was happening. The pose must return to a ground
        // state on the same tick the feet do; the landing reaction and the next tick's normal state selection still run.</p>
        if (from.isOffGround() && facts.onGround()) {
            if (facts.moving() && !facts.postDrawGrace()) {
                if (facts.aiming()) {
                    return LocomotionState.WALKING_AIMING;
                }
                if (facts.forward()) {
                    return LocomotionState.WALKING;
                }
                if (facts.backward()) {
                    return LocomotionState.WALKING_BACKWARD;
                }
                if (facts.left()) {
                    return LocomotionState.WALKING_LEFT;
                }
                if (facts.right()) {
                    return LocomotionState.WALKING_RIGHT;
                }
            }
            return LocomotionState.STANDING;
        }
        return from;
    }

    /**
     * @param facts the facts of this tick
     * @return whether the sprint goes on: the reference's
     *         {@code hasSprintIntent && !isMovingSlowly && hasMovementIntent && isMovingForward}
     */
    private static boolean shouldKeepRunning(Facts facts) {
        return facts.sprintIntent() && !facts.slow() && facts.movementIntent() && facts.forward();
    }

    /**
     * @param from    the current state
     * @param exclude the one walking state that must not transition to the caller's target
     * @return whether {@code from} may enter a directional walking state
     */
    private static boolean walkingFrom(LocomotionState from, LocomotionState exclude) {
        if (from == exclude) {
            return false;
        }
        // The reference's from-lists for the directional transitions are {off_ground, standing, every other walking state}.
        return from == LocomotionState.OFF_GROUND || from == LocomotionState.STANDING || from.isWalking();
    }
}


