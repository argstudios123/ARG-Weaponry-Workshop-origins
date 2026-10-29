package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The evaluated value of one bone at one animation time.
 *
 * <p>A channel is {@code null} when the animation does not animate it, which is different from a channel
 * that evaluates to zero: absent channels are never replaced by invented values. Identity defaults
 * (position zero, rotation zero, scale one) belong to the pose layer that consumes this data, not to
 * sampling.</p>
 *
 * @param boneName the bone name as written in the animation file
 * @param rotation the evaluated rotation in degrees, or {@code null} when the animation does not animate it
 * @param position the evaluated position in model units, or {@code null} when the animation does not animate it
 * @param scale    the evaluated scale per axis, or {@code null} when the animation does not animate it
 */
public record SampledBone(String boneName, Vec3 rotation, Vec3 position, Vec3 scale) {

    public SampledBone {
        if (boneName == null || boneName.isBlank()) {
            throw new IllegalArgumentException("A sampled bone must name a bone");
        }
        if (rotation == null && position == null && scale == null) {
            throw new IllegalArgumentException("Bone '" + boneName + "' evaluated no channel");
        }
    }

    /**
     * @param kind the channel to read
     * @return the evaluated value, or {@code null} when the animation does not animate that channel
     */
    public Vec3 channel(ChannelKind kind) {
        return switch (kind) {
            case ROTATION -> rotation;
            case POSITION -> position;
            case SCALE -> scale;
        };
    }

    /** @return {@code true} when at least one channel is present. */
    public boolean hasChannels() {
        return rotation != null || position != null || scale != null;
    }
}
