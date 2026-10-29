package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * The three bone channels the Bedrock animation format can animate.
 *
 * <p>All three are present in the M100 DMR asset: rotation and position are always three-component
 * vectors, while scale additionally appears as a bare number ({@code "scale": 0}), which the format
 * defines as a uniform scale on all three axes.</p>
 */
public enum ChannelKind {

    /** Euler rotation in degrees, applied around the bone pivot. */
    ROTATION("rotation", false),

    /** Translation in model units, applied in the bone's parent space. */
    POSITION("position", false),

    /** Scale multiplier around the bone pivot; defaults to 1. */
    SCALE("scale", true);

    private final String jsonName;
    private final boolean allowsScalar;

    ChannelKind(String jsonName, boolean allowsScalar) {
        this.jsonName = jsonName;
        this.allowsScalar = allowsScalar;
    }

    /** @return the property name used in the animation JSON. */
    public String jsonName() {
        return jsonName;
    }

    /** @return {@code true} when the format allows a single number instead of a vector. */
    public boolean allowsScalar() {
        return allowsScalar;
    }

    /**
     * @param name the property name from the JSON
     * @return the matching kind, or {@code null} when the property is not an animation channel
     */
    public static ChannelKind fromJson(String name) {
        for (ChannelKind kind : values()) {
            if (kind.jsonName.equals(name)) {
                return kind;
            }
        }
        return null;
    }
}
