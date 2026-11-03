package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * The locomotion states of the first-person weapon, named and ordered after Vic's Point Blank's
 * {@code FirstPersonWalkingAnimationHandler.PlayerWalkingState}.
 *
 * <p>The state decides <em>which</em> locomotion clip is sampled and <em>whether</em> a transition is allowed; it does not
 * decide the pose algebra, which stays this framework's overlay. Two states are deliberately different from the
 * reference's:</p>
 * <ul>
 *   <li>{@link #STANDING} produces <strong>no overlay at all</strong>, so the weapon's own idle clip keeps the pose. Point
 *   Blank has a dedicated {@code standing} clip in the same controller; here the idle base clip already is that pose, and
 *   adding a second one would make two clips fight over the same bones.</li>
 *   <li>{@link #WALKING_AIMING} is sampled from an authored {@code walk_aiming} clip when the pack has one and from the
 *   directional walk blend otherwise, so a pack written before this state existed keeps working.</li>
 * </ul>
 */
public enum LocomotionState {

    /** No locomotion layer is contributing: third person, nothing held, or the weapon is being drawn. */
    NONE,

    /** On the ground, not moving: the idle base clip owns the pose. */
    STANDING,

    /** On the ground, moving forward, not aiming, not sprinting. */
    WALKING,

    /** On the ground, moving, aiming: the reference's {@code walking_aiming}. */
    WALKING_AIMING,

    /** On the ground, moving backwards. */
    WALKING_BACKWARD,

    /** On the ground, strafing left. */
    WALKING_LEFT,

    /** On the ground, strafing right. */
    WALKING_RIGHT,

    /** Sprinting: {@code run_start} plays once and must finish before {@link #RUNNING}. */
    PREPARE_RUNNING,

    /** Sprinting on the ground: {@code run} loops. */
    RUNNING,

    /** The sprint stopped: {@code run_end} plays once and must finish before a ground state. */
    COMPLETE_RUNNING,

    /** Off the ground while not sprinting. */
    OFF_GROUND,

    /** Off the ground while sprinting. */
    OFF_GROUND_SPRINTING;

    /** @return whether this state walks on the ground (all four direction states plus the aiming one). */
    public boolean isWalking() {
        return this == WALKING || this == WALKING_AIMING || this == WALKING_BACKWARD || this == WALKING_LEFT
                || this == WALKING_RIGHT;
    }

    /** @return whether this state is one of the two airborne states. */
    public boolean isOffGround() {
        return this == OFF_GROUND || this == OFF_GROUND_SPRINTING;
    }

    /** @return whether this state plays one of the three running clips. */
    public boolean isRunningSequence() {
        return this == PREPARE_RUNNING || this == RUNNING || this == COMPLETE_RUNNING;
    }

    /** @return whether this state produces a locomotion pose at all. */
    public boolean contributes() {
        return this != NONE && this != STANDING;
    }
}
