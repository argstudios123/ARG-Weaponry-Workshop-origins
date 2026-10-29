package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * How the value between two keyframes is interpolated.
 *
 * <p>The Bedrock animation format expresses this per keyframe with {@code lerp_mode}; a keyframe
 * without it uses linear interpolation. The M100 DMR asset uses only {@link #LINEAR} (implicit) and
 * {@link #CATMULLROM}.</p>
 *
 * <p>{@link #UNKNOWN} preserves a mode this engine does not implement instead of silently treating it
 * as linear: the raw text is kept on the {@link Keyframe} so a diagnostic can name it. Interpolation
 * behaviour for unknown modes is a playback-stage decision, not a parsing one.</p>
 */
public enum LerpMode {

    /** Straight interpolation between the two keyframe values (the default when {@code lerp_mode} is absent). */
    LINEAR("linear"),

    /** Smooth Catmull-Rom interpolation using the surrounding keyframes and their pre/post values. */
    CATMULLROM("catmullrom"),

    /** A mode that was declared in the file but is not implemented by this engine. */
    UNKNOWN("unknown");

    private final String jsonName;

    LerpMode(String jsonName) {
        this.jsonName = jsonName;
    }

    /** @return the name used in the animation JSON. */
    public String jsonName() {
        return jsonName;
    }

    /**
     * @param raw the {@code lerp_mode} text as written, or {@code null}
     * @return the matching mode, {@link #LINEAR} when absent, or {@link #UNKNOWN} for an unsupported value
     */
    public static LerpMode fromJson(String raw) {
        if (raw == null) {
            return LINEAR;
        }
        for (LerpMode mode : values()) {
            if (mode != UNKNOWN && mode.jsonName.equalsIgnoreCase(raw)) {
                return mode;
            }
        }
        return UNKNOWN;
    }
}
