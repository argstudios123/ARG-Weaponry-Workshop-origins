package com.arg_studios.arg_weaponry.client.recoil;

/**
 * One accepted shot's recoil contribution: the signed offset, in degrees, that the shot adds to the player's
 * accumulated recoil offset, together with the two timing settings that shot uses.
 *
 * <p>This record carries no time shape of its own. A shot is an <em>impulse</em>: it raises the accumulated offset by
 * the configured amount at the instant the server accepts it, and the return of that offset towards neutral is owned by
 * {@link PlayerRecoilController}, which normalises the whole accumulated state over one well-defined interval. Keeping
 * the per-shot value this small is what makes a burst behave like a burst: two shots in quick succession add two
 * impulses to the same offset, so the aim really climbs, instead of starting a second independent bump that has to
 * decay on its own.</p>
 *
 * <h2>Units, unchanged from the JSON</h2>
 * <ul>
 *   <li>{@code pitchDegrees} is the weapon's {@code vertical_recoil} value, optionally scaled by
 *   {@code aim_multiplier} and/or {@code crouch_multiplier}: degrees of upward aim offset per accepted shot, positive
 *   meaning the aim rises.</li>
 *   <li>{@code yawDegrees} is one of {@code horizontal_recoil_left} / {@code horizontal_recoil_right}, scaled by the same
 *   multipliers: degrees of sideways aim offset per accepted shot, negative meaning the aim turns left and positive
 *   meaning it turns right.</li>
 *   <li>{@code smoothing} and {@code recoverySeconds} are the two timing settings the controller needs for this shot; they
 *   are carried here (and not read by the controller from a profile) so the controller stays a pure state machine fed by
 *   one value object, exactly as it was before the tuning properties existed.</li>
 * </ul>
 *
 * @param pitchDegrees    the offset this shot adds, in degrees of pitch; positive raises the aim
 * @param yawDegrees      the offset this shot adds, in degrees of yaw; positive turns the aim right
 * @param smoothing       share of the remaining offset applied per reference interval, {@code 1.0} meaning "apply it now"
 * @param recoverySeconds how long the accumulated offset takes to normalise back to neutral, in seconds
 */
public record ViewRecoilResponse(double pitchDegrees, double yawDegrees, double smoothing, double recoverySeconds) {

    /** A response that contributes nothing. */
    public static final ViewRecoilResponse NONE = new ViewRecoilResponse(0.0, 0.0,
            PlayerRecoilController.DEFAULT_SMOOTHING, PlayerRecoilController.RECOVERY_SECONDS);

    /**
     * Backwards-compatible construction: an impulse with the documented defaults, i.e. applied at once and recovered over
     * the baseline {@link PlayerRecoilController#RECOVERY_SECONDS}.
     *
     * @param pitchDegrees the offset this shot adds, in degrees of pitch
     * @param yawDegrees   the offset this shot adds, in degrees of yaw
     */
    public ViewRecoilResponse(double pitchDegrees, double yawDegrees) {
        this(pitchDegrees, yawDegrees, PlayerRecoilController.DEFAULT_SMOOTHING,
                PlayerRecoilController.RECOVERY_SECONDS);
    }

    public ViewRecoilResponse {
        pitchDegrees = finite(pitchDegrees);
        yawDegrees = finite(yawDegrees);
        smoothing = clamp(smoothing, PlayerRecoilController.MIN_SMOOTHING, PlayerRecoilController.MAX_SMOOTHING,
                PlayerRecoilController.DEFAULT_SMOOTHING);
        recoverySeconds = clamp(recoverySeconds, PlayerRecoilController.MIN_RECOVERY_SECONDS,
                PlayerRecoilController.MAX_RECOVERY_SECONDS, PlayerRecoilController.RECOVERY_SECONDS);
    }

    /** @return whether this response would move the aim at all. */
    public boolean isZero() {
        return pitchDegrees == 0.0 && yawDegrees == 0.0;
    }

    /**
     * Scales both channels, which is how {@link PlayerRecoilController} applies its saturation: an impulse is added at
     * the share of its configured size that the accumulated offset still has room for. The timing settings are kept.
     *
     * @param factor the multiplier, clamped to {@code [0, 1]}
     * @return the scaled response, never {@code null}
     */
    public ViewRecoilResponse scaled(double factor) {
        if (!Double.isFinite(factor) || factor <= 0.0) {
            return new ViewRecoilResponse(0.0, 0.0, smoothing, recoverySeconds);
        }
        if (factor >= 1.0) {
            return this;
        }
        return new ViewRecoilResponse(pitchDegrees * factor, yawDegrees * factor, smoothing, recoverySeconds);
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    /**
     * @param value    the configured value
     * @param minimum  the smallest accepted value
     * @param maximum  the largest accepted value
     * @param fallback the value used when the configured one is absent or not a number
     * @return the sanitised value
     */
    private static double clamp(double value, double minimum, double maximum, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
