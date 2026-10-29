package com.arg_studios.arg_weaponry.weapon.equip;

/**
 * The duration arithmetic of the draw and holster windows.
 *
 * <p>The schema declares {@code duration} in <em>seconds</em> ({@code "duration": 1.2} in {@code draw_anims} and {@code
 * holster_anims}); the state machine counts <em>server ticks</em>. This class is the single conversion between the two,
 * with the documented rule:</p>
 * <pre>
 *   ticks = max(1, ceil(seconds * 20))
 * </pre>
 * <p>so a 1.2 s clip is a 24 tick window, a 0.87 s clip is 18 ticks, and an absent, zero, negative or non-finite duration
 * still yields a window of one tick rather than a zero-length or negative one. This mirrors the conversion the reload and
 * inspection sessions already use ({@code WeaponInspectService#durationTicks}); it is repeated here because those classes
 * own their own sessions, and the equip window must not depend on the inspection service to decide when a holster ends.</p>
 *
 * <p>The safety timeout is {@link #SAFETY_MARGIN_TICKS} ticks longer than the window, so a completed window is always
 * allowed to finish normally before the manager forces its outcome.</p>
 */
public final class EquipTiming {

    /** Server ticks per second, the unit every window is counted in. */
    public static final int TICKS_PER_SECOND = 20;

    /** Shortest window any authored duration can produce, in ticks. */
    public static final int MIN_DURATION_TICKS = 1;

    /** Longest window any authored duration can produce, in ticks; a longer draw is a typo, not a design. */
    public static final int MAX_DURATION_TICKS = 12_000;

    /** Seconds a single draw or holster window may last at most. */
    public static final double MAX_DURATION_SECONDS = MAX_DURATION_TICKS / (double) TICKS_PER_SECOND;

    /** Extra ticks a window may overrun before the safety net forces its outcome. */
    public static final int SAFETY_MARGIN_TICKS = 5;

    private EquipTiming() {
    }

    /**
     * @param seconds the authored duration, in seconds
     * @return the duration in whole ticks, at least {@link #MIN_DURATION_TICKS} and at most
     *         {@link #MAX_DURATION_TICKS}
     */
    public static int secondsToTicks(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            return MIN_DURATION_TICKS;
        }
        double ticks = Math.ceil(Math.min(seconds, MAX_DURATION_SECONDS) * TICKS_PER_SECOND);
        return (int) Math.max(MIN_DURATION_TICKS, Math.min(MAX_DURATION_TICKS, ticks));
    }

    /**
     * @param durationTicks the authored window, in ticks
     * @return the tick at which the safety net force-completes the window, always later than the window itself
     */
    public static int deadlineTicks(int durationTicks) {
        int clamped = Math.max(MIN_DURATION_TICKS, Math.min(MAX_DURATION_TICKS, durationTicks));
        return clamped + SAFETY_MARGIN_TICKS;
    }

    /**
     * @param ticks a number of ticks
     * @return the same duration in seconds, used to retime a clip to its authored window
     */
    public static double ticksToSeconds(int ticks) {
        return Math.max(0, ticks) / (double) TICKS_PER_SECOND;
    }
}
