package com.arg_studios.arg_weaponry.weapon.aiming;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The easing curves a weapon may select for its aim-down-sights transition.
 *
 * <p>A curve maps linear progress in {@code [0, 1]} to eased progress in {@code [0, 1]}. It is used for exactly one
 * thing - blending the hip-fire placement into the sight-aligned placement and scaling the field of view - and it is
 * deliberately separate from the animation engine's bone interpolation: a zoom transition is a view transition, not a
 * keyframe curve, and mixing the two would tie the look of aiming to the authored animation timing.</p>
 *
 * <h2>Names</h2>
 * <p>Every curve has one canonical name. {@link #parse(String)} normalises what a content pack writes, so
 * {@code "ease in out"}, {@code "ease-in-out"}, {@code "Ease_In_Out"} and {@code "easeinout"} all resolve to the same
 * curve. An unknown name is reported by the parser and falls back to {@link #LINEAR}, which is the behaviour of a weapon
 * that declares no curve at all, so a typo can never disable aiming.</p>
 *
 * <h2>Range</h2>
 * <p>{@link #evaluate(double)} clamps its input, so an out-of-range progress value cannot produce an out-of-range zoom, and
 * every curve returns exactly {@code 0} at {@code 0} and {@code 1} at {@code 1}.</p>
 */
public enum ZoomCurve {

    /** No easing. */
    LINEAR("linear"),

    /** The documented smoothstep curve: {@code 3t^2 - 2t^3}. */
    SMOOTH("smooth"),

    /** Quadratic ease in. */
    EASE_IN_QUAD("ease_in_quad"),

    /** Quadratic ease out. */
    EASE_OUT_QUAD("ease_out_quad"),

    /** Quadratic ease in and out. */
    EASE_IN_OUT_QUAD("ease_in_out_quad"),

    /** Cubic ease in. */
    EASE_IN_CUBIC("ease_in_cubic"),

    /** Cubic ease out. */
    EASE_OUT_CUBIC("ease_out_cubic"),

    /** Cubic ease in and out. */
    EASE_IN_OUT_CUBIC("ease_in_out_cubic"),

    /** Quartic ease in. */
    EASE_IN_QUART("ease_in_quart"),

    /** Quartic ease out. */
    EASE_OUT_QUART("ease_out_quart"),

    /** Quartic ease in and out. */
    EASE_IN_OUT_QUART("ease_in_out_quart"),

    /** Quintic ease in. */
    EASE_IN_QUINT("ease_in_quint"),

    /** Quintic ease out. */
    EASE_OUT_QUINT("ease_out_quint"),

    /** Quintic ease in and out. */
    EASE_IN_OUT_QUINT("ease_in_out_quint"),

    /** Sine ease in. */
    EASE_IN_SINE("ease_in_sine"),

    /** Sine ease out. */
    EASE_OUT_SINE("ease_out_sine"),

    /** Sine ease in and out. */
    EASE_IN_OUT_SINE("ease_in_out_sine"),

    /** Exponential ease in. */
    EASE_IN_EXPO("ease_in_expo"),

    /** Exponential ease out. */
    EASE_OUT_EXPO("ease_out_expo"),

    /** Exponential ease in and out. */
    EASE_IN_OUT_EXPO("ease_in_out_expo"),

    /** Circular ease in. */
    EASE_IN_CIRC("ease_in_circ"),

    /** Circular ease out. */
    EASE_OUT_CIRC("ease_out_circ"),

    /** Circular ease in and out. */
    EASE_IN_OUT_CIRC("ease_in_out_circ"),

    /** Plain {@code t}: the curve a weapon gets when it names none, kept as an alias for readability in packs. */
    NONE("none");

    private final String canonicalName;

    ZoomCurve(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    /** @return the name a content pack uses for this curve. */
    public String canonicalName() {
        return canonicalName;
    }

    /**
     * @param progress linear progress, may be any finite double
     * @return the eased progress, always within {@code [0, 1]}
     */
    public double evaluate(double progress) {
        double t = Double.isFinite(progress) ? Math.max(0.0, Math.min(1.0, progress)) : 0.0;
        // Exact endpoints for every family: some closed forms (the sine ones, for instance) return 0.9999999999999999 at the
        // end, which would leave a weapon a hair away from its configured zoom. Pinning the ends keeps the transition exact.
        if (t <= 0.0) {
            return 0.0;
        }
        if (t >= 1.0) {
            return 1.0;
        }
        return switch (this) {
            case LINEAR, NONE -> t;
            case SMOOTH -> t * t * (3.0 - 2.0 * t);
            case EASE_IN_QUAD -> t * t;
            case EASE_OUT_QUAD -> 1.0 - (1.0 - t) * (1.0 - t);
            case EASE_IN_OUT_QUAD -> t < 0.5 ? 2.0 * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 2.0) / 2.0;
            case EASE_IN_CUBIC -> t * t * t;
            case EASE_OUT_CUBIC -> 1.0 - Math.pow(1.0 - t, 3.0);
            case EASE_IN_OUT_CUBIC -> t < 0.5 ? 4.0 * t * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0;
            case EASE_IN_QUART -> Math.pow(t, 4.0);
            case EASE_OUT_QUART -> 1.0 - Math.pow(1.0 - t, 4.0);
            case EASE_IN_OUT_QUART -> t < 0.5 ? 8.0 * Math.pow(t, 4.0)
                    : 1.0 - Math.pow(-2.0 * t + 2.0, 4.0) / 2.0;
            case EASE_IN_QUINT -> Math.pow(t, 5.0);
            case EASE_OUT_QUINT -> 1.0 - Math.pow(1.0 - t, 5.0);
            case EASE_IN_OUT_QUINT -> t < 0.5 ? 16.0 * Math.pow(t, 5.0)
                    : 1.0 - Math.pow(-2.0 * t + 2.0, 5.0) / 2.0;
            case EASE_IN_SINE -> 1.0 - Math.cos(t * Math.PI / 2.0);
            case EASE_OUT_SINE -> Math.sin(t * Math.PI / 2.0);
            case EASE_IN_OUT_SINE -> -(Math.cos(Math.PI * t) - 1.0) / 2.0;
            case EASE_IN_EXPO -> t == 0.0 ? 0.0 : Math.pow(2.0, 10.0 * t - 10.0);
            case EASE_OUT_EXPO -> t == 1.0 ? 1.0 : 1.0 - Math.pow(2.0, -10.0 * t);
            case EASE_IN_OUT_EXPO -> {
                if (t == 0.0 || t == 1.0) {
                    yield t;
                }
                yield t < 0.5 ? Math.pow(2.0, 20.0 * t - 10.0) / 2.0
                        : (2.0 - Math.pow(2.0, -20.0 * t + 10.0)) / 2.0;
            }
            case EASE_IN_CIRC -> 1.0 - Math.sqrt(Math.max(0.0, 1.0 - t * t));
            case EASE_OUT_CIRC -> Math.sqrt(Math.max(0.0, 1.0 - Math.pow(t - 1.0, 2.0)));
            case EASE_IN_OUT_CIRC -> t < 0.5
                    ? (1.0 - Math.sqrt(Math.max(0.0, 1.0 - Math.pow(2.0 * t, 2.0)))) / 2.0
                    : (Math.sqrt(Math.max(0.0, 1.0 - Math.pow(-2.0 * t + 2.0, 2.0))) + 1.0) / 2.0;
        };
    }

    /**
     * Resolves a configured curve name.
     *
     * @param raw the value from the weapon definition, may be {@code null}
     * @return the matching curve, or {@link #LINEAR} when the name is absent or unknown
     */
    public static ZoomCurve parse(String raw) {
        ZoomCurve known = lookup(raw);
        return known == null ? LINEAR : known;
    }

    /**
     * @param raw the value from the weapon definition, may be {@code null}
     * @return the matching curve, or {@code null} when the name is not one of the documented ones
     */
    public static ZoomCurve lookup(String raw) {
        String normalised = normalise(raw);
        if (normalised.isEmpty()) {
            return null;
        }
        for (ZoomCurve curve : values()) {
            if (normalise(curve.canonicalName).equals(normalised)) {
                return curve;
            }
        }
        // Bare names without a family. Content packs write "ease in", "ease out" and "ease in out" (and the CSS-style
        // dashed variants) without naming a polynomial; those resolve to the quadratic family, which is documented here and
        // in the report so the mapping is a decision rather than an accident. The sine family is also accepted in its short
        // form ("sine_in" / "sine_out" / "sine_in_out") because that is the vocabulary the aiming configuration documents.
        return switch (normalised) {
            case "ease", "easein", "in" -> EASE_IN_QUAD;
            case "easeout", "out" -> EASE_OUT_QUAD;
            case "easeinout", "inout" -> EASE_IN_OUT_QUAD;
            case "sinein" -> EASE_IN_SINE;
            case "sineout" -> EASE_OUT_SINE;
            case "sineinout" -> EASE_IN_OUT_SINE;
            default -> null;
        };
    }

    /**
     * Normalises a curve name so whitespace, capitalisation and separator style cannot change its meaning.
     *
     * <p>Everything that is not a letter or digit is dropped and the rest is lower-cased, so {@code "ease in out"},
     * {@code "Ease-In-Out"} and {@code "ease_in_out"} all become {@code "easeinout"}.</p>
     *
     * @param raw a configured name, may be {@code null}
     * @return the normalised name, never {@code null}, empty when there was nothing to normalise
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder normalised = new StringBuilder(lower.length());
        for (int index = 0; index < lower.length(); index++) {
            char character = lower.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalised.append(character);
            }
        }
        return normalised.toString();
    }

    /** @return every canonical name, in declaration order, for diagnostics. */
    public static List<String> canonicalNames() {
        List<String> names = new ArrayList<>(values().length);
        for (ZoomCurve curve : values()) {
            names.add(curve.canonicalName);
        }
        return List.copyOf(names);
    }
}
