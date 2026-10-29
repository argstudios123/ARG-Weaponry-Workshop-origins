package com.arg_studios.arg_weaponry.weapon.animation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable per-evaluation bone pose: the evaluated transform of every animated bone.
 *
 * <p>A pose is a value, not state. It carries no timers, no scheduling information, no model bones and no
 * rendering objects, so it can be handed to a renderer, compared, stored or discarded without any other
 * code being affected. It never writes back into {@link BedrockAnimation}, {@link SampledAnimation} or the
 * model it will be applied to.</p>
 *
 * <h2>Missing channels are not zeros</h2>
 * <p>Each channel of each bone is independent. A channel that the animation does not animate stays
 * {@code null} here, and the consumer must keep the authored (static) value for it. A channel that the
 * animation <em>does</em> animate is always present, even when it evaluates to exactly zero, so an
 * authored zero (notably a scale of {@code (0, 0, 0)} that hides a bone) is never mistaken for a missing
 * channel. This is the approved "Option A" policy: preserve what is not animated, apply what is.</p>
 *
 * <h2>Sampling issues</h2>
 * <p>A channel whose interpolation mode is unsupported is omitted from {@link #bones()} by the sampler and
 * reported in {@link #issues()}. Such a channel therefore behaves like a missing channel — the authored
 * value stays — but the reason remains inspectable instead of being silently treated as a zero.</p>
 *
 * @param animationName the animation this pose was evaluated from, for diagnostics
 * @param timeSeconds   the resolved sample time this pose belongs to, for diagnostics
 * @param bones         the evaluated bones, keyed by the bone name as written in the animation file
 * @param issues        the channels that could not be evaluated; never {@code null}
 */
public record WeaponPose(String animationName, double timeSeconds, Map<String, SampledBone> bones,
                         List<SamplingIssue> issues) {

    /** A pose that animates nothing; applying it is the same as rendering statically. */
    public static final WeaponPose NONE = new WeaponPose("", 0.0, Map.of(), List.of());

    public WeaponPose {
        if (animationName == null) {
            throw new IllegalArgumentException("A pose must name its animation");
        }
        if (!Double.isFinite(timeSeconds)) {
            throw new IllegalArgumentException("Invalid pose time: " + timeSeconds);
        }
        if (bones == null) {
            throw new IllegalArgumentException("A pose must have a bone map");
        }
        bones = Collections.unmodifiableMap(new LinkedHashMap<>(bones));
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /**
     * Converts a sampled animation into a pose.
     *
     * <p>Absent channels stay absent, explicit zeros stay zero, and unsupported channels stay reported: no
     * identity default is invented anywhere on this path.</p>
     *
     * @param sampled the sampler result
     * @return the pose, or {@link #NONE} shaped value when the animation animated nothing
     * @throws IllegalArgumentException when {@code sampled} is {@code null}
     */
    public static WeaponPose fromSampledAnimation(SampledAnimation sampled) {
        if (sampled == null) {
            throw new IllegalArgumentException("Cannot build a pose from a missing sample");
        }
        return new WeaponPose(sampled.animationName(), sampled.timeSeconds(), sampled.bones(), sampled.issues());
    }

    /**
     * @param boneName the bone name as written in the animation file
     * @return the evaluated bone, or {@code null} when the pose does not animate that bone
     */
    public SampledBone bone(String boneName) {
        return bones.get(boneName);
    }

    /** @return {@code true} when the pose animates the given bone. */
    public boolean hasBone(String boneName) {
        return bones.containsKey(boneName);
    }

    /** @return {@code true} when no bone is animated, so applying the pose changes nothing. */
    public boolean isEmpty() {
        return bones.isEmpty();
    }

    /** @return the number of animated bones. */
    public int boneCount() {
        return bones.size();
    }

    /** @return {@code true} when at least one channel could not be evaluated. */
    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}
