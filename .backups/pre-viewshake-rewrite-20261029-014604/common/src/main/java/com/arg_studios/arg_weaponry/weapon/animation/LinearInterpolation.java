package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * Component-wise linear interpolation between two channel values.
 *
 * <p>The interpolation parameter is the fraction of the way from {@code startTime} to {@code endTime}, so
 * unevenly spaced keyframes are respected and the arithmetic is done in {@code double} before the result is
 * stored in a {@link Vec3}.</p>
 *
 * <p>Coincident timestamps cannot produce a division by zero: a zero or negative span returns the end value,
 * matching the reference implementation (TaCZ {@code ObjectAnimationChannel.computeAlpha}), which uses a
 * fraction of {@code 1} in that case.</p>
 */
public final class LinearInterpolation {

    private LinearInterpolation() {
    }

    /**
     * Interpolates between two values.
     *
     * @param startTime  the timestamp of the first value, in seconds
     * @param startValue the value at {@code startTime}
     * @param endTime    the timestamp of the second value, in seconds
     * @param endValue   the value at {@code endTime}
     * @param time       the time to evaluate, expected to lie between the two timestamps
     * @return the interpolated value, never containing {@code NaN} or an infinity
     */
    public static Vec3 apply(double startTime, Vec3 startValue, double endTime, Vec3 endValue, double time) {
        double span = endTime - startTime;
        if (!(span > 0.0) || !Double.isFinite(span)) {
            return endValue;
        }
        double fraction = clamp01((time - startTime) / span);
        return lerp(startValue, endValue, fraction);
    }

    /**
     * Interpolates between two values with an explicit fraction.
     *
     * @param startValue the value at fraction {@code 0}
     * @param endValue   the value at fraction {@code 1}
     * @param fraction   the fraction, clamped into {@code [0, 1]}
     * @return the interpolated value
     */
    public static Vec3 lerp(Vec3 startValue, Vec3 endValue, double fraction) {
        double clamped = clamp01(fraction);
        return new Vec3(
                (float) mix(startValue.x(), endValue.x(), clamped),
                (float) mix(startValue.y(), endValue.y(), clamped),
                (float) mix(startValue.z(), endValue.z(), clamped));
    }

    static double mix(double start, double end, double fraction) {
        double value = start + (end - start) * fraction;
        if (!Double.isFinite(value)) {
            return end;
        }
        return value;
    }

    static double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        return value > 1.0 ? 1.0 : value;
    }
}
