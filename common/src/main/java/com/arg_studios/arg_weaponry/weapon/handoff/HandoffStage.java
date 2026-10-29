package com.arg_studios.arg_weaponry.weapon.handoff;

/**
 * The stage of one handoff, mirroring the reference architecture (GWO {@code GunDrawHolsterHandler.State}).
 *
 * <p>{@link #HANDOFF} is a boundary stage, not a resting one: the controller enters it and leaves it inside the same
 * call, because it performs exactly two things at once - retire the outgoing visual weapon and adopt the incoming one -
 * and no frame may ever be rendered while both, or neither, are active.</p>
 */
public enum HandoffStage {

    /** No transition is running; the visual weapon is the selected one. */
    IDLE,

    /** The outgoing weapon is still the visual weapon and is playing its holster clip. */
    HOLSTERING,

    /** The boundary at which the outgoing visual weapon is retired and the incoming one becomes active. */
    HANDOFF,

    /** The incoming weapon is the visual weapon and is playing its draw clip. */
    DRAWING
}
