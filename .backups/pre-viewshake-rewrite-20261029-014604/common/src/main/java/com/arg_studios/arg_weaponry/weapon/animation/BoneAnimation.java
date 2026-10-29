package com.arg_studios.arg_weaponry.weapon.animation;

/**
 * The animation channels of one bone.
 *
 * <p>A bone may animate any subset of {@link ChannelKind}: the M100 DMR asset contains bones with a
 * single channel as well as bones with all three. Bone names are stored exactly as written in the
 * file (for example {@code left_hand}, {@code magazine_with_LH}, {@code RH}) — they are matched against
 * the model later and are never normalised.</p>
 *
 * @param boneName the bone name as written in the animation file
 * @param rotation the rotation channel, or {@code null} when the bone does not animate its rotation
 * @param position the position channel, or {@code null} when the bone does not animate its position
 * @param scale    the scale channel, or {@code null} when the bone does not animate its scale
 */
public record BoneAnimation(String boneName, AnimationChannel rotation, AnimationChannel position,
                            AnimationChannel scale) {

    public BoneAnimation {
        if (boneName == null || boneName.isBlank()) {
            throw new IllegalArgumentException("A bone animation must name a bone");
        }
        if (rotation == null && position == null && scale == null) {
            throw new IllegalArgumentException("Bone '" + boneName + "' animates no channel");
        }
    }

    /**
     * @param kind the channel to read
     * @return the channel, or {@code null} when this bone does not animate it
     */
    public AnimationChannel channel(ChannelKind kind) {
        return switch (kind) {
            case ROTATION -> rotation;
            case POSITION -> position;
            case SCALE -> scale;
        };
    }

    /** @return {@code true} when this bone animates at least one channel. */
    public boolean hasChannels() {
        return rotation != null || position != null || scale != null;
    }
}
