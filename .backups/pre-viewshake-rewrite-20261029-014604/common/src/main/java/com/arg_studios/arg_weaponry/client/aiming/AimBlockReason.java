package com.arg_studios.arg_weaponry.client.aiming;

/**
 * Why aim-down-sights cannot be entered, kept as a value so the client can report the reason exactly once per change.
 *
 * <p>A refusal is never silent in the log the first time it happens, and it never leaves the weapon in a half-aimed state:
 * every reason ends the aim transition the same way, by blending back to the ordinary placement.</p>
 */
public enum AimBlockReason {

    /** Aiming is allowed; no reason to report. */
    NONE,

    /** Nothing is held in the main hand, or the held item is not a weapon. */
    NO_WEAPON,

    /** The weapon's {@code aiming.enabled} is {@code false} or its {@code aiming} block is missing. */
    DISABLED,

    /** The weapon has no {@code aiming_view} bone, so there is no authored sight alignment to use. */
    NO_AIMING_BONE,

    /** The weapon's model, display or texture could not be resolved. */
    NO_MODEL,

    /** {@code aiming.no_aiming_on_inspect} is in force and an inspection is running. */
    INSPECTING,

    /** {@code aiming.no_aiming_on_reload} is in force and a reload is running. */
    RELOADING,

    /** The player is not alive. */
    NOT_ALIVE,

    /** The game is not in a state where world input is read (a screen is open, the mouse is not grabbed). */
    NO_INPUT_FOCUS;

    /** @return {@code true} when this reason prevents aiming. */
    public boolean blocks() {
        return this != NONE;
    }
}
