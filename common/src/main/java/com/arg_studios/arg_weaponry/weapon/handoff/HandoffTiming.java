package com.arg_studios.arg_weaponry.weapon.handoff;

/**
 * The timing rules of one handoff.
 *
 * <p>Every duration in this subsystem comes from the clip itself, never from a declared number of ticks: the holster window
 * is the authored length of the holster clip and the draw window is the authored length of the draw clip, so the clip and
 * the window cannot drift apart when the client ticks slowly. This is the rule the reference implementation follows
 * ({@code AnimationDurationResolver.authoredDuration}), and it removes the reason the previous implementation needed a
 * "hold the terminal pose" patch.</p>
 *
 * <p>The deadline is a safety net only. A transition that is still running when its deadline passes is committed anyway,
 * so a clip that never reports completion - a broken animation document, a paused client, a missing resource - can never
 * leave the player with a weapon that is stuck mid-holster.</p>
 */
public final class HandoffTiming {

    /** How much longer than the clip a transition may take before it is forced to its outcome. */
    public static final double SAFETY_FACTOR = 1.5;

    /** The fixed part of the safety window, covering a stalled tick or two. */
    public static final double SAFETY_MARGIN_SECONDS = 0.25;

    private HandoffTiming() {
    }

    /**
     * @param clipSeconds the authored length of the clip that owns the stage, may be {@code 0} for "no clip"
     * @return the wall-clock deadline of that stage, measured on the same clock as {@link HandoffEnvironment#nowSeconds()}
     */
    public static double deadline(double clipSeconds) {
        if (!Double.isFinite(clipSeconds) || clipSeconds <= 0.0) {
            return SAFETY_MARGIN_SECONDS;
        }
        return clipSeconds * SAFETY_FACTOR + SAFETY_MARGIN_SECONDS;
    }
}
