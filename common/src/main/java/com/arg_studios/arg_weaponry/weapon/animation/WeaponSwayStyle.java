package com.arg_studios.arg_weaponry.weapon.animation;

import java.util.Locale;

/**
 * The selectable weapon-sway styles: how the weapon reacts to the camera's mouse movement.
 *
 * <p>A style is a small bundle of constants, not an algorithm: {@link SwayState} owns the integrator and reads these values
 * from the style the weapon selected. That keeps the three feels comparable and every constant testable without a game.</p>
 *
 * <ul>
 *   <li><strong>{@link #VANILLA} (default)</strong> - subtle, light first-order follow, low rotation ceiling, little roll.
 *   It is the closest to the previous single-style implementation, so a pack that names no style keeps its feel.</li>
 *   <li><strong>{@link #ROTATORY}</strong> - rotational inertia: higher gain, a much larger roll share and an underdamped
 *   spring, so camera movement turns the weapon noticeably and it settles smoothly instead of stopping dead.</li>
 *   <li><strong>{@link #TYPE_1}</strong> - inspired by STALKER 2's heavier handling (an approximation, not a recreation):
 *   the strongest lag, the largest positional response relative to rotation, and a soft, underdamped settle.</li>
 * </ul>
 *
 * <p>{@code weapon_sway_type} is matched case-insensitively with separators ignored; {@code default} is accepted as another
 * name for {@link #VANILLA} because the shipped content pack still writes it. An unknown name falls back to
 * {@code vanilla}.</p>
 */
public enum WeaponSwayStyle {

    /** Subtle, Minecraft-inspired, first-order follow. */
    VANILLA("vanilla", 0.05 / 3.0, 3.0, 0.15, 0.0030, 1.00, 1.00, false, 0.05),

    /** Rotational inertia: more rotation, more roll, a visibly underdamped settle. */
    ROTATORY("rotatory", 0.05, 8.0, 0.55, 0.0015, 0.70, 1.00, true, 0.80),

    /** Heavier physical handling: strong lag, larger translation, a soft delayed settle. */
    TYPE_1("type_1", 0.035, 10.0, 0.30, 0.0060, 0.50, 0.55, true, 0.05);

    private final String canonicalName;
    private final double response;
    private final double maxRotationDegrees;
    private final double rollShare;
    private final double positionPerDegree;
    private final double dampingRatio;
    private final double stiffnessScale;
    private final boolean inertia;
    private final double inputResponse;

    WeaponSwayStyle(String canonicalName, double response, double maxRotationDegrees, double rollShare,
                    double positionPerDegree, double dampingRatio, double stiffnessScale, boolean inertia,
                    double inputResponse) {
        this.canonicalName = canonicalName;
        this.response = response;
        this.maxRotationDegrees = maxRotationDegrees;
        this.rollShare = rollShare;
        this.positionPerDegree = positionPerDegree;
        this.dampingRatio = dampingRatio;
        this.stiffnessScale = stiffnessScale;
        this.inertia = inertia;
        this.inputResponse = inputResponse;
    }

    /** @return the name a content pack uses for this style. */
    public String canonicalName() {
        return canonicalName;
    }

    /** @return degrees of weapon rotation per degree per second of camera rate at sensitivity {@code 1}. */
    public double response() {
        return response;
    }

    /** @return the largest rotation one axis may reach, in degrees, before {@code intensity} is applied. */
    public double maxRotationDegrees() {
        return maxRotationDegrees;
    }

    /** @return the share of the horizontal rotation that becomes roll. */
    public double rollShare() {
        return rollShare;
    }

    /** @return model units of translation per degree of the displayed sway. */
    public double positionPerDegree() {
        return positionPerDegree;
    }

    /** @return the damping ratio of the spring, {@code 1} being critically damped. */
    public double dampingRatio() {
        return dampingRatio;
    }

    /** @return how strongly the configured rate drives the spring; below {@code 1} is a heavier, slower weapon. */
    public double stiffnessScale() {
        return stiffnessScale;
    }

    /** @return whether the style integrates a velocity (inertia and overshoot) instead of a plain first-order follow. */
    public boolean inertia() {
        return inertia;
    }

    /**
     * @return the share of the frame's mouse movement applied as an immediate impulse before the smoothed spring follows
     *         it. This is what makes a flick answer on the same frame instead of waiting for the spring; the impulse is
     *         accumulated per degree of mouse movement, so splitting one movement across more frames does not change the
     *         total.
     */
    public double inputResponse() {
        return inputResponse;
    }

    /**
     * Resolves a configured style name.
     *
     * @param raw the value from the weapon definition, may be {@code null}
     * @return the matching style, or {@link #VANILLA} when the name is absent or unknown
     */
    public static WeaponSwayStyle parse(String raw) {
        WeaponSwayStyle known = lookup(raw);
        return known == null ? VANILLA : known;
    }

    /**
     * @param raw the value from the weapon definition, may be {@code null}
     * @return the matching style, or {@code null} when the name is not one of the documented ones
     */
    public static WeaponSwayStyle lookup(String raw) {
        String normalised = normalise(raw);
        if (normalised.isEmpty()) {
            return null;
        }
        for (WeaponSwayStyle style : values()) {
            if (normalise(style.canonicalName).equals(normalised)) {
                return style;
            }
        }
        // The shipped pack still writes "default" for the behaviour it had before styles existed.
        return switch (normalised) {
            case "default", "minecraft" -> VANILLA;
            case "rotational", "rotate" -> ROTATORY;
            case "type1", "stalker", "stalker2" -> TYPE_1;
            default -> null;
        };
    }

    /**
     * @param raw a configured name, may be {@code null}
     * @return the lower-cased name with every non-alphanumeric character removed
     */
    static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(lower.length());
        for (int index = 0; index < lower.length(); index++) {
            char character = lower.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                builder.append(character);
            }
        }
        return builder.toString();
    }
}
