package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * Uniform Catmull-Rom (cardinal) interpolation between two channel values.
 *
 * <h2>Variant and evidence</h2>
 * <p>This is the <strong>uniform</strong> Catmull-Rom variant evaluated in Hermite form, with the standard
 * tension {@code 0.5}. It was established from the project's reference mod rather than guessed: TaCZ
 * ({@code com.tacz.guns.util.math.MathUtil.splineCurve(float[4], float tension, float t)}, verified in the
 * shipped {@code TACZ-Refabricated-26.3-1.1.8} bytecode) computes</p>
 * <pre>
 * a = 2t^3 - 3t^2 + 1        b = -2t^3 + 3t^2
 * c = t^3 - 2t^2 + t         d = t^3 - t^2
 * result = a*p1 + b*p2 + tension*c*(p2 - p0) + tension*d*(p3 - p1)
 * </pre>
 * <p>with {@code tension = 0.5}, and TaCZ's {@code CustomInterpolator#doCatmullromLerp} calls it with that
 * constant. The tangents are therefore derived from the chord between neighbouring keyframe values, i.e.
 * they do not depend on how the keyframes are spaced in time; the segment fraction is the normalised
 * fraction {@code (t - t1) / (t2 - t1)} (TaCZ {@code ObjectAnimationChannel#computeAlpha}).</p>
 *
 * <p><strong>Known limitation:</strong> on unevenly spaced keyframes a time-aware (non-uniform) Catmull-Rom
 * would scale the tangents by the interval lengths and produce slightly different values inside those
 * segments. The variant used here matches the reference implementation and the authoring tool convention;
 * it is isolated in this class so it can be replaced in one place if in-game comparison ever shows that the
 * Bedrock engine behaves differently. The M100 DMR keyframes are authored on a roughly uniform 24 fps grid
 * (steps of {@code 0.0167} or {@code 0.0417}), so the two variants differ by very little on that asset.</p>
 *
 * <h2>Rotation channels</h2>
 * <p>Every component is interpolated independently in Euler space, because that is how the channels are
 * authored. There is deliberately no quaternion conversion in this stage: Euler-space interpolation can
 * take a different path than a rotation-space interpolation when components are large (for example
 * {@code -160} to {@code 170} degrees sweeps the long way round), and it cannot avoid the usual Euler
 * artefacts. That difference is documented, not hidden.</p>
 */
public final class CatmullRomInterpolation {

    /** The tension used by the reference implementation for {@code "lerp_mode": "catmullrom"}. */
    public static final double TENSION = 0.5;

    private CatmullRomInterpolation() {
    }

    /**
     * Interpolates one segment of a Catmull-Rom spline.
     *
     * @param previous   the control point before the segment start
     * @param startValue the value at the start of the segment
     * @param endValue   the value at the end of the segment
     * @param next       the control point after the segment end
     * @param startTime  the timestamp of {@code startValue}, in seconds
     * @param endTime    the timestamp of {@code endValue}, in seconds
     * @param time       the time to evaluate; the fraction is clamped into {@code [0, 1]}
     * @return the interpolated value, never containing {@code NaN} or an infinity
     */
    public static Vec3 apply(Vec3 previous, Vec3 startValue, Vec3 endValue, Vec3 next, double startTime,
                             double endTime, double time) {
        double span = endTime - startTime;
        if (!(span > 0.0) || !Double.isFinite(span)) {
            return endValue;
        }
        double fraction = LinearInterpolation.clamp01((time - startTime) / span);
        Vec3 result = new Vec3(
                (float) spline(previous.x(), startValue.x(), endValue.x(), next.x(), fraction),
                (float) spline(previous.y(), startValue.y(), endValue.y(), next.y(), fraction),
                (float) spline(previous.z(), startValue.z(), endValue.z(), next.z(), fraction));
        if (!isFinite(result)) {
            // Unreachable for finite control points and a clamped fraction, but interpolating must never
            // hand a corrupted value to the pose layer.
            return LinearInterpolation.lerp(startValue, endValue, fraction);
        }
        return result;
    }

    /**
     * Evaluates the one-dimensional Catmull-Rom polynomial used by the reference implementation.
     *
     * @param p0       the control point before the segment
     * @param p1       the value at the start of the segment
     * @param p2       the value at the end of the segment
     * @param p3       the control point after the segment
     * @param fraction the fraction inside the segment, expected in {@code [0, 1]}
     * @return the interpolated component
     */
    public static double spline(double p0, double p1, double p2, double p3, double fraction) {
        double t = fraction;
        double squared = t * t;
        double cubed = squared * t;
        double a = 2.0 * cubed - 3.0 * squared + 1.0;
        double b = -2.0 * cubed + 3.0 * squared;
        double c = cubed - 2.0 * squared + t;
        double d = cubed - squared;
        double value = a * p1 + b * p2 + TENSION * c * (p2 - p0) + TENSION * d * (p3 - p1);
        return Double.isFinite(value) ? value : p2;
    }

    private static boolean isFinite(Vec3 value) {
        return Float.isFinite(value.x()) && Float.isFinite(value.y()) && Float.isFinite(value.z());
    }
}
