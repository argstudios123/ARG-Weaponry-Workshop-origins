package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * A single keyframe of an animation channel.
 *
 * <p>The Bedrock format writes a keyframe in two shapes, both preserved here:</p>
 * <ul>
 *     <li>a plain vector, for example {@code "0.0167": [0, 0, 2.2]} — then {@link #value()} holds the
 *     vector and {@link #pre()}/{@link #post()} are {@code null} because the file declared no
 *     separate values;</li>
 *     <li>an object with {@code pre}, {@code post} and {@code lerp_mode}, for example
 *     {@code {"post": [0, 0.03326, 0], "lerp_mode": "catmullrom"}} — then {@link #pre()} and/or
 *     {@link #post()} hold the declared values exactly as written.</li>
 * </ul>
 *
 * <p>{@link #value()} is always the effective value of the keyframe ({@code post}, else {@code pre},
 * else the plain vector). {@code pre} and {@code post} stay independently represented <em>even when
 * they are equal</em>, because the difference between "declared both" and "declared one" matters for
 * Catmull-Rom tangents.</p>
 *
 * @param timeSeconds     the keyframe time in seconds, never negative, never non-finite
 * @param value           the effective value; always present
 * @param pre             the {@code pre} value as written, or {@code null} when the file did not declare one
 * @param post            the {@code post} value as written, or {@code null} when the file did not declare one
 * @param lerpMode        the interpolation mode; {@link LerpMode#LINEAR} when the file did not declare one
 * @param declaredLerpMode the raw {@code lerp_mode} text, or {@code null} when the file did not declare one
 */
public record Keyframe(double timeSeconds, Vec3 value, Vec3 pre, Vec3 post, LerpMode lerpMode, String declaredLerpMode) {

    public Keyframe {
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0) {
            throw new IllegalArgumentException("Invalid keyframe time: " + timeSeconds);
        }
        if (value == null) {
            throw new IllegalArgumentException("A keyframe must have a value");
        }
        if (lerpMode == null) {
            lerpMode = LerpMode.LINEAR;
        }
    }

    /**
     * Creates a keyframe written as a plain vector.
     *
     * @param timeSeconds the keyframe time in seconds
     * @param value       the vector as written
     * @return the keyframe, with no {@code pre}/{@code post} values and linear interpolation
     */
    public static Keyframe plain(double timeSeconds, Vec3 value) {
        return new Keyframe(timeSeconds, value, null, null, LerpMode.LINEAR, null);
    }

    /**
     * Creates a keyframe written as an object.
     *
     * @param timeSeconds      the keyframe time in seconds
     * @param pre              the {@code pre} value, or {@code null}
     * @param post             the {@code post} value, or {@code null}
     * @param lerpMode         the resolved interpolation mode
     * @param declaredLerpMode the raw {@code lerp_mode} text, or {@code null}
     * @return the keyframe
     */
    public static Keyframe of(double timeSeconds, Vec3 pre, Vec3 post, LerpMode lerpMode, String declaredLerpMode) {
        Vec3 effective = post != null ? post : pre;
        if (effective == null) {
            throw new IllegalArgumentException("An object keyframe must declare a pre or a post value");
        }
        return new Keyframe(timeSeconds, effective, pre, post, lerpMode, declaredLerpMode);
    }

    /** @return {@code true} when the file wrote this keyframe as a plain vector. */
    public boolean isPlain() {
        return pre == null && post == null;
    }

    /** @return {@code true} when the keyframe declared a {@code pre} value. */
    public boolean hasPre() {
        return pre != null;
    }

    /** @return {@code true} when the keyframe declared a {@code post} value. */
    public boolean hasPost() {
        return post != null;
    }

    /** @return {@code true} when the keyframe declared an interpolation mode. */
    public boolean hasDeclaredLerpMode() {
        return declaredLerpMode != null;
    }
}
