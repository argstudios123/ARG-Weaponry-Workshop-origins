package com.arg_studios.arg_weaponry.weapon.reload;

/**
 * The arithmetic and timing rules of a reload, with no Minecraft types at all.
 *
 * <p>Everything here is a function of numbers, which is what makes the magazine/transfer table in the
 * milestone verifiable without a running game:</p>
 * <pre>
 *   room     = capacity - currentAmmo        (never negative)
 *   transfer = min(room, reserveAvailable)   (never negative, never more than the room)
 * </pre>
 *
 * <h2>Why the clamp on {@code currentAmmo}</h2>
 * <p>A stored count above the current capacity is legal in this project (a pack can lower
 * {@code ammo_capacity} under a stack that already holds more rounds), and {@code WeaponState} keeps such a
 * value untouched. For reloading that simply means there is no room: {@code room} is {@code 0}, not negative,
 * so a reload is refused instead of accidentally removing rounds.</p>
 *
 * <h2>Time</h2>
 * <p>Reload durations are authored in seconds and executed in whole server ticks, exactly like firing
 * cadence: {@link #ticksFor(double)} rounds <em>up</em> so a reload never completes earlier than authored, and
 * never produces a zero-tick reload.</p>
 */
public final class ReloadRules {

    /** Server ticks per second, the unit reload durations are executed in. */
    public static final int TICKS_PER_SECOND = 20;

    /**
     * Duration used when a definition declares no usable reload sequence.
     *
     * <p>A weapon that supports ammunition but declares no {@code reload_sequences} is still reloadable; it
     * simply has no authored animation timing, so this documented default is used instead of inventing a
     * duration from the animation clip length.</p>
     */
    public static final double DEFAULT_RELOAD_SECONDS = 1.0;

    /**
     * The cross-fade length used when a reload entry declares no {@code transition_duration}.
     *
     * <p>Reload stages are separate clips, so the pose at the end of one stage and the pose at the start of the next are
     * generally not the same. Fading between them for this long reads as one continuous motion instead of a cut, and it
     * is short enough that it never hides either clip: at twenty ticks per second it is three ticks. The value is a
     * <em>render-side</em> blend only - it never changes a stage's duration, an insertion point or any server timing.</p>
     */
    public static final double DEFAULT_TRANSITION_SECONDS = 0.15;

    /**
     * The longest cross-fade a reload entry may ask for.
     *
     * <p>A larger {@code transition_duration} is clamped rather than rejected: a fade longer than this would still be
     * inside the stage it introduces, but it would stop reading as a transition and start reading as its own animation.
     * </p>
     */
    public static final double MAX_TRANSITION_SECONDS = 0.5;

    private ReloadRules() {
    }

    /**
     * Resolves the cross-fade length of one reload entry into whole milliseconds, the form that crosses the wire.
     *
     * @param seconds the authored {@code transition_duration}, or {@code null} when the entry declares none
     * @return the fade length in milliseconds, {@code 0} when blending is disabled; always within
     *         {@code [0, MAX_TRANSITION_SECONDS]}
     */
    public static int transitionMillis(Double seconds) {
        if (seconds == null || !Double.isFinite(seconds)) {
            seconds = DEFAULT_TRANSITION_SECONDS;
        }
        double clamped = Math.max(0.0, Math.min(MAX_TRANSITION_SECONDS, seconds));
        return (int) Math.round(clamped * 1000.0);
    }

    /**
     * @param capacity    the magazine capacity, {@code 0} when the weapon declares none
     * @param currentAmmo the rounds currently in the magazine
     * @return how many rounds the magazine can still take, never negative
     */
    public static int room(int capacity, int currentAmmo) {
        if (capacity <= 0) {
            return 0;
        }
        return Math.max(0, capacity - Math.max(0, currentAmmo));
    }

    /**
     * @param capacity         the magazine capacity
     * @param currentAmmo      the rounds currently in the magazine
     * @param reserveAvailable the compatible rounds actually present in the inventory
     * @return the number of rounds a real transfer may move, never negative and never more than the room
     */
    public static int transfer(int capacity, int currentAmmo, int reserveAvailable) {
        if (reserveAvailable <= 0) {
            return 0;
        }
        return Math.min(room(capacity, currentAmmo), reserveAvailable);
    }

    /**
     * @param seconds an authored duration in seconds
     * @return the duration in whole server ticks, at least one; a non-finite or non-positive value yields one
     *         tick so a broken duration can never start an instantly completing reload
     */
    public static int ticksFor(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(seconds * TICKS_PER_SECOND));
    }

    /**
     * @param ticks a duration in server ticks
     * @return the same duration in seconds, for client-side animation timing and diagnostics
     */
    public static double secondsFor(int ticks) {
        return ticks <= 0 ? 0.0 : ticks / (double) TICKS_PER_SECOND;
    }
}
