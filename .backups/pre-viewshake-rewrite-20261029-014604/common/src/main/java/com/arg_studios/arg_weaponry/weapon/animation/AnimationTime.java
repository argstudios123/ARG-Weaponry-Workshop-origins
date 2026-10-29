package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * Maps a requested animation time onto the valid sampling interval of an animation.
 *
 * <p>Pure time arithmetic: no clock, no playback state, no side effects. The looping configuration of the
 * animation decides the mapping, and the distinction Stage 8A preserves between an absent {@code loop}
 * property and an explicit {@code false} is respected.</p>
 *
 * <h2>Policies</h2>
 * <ul>
 *     <li>{@link LoopMode#ONCE} - clamp into {@code [0, length]}. Time before the start stays at the start,
 *     time after the end stays at the terminal pose.</li>
 *     <li>{@link LoopMode#LOOP} - wrap into {@code [0, length)}. Negative times wrap too ({@code -0.5} of a
 *     2s animation resolves to {@code 1.5}). A requested time exactly equal to the length resolves to
 *     {@code 0.0}: a cycle has no distinct end pose, and this keeps {@code sample(length)} identical to
 *     {@code sample(0)} so a looping animation never shows a one tick hitch.</li>
 *     <li>{@link LoopMode#HOLD_ON_LAST_FRAME} - clamp, exactly like {@code ONCE}. Holding the last frame is
 *     the same value-level behaviour as clamping; the difference only matters to a playback state machine
 *     (it does not stop), which is a later stage.</li>
 *     <li>An <strong>unspecified</strong> loop mode ({@code null}) - clamp, like {@code ONCE}.</li>
 * </ul>
 *
 * <h2>Why an unspecified loop mode clamps</h2>
 * <p>The Bedrock format treats {@code loop} as opt-in: an animation that does not declare it plays once.
 * Clamping is therefore the behaviour that matches the format's intent for the shipped asset (14 of its 20
 * animations omit the property), while the data model keeps {@code null} so a later stage could choose a
 * different default without re-parsing anything. This is an explicit policy choice, not a proven format
 * requirement.</p>
 *
 * <h2>Degenerate input</h2>
 * <p>A zero-length animation resolves to {@code 0.0} in every mode, so no division by zero is possible.
 * A non-finite requested time is rejected with an {@link IllegalArgumentException}: neither {@code NaN}
 * nor an infinite time has a meaningful sample, and silently substituting a value would corrupt playback.</p>
 */
public final class AnimationTime {

    private AnimationTime() {
    }

    /**
     * @param animation the animation whose length and loop mode decide the mapping
     * @return the loop mode that governs sampling; never {@code null} (an unspecified property means
     *         {@link LoopMode#ONCE})
     */
    public static LoopMode effectiveLoopMode(BedrockAnimation animation) {
        return animation.loopMode() == null ? LoopMode.ONCE : animation.loopMode();
    }

    /**
     * Maps a requested time onto a time that lies inside the animation.
     *
     * @param animation        the animation being sampled
     * @param requestedSeconds the requested time in seconds; must be finite, may be negative
     * @return a sampling time in {@code [0, length]}
     * @throws IllegalArgumentException when the requested time is {@code NaN} or infinite
     */
    public static double resolve(BedrockAnimation animation, double requestedSeconds) {
        if (animation == null) {
            throw new IllegalArgumentException("Cannot resolve a time for a missing animation");
        }
        if (!Double.isFinite(requestedSeconds)) {
            throw new IllegalArgumentException("Invalid requested time for animation '" + animation.name() + "': "
                    + requestedSeconds + " (expected a finite number of seconds)");
        }

        double length = animation.lengthSeconds();
        if (length <= 0.0) {
            return 0.0;
        }

        if (effectiveLoopMode(animation) != LoopMode.LOOP) {
            return Math.min(Math.max(requestedSeconds, 0.0), length);
        }

        double wrapped = requestedSeconds - length * Math.floor(requestedSeconds / length);
        if (wrapped < 0.0 || wrapped >= length) {
            // Guards the rounding of very large inputs and the exact-length case.
            wrapped = 0.0;
        }
        return wrapped;
    }
}
