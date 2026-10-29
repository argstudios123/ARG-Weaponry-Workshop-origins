package com.arg_studios.arg_weaponry.weapon.animation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The evaluated state of one animation at one resolved time.
 *
 * <p>Immutable and free of playback state: the same animation sampled at the same time always produces
 * an equal result, and sampling never modifies the parsed animation it reads.</p>
 *
 * @param animationName      the animation this result was sampled from
 * @param timeSeconds        the resolved sampling time, after clamping or wrapping (see {@link AnimationTime})
 * @param bones              the evaluated bones, keyed by the bone name as written, in file order
 * @param issues             the channels that could not be evaluated; never {@code null}
 */
public record SampledAnimation(String animationName, double timeSeconds, Map<String, SampledBone> bones,
                               List<SamplingIssue> issues) {

    /** The result of sampling an animation that animates nothing. */
    public static final SampledAnimation EMPTY = new SampledAnimation("", 0.0, Map.of(), List.of());

    public SampledAnimation {
        if (animationName == null) {
            throw new IllegalArgumentException("A sampled animation must name its animation");
        }
        if (!Double.isFinite(timeSeconds)) {
            throw new IllegalArgumentException("Invalid sampling time: " + timeSeconds);
        }
        if (bones == null) {
            throw new IllegalArgumentException("A sampled animation must have a bone map");
        }
        bones = Collections.unmodifiableMap(new LinkedHashMap<>(bones));
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /**
     * @param boneName the bone name as written in the animation file
     * @return the evaluated bone, or {@code null} when the animation does not animate that bone
     */
    public SampledBone bone(String boneName) {
        return bones.get(boneName);
    }

    /** @return {@code true} when no bone was evaluated. */
    public boolean isEmpty() {
        return bones.isEmpty();
    }

    /** @return the number of evaluated bones. */
    public int boneCount() {
        return bones.size();
    }

    /** @return {@code true} when at least one channel could not be evaluated. */
    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
