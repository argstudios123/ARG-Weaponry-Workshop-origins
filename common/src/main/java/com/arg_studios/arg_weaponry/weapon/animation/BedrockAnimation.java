package com.arg_studios.arg_weaponry.weapon.animation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One named animation of a Bedrock animation document.
 *
 * <p>The name is preserved exactly as written, including its prefix (for example
 * {@code anim.model.idle}): no prefix stripping, normalisation or aliasing happens anywhere.</p>
 *
 * @param name          the animation name as written in the file
 * @param lengthSeconds the declared {@code animation_length} in seconds
 * @param loopMode      the declared looping configuration, or {@code null} when the file omits
 *                      {@code loop} (an absent property is not the same as {@code "loop": false})
 * @param bones         the animated bones, keyed by bone name, in file order
 */
public record BedrockAnimation(String name, double lengthSeconds, LoopMode loopMode,
                               Map<String, BoneAnimation> bones) {

    public BedrockAnimation {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An animation must have a name");
        }
        if (!Double.isFinite(lengthSeconds) || lengthSeconds < 0.0) {
            throw new IllegalArgumentException("Invalid animation length for '" + name + "': " + lengthSeconds);
        }
        if (bones == null) {
            throw new IllegalArgumentException("Animation '" + name + "' has no bone map");
        }
        bones = Collections.unmodifiableMap(new LinkedHashMap<>(bones));
    }

    /**
     * @param boneName the bone name as written in the animation file
     * @return the bone animation, or {@code null} when this animation does not animate that bone
     */
    public BoneAnimation bone(String boneName) {
        return bones.get(boneName);
    }

    /** @return {@code true} when the file declared {@code "loop": true}. */
    public boolean isLooping() {
        return loopMode == LoopMode.LOOP;
    }

    /** @return {@code true} when the file declared a {@code loop} property at all. */
    public boolean hasLoopSetting() {
        return loopMode != null;
    }

    /** @return the number of animated bones. */
    public int boneCount() {
        return bones.size();
    }
}
