package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed Bedrock animation document.
 *
 * <p>Mirrors {@link com.arg_studios.arg_weaponry.weapon.model.WeaponModel} for geometry: an immutable
 * value carrying the identity of the document it was parsed from, so a diagnostic can name the file
 * after parsing has finished.</p>
 *
 * @param id            the content-pack resource this document was read from
 * @param formatVersion the declared {@code format_version}, or an empty string when absent
 * @param animations    the animations, keyed by name as written, in file order
 */
public record AnimationFile(ResourceId id, String formatVersion, Map<String, BedrockAnimation> animations) {

    public AnimationFile {
        if (id == null) {
            throw new IllegalArgumentException("An animation file must have a resource id");
        }
        if (formatVersion == null) {
            formatVersion = "";
        }
        if (animations == null) {
            throw new IllegalArgumentException("An animation file must have an animation map");
        }
        animations = Collections.unmodifiableMap(new LinkedHashMap<>(animations));
    }

    /**
     * @param name the animation name as written, for example {@code anim.model.idle}
     * @return the animation, or {@code null} when the document does not define it
     */
    public BedrockAnimation animation(String name) {
        return animations.get(name);
    }

    /** @return the animation names in file order; never modifiable. */
    public List<String> names() {
        return List.copyOf(animations.keySet());
    }

    /** @return the number of animations. */
    public int animationCount() {
        return animations.size();
    }

    /** @return {@code true} when the document defines no animation at all. */
    public boolean isEmpty() {
        return animations.isEmpty();
    }
}
