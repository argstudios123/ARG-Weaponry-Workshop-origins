package com.arg_studios.arg_weaponry.weapon.aiming;

/**
 * The zoom convention and its validation.
 *
 * <h2>Convention</h2>
 * <p>{@code aiming.aimimg_zoom} is a <strong>field-of-view multiplier</strong> at full aim-down-sights:</p>
 * <ul>
 *   <li>{@code 1.0} - the weapon does not change the base field of view,</li>
 *   <li>{@code 0.5} - full ADS uses half of the ordinary field of view,</li>
 *   <li>smaller values - stronger zoom.</li>
 * </ul>
 * <p>The multiplier is interpolated from {@code 1.0} to the configured value with the weapon's own easing curve, so
 * {@code 0.5} at half progress is {@code 0.75} of the base field of view.</p>
 *
 * <h2>Validation</h2>
 * <p>The value must be finite and strictly positive. Anything else would divide the field of view by zero, invert it, or
 * hide the world, so {@link #sanitise(double)} clamps it into {@code [MIN_ZOOM, MAX_ZOOM]} and reports the problem once, at
 * parse time. {@link #MAX_ZOOM} is {@code 1.0}: a value above it would widen the view instead of zooming, which the
 * milestone does not define, and clamping it to {@code 1.0} is the neutral behaviour.</p>
 */
public final class AimZoom {

    /** The multiplier of a weapon that does not zoom. */
    public static final double DEFAULT_ZOOM = 1.0;

    /** The widest accepted multiplier; above this the weapon would widen the view instead of zooming. */
    public static final double MAX_ZOOM = 1.0;

    /** The strongest accepted multiplier; below this the view becomes unusably narrow. */
    public static final double MIN_ZOOM = 0.02;

    private AimZoom() {
    }

    /**
     * @param zoom the configured multiplier; may be any value, including non-finite ones
     * @return {@code true} when the value can be used as it is
     */
    public static boolean isValid(double zoom) {
        return Double.isFinite(zoom) && zoom > 0.0 && zoom <= MAX_ZOOM;
    }

    /**
     * @param zoom the configured multiplier
     * @return a multiplier that is always usable: finite and within {@code [MIN_ZOOM, MAX_ZOOM]}
     */
    public static double sanitise(double zoom) {
        if (!Double.isFinite(zoom) || zoom <= 0.0) {
            return MIN_ZOOM;
        }
        if (zoom > MAX_ZOOM) {
            return MAX_ZOOM;
        }
        return Math.max(MIN_ZOOM, zoom);
    }

    /**
     * Describes why a configured multiplier is not usable, for the validation report.
     *
     * @param zoom the configured multiplier
     * @return a diagnostic, or {@code null} when the value is valid
     */
    public static String describeProblem(double zoom) {
        if (isValid(zoom)) {
            return null;
        }
        if (!Double.isFinite(zoom)) {
            return "The aim zoom '" + zoom + "' is not a finite number";
        }
        if (zoom <= 0.0) {
            return "The aim zoom '" + zoom + "' would divide the field of view by zero or invert it; it is clamped to "
                    + MIN_ZOOM;
        }
        return "The aim zoom '" + zoom + "' is greater than " + MAX_ZOOM + ", which would widen the view instead of "
                + "zooming it; it is clamped to " + MAX_ZOOM;
    }
}
