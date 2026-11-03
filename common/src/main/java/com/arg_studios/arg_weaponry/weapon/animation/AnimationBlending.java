package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * The small, pure pieces of arithmetic the animation layers share: rate-limited approaches and easing.
 *
 * <p>Kept in one place on purpose. The movement layer, the aiming layer and the weapon controller all need the same two
 * rules - "move a value towards a target at a fixed rate per second" and "shape a linear {@code 0..1} position into a
 * fade with zero velocity at both ends" - and duplicating them would let the layers drift apart. Nothing here reads a
 * clock or holds state, so every function is trivially testable and exactly reproducible.</p>
 *
 * <h2>Frame-rate independence</h2>
 * <p>{@link #moveTowards(double, double, double, double)} advances by {@code delta / duration} per call, so the time a
 * transition takes is the same at sixty, thirty or one tick per second: sixty calls of {@code 1/60 s} and one call of
 * {@code 1 s} reach the same value. That is the property that keeps a stalled client from jumping the state machine and
 * a fast client from finishing a cross-fade early.</p>
 */
public final class AnimationBlending {

    /** How many time constants a full {@link #approach} covers in its documented duration. */
    private static final double APPROACH_TIME_CONSTANTS = 3.0;

    /** Below this the exponential follow snaps to its target, so a settled value is exactly its target. */
    private static final double APPROACH_EPSILON = 1.0E-4;

    private AnimationBlending() {
    }

    /**
     * Moves a value towards a target with an exponential (critically damped, first-order) follow.
     *
     * <p>Unlike {@link #moveTowards}, the value starts and ends smoothly: the step is proportional to the remaining
     * distance, so a direction change eases out instead of travelling at a constant speed and stopping abruptly. The
     * exponential form is exactly frame-rate independent, and the value snaps to the target once it is within
     * {@value #APPROACH_EPSILON} so a settled value is exactly its target.</p>
     *
     * @param current         the value to move
     * @param target          the value to reach
     * @param deltaSeconds    the time this update covers
     * @param durationSeconds the time after which about 95% of a full move is covered
     * @return the moved value, never past the target
     */
    public static double approach(double current, double target, double deltaSeconds, double durationSeconds) {
        if (!Double.isFinite(current)) {
            return target;
        }
        if (!Double.isFinite(target)) {
            return current;
        }
        if (!(durationSeconds > 0.0)) {
            return target;
        }
        double delta = Math.max(0.0, Double.isFinite(deltaSeconds) ? deltaSeconds : 0.0);
        double alpha = 1.0 - Math.exp(-APPROACH_TIME_CONSTANTS * delta / durationSeconds);
        double next = current + (target - current) * alpha;
        return Math.abs(target - next) <= APPROACH_EPSILON ? target : next;
    }

    /**
     * Moves a value towards a target at a rate that covers the whole range in {@code duration} seconds.
     *
     * @param current         the value to move
     * @param target          the value to reach
     * @param deltaSeconds    the time this update covers
     * @param durationSeconds the time a full {@code 0 -> 1} move takes; {@code 0} or less jumps to the target
     * @return the moved value, never past the target; the target when the remaining distance is below the epsilon
     */
    public static double moveTowards(double current, double target, double deltaSeconds, double durationSeconds) {
        if (!Double.isFinite(current)) {
            return target;
        }
        if (!Double.isFinite(target)) {
            return current;
        }
        if (!(durationSeconds > 0.0)) {
            return target;
        }
        double step = Math.max(0.0, Double.isFinite(deltaSeconds) ? deltaSeconds : 0.0) / durationSeconds;
        if (step >= 1.0) {
            return target;
        }
        double distance = target - current;
        if (Math.abs(distance) <= step) {
            return target;
        }
        return current + Math.signum(distance) * step;
    }

    /**
     * The documented smoothstep curve: {@code 3t^2 - 2t^3}.
     *
     * <p>Used for cross-fades, so a blend starts and ends with zero velocity instead of jerking at the moment the state
     * changes. The input is clamped, and the two endpoints are returned exactly.</p>
     *
     * @param progress the linear position
     * @return the eased position, always within {@code [0, 1]}
     */
    public static double smoothstep(double progress) {
        double t = clamp01(progress);
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * @param value any value
     * @return the value clamped into {@code [0, 1]}; {@code NaN} becomes {@code 0} and an infinite value its own end
     */
    public static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        if (value == Double.POSITIVE_INFINITY) {
            return 1.0;
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
