package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a parsed animation at a requested time.
 *
 * <p>Pure and stateless: it reads the immutable Stage 8A data, never writes to it, holds no playback state,
 * reads no clock, and touches no Minecraft, renderer or loader class. Sampling the same animation at the
 * same time always returns an equal result.</p>
 *
 * <h2>Channel evaluation</h2>
 * <ul>
 *     <li><strong>Constant channels</strong> return their stored value at every time. The value is not
 *     normalised, clamped or interpolated; a scalar scale of {@code 0} stays {@code (0, 0, 0)}.</li>
 *     <li><strong>Linear</strong> segments interpolate component-wise from the start keyframe's outgoing
 *     value to the end keyframe's incoming value (see {@link KeyframeValues}).</li>
 *     <li><strong>Catmull-Rom</strong> segments use {@link CatmullRomInterpolation} with the neighbouring
 *     keyframes as outer control points.</li>
 * </ul>
 *
 * <h2>Segment interpolation mode</h2>
 * <p>A segment uses Catmull-Rom when <em>either</em> of its two keyframes declares {@code "catmullrom"}, and
 * linear otherwise. That mirrors the reference implementation (TaCZ
 * {@code CustomInterpolator#interpolate}), which checks both endpoints of the segment. Mixing modes inside
 * one channel is therefore supported, including per-segment changes.</p>
 *
 * <h2>Endpoints</h2>
 * <p>Time is first mapped by {@link AnimationTime}, so the sampler itself only ever sees a time inside
 * {@code [0, length]}. Within a channel:</p>
 * <ul>
 *     <li>at or before the first keyframe: the first keyframe's outgoing value;</li>
 *     <li>at or after the last keyframe: the last keyframe's outgoing value, so the terminal pose is held;</li>
 *     <li>exactly on a keyframe: that keyframe's outgoing value, i.e. the curve is right-continuous and a
 *     {@code pre}/{@code post} discontinuity resolves to {@code post};</li>
 *     <li>a channel with a single keyframe returns that keyframe's outgoing value at every time.</li>
 * </ul>
 *
 * <p>For Catmull-Rom, a missing outer control point is replaced by the segment endpoint itself (the first
 * keyframe for the first segment, the last keyframe for the final segment). The reference implementation
 * clamps its neighbour indices the same way.</p>
 *
 * <h2>Unsupported interpolation</h2>
 * <p>A keyframed channel that contains a keyframe whose interpolation mode is unknown is <em>not</em>
 * sampled: it is reported through {@link SampledAnimation#issues()} and omitted from the result, so the
 * caller keeps whatever value it already had instead of receiving a silently linear approximation. Nothing is
 * logged here, so repeated sampling cannot flood the log.</p>
 */
public final class AnimationSampler {

    private AnimationSampler() {
    }

    /**
     * Samples an animation at a requested time.
     *
     * @param animation        the animation to evaluate
     * @param requestedSeconds the requested time in seconds; may be negative or beyond the animation's length,
     *                         in which case the animation's loop mode decides how it is mapped
     * @return the evaluated channels, or a result with no bones when the animation animates nothing
     * @throws IllegalArgumentException when the animation is {@code null} or the time is {@code NaN}/infinite
     */
    public static SampledAnimation sample(BedrockAnimation animation, double requestedSeconds) {
        if (animation == null) {
            throw new IllegalArgumentException("Cannot sample a missing animation");
        }
        double time = AnimationTime.resolve(animation, requestedSeconds);

        Map<String, SampledBone> bones = new LinkedHashMap<>();
        List<SamplingIssue> issues = new ArrayList<>();
        for (BoneAnimation bone : animation.bones().values()) {
            Vec3 rotation = sampleChannel(bone.boneName(), bone.rotation(), ChannelKind.ROTATION, time, issues);
            Vec3 position = sampleChannel(bone.boneName(), bone.position(), ChannelKind.POSITION, time, issues);
            Vec3 scale = sampleChannel(bone.boneName(), bone.scale(), ChannelKind.SCALE, time, issues);
            if (rotation == null && position == null && scale == null) {
                continue;
            }
            bones.put(bone.boneName(), new SampledBone(bone.boneName(), rotation, position, scale));
        }
        return new SampledAnimation(animation.name(), time, bones, issues);
    }

    /**
     * Samples one channel.
     *
     * @return the evaluated value, or {@code null} when the channel is absent or cannot be evaluated
     */
    private static Vec3 sampleChannel(String boneName, AnimationChannel channel, ChannelKind kind, double time,
                                      List<SamplingIssue> issues) {
        if (channel == null) {
            return null;
        }
        if (channel instanceof AnimationChannel.Constant constant) {
            return constant.value();
        }
        List<Keyframe> keyframes = ((AnimationChannel.Keyframed) channel).keyframes();
        String unsupportedMode = unsupportedMode(keyframes);
        if (unsupportedMode != null) {
            issues.add(SamplingIssue.unsupportedInterpolation(boneName, kind, unsupportedMode));
            return null;
        }
        return sampleKeyframes(keyframes, time);
    }

    /**
     * @return the declared text of the first unknown interpolation mode in the channel, or {@code null} when
     *         every keyframe uses a supported mode
     */
    private static String unsupportedMode(List<Keyframe> keyframes) {
        for (Keyframe keyframe : keyframes) {
            if (keyframe.lerpMode() == LerpMode.UNKNOWN) {
                return keyframe.declaredLerpMode() == null ? LerpMode.UNKNOWN.jsonName()
                        : keyframe.declaredLerpMode();
            }
        }
        return null;
    }

    /**
     * Evaluates an ascending keyframe list. The list is never sorted or modified here: Stage 8A already
     * stores keyframes in ascending time order.
     *
     * @param keyframes the keyframes of one channel, at least one entry
     * @param time      a time inside the channel's range, after clamping or wrapping
     * @return the evaluated value
     */
    private static Vec3 sampleKeyframes(List<Keyframe> keyframes, double time) {
        Keyframe first = keyframes.get(0);
        if (time <= first.timeSeconds()) {
            return KeyframeValues.outgoing(first);
        }
        Keyframe last = keyframes.get(keyframes.size() - 1);
        if (time >= last.timeSeconds()) {
            return KeyframeValues.outgoing(last);
        }

        int index = segmentIndex(keyframes, time);
        Keyframe start = keyframes.get(index);
        Keyframe end = keyframes.get(index + 1);
        if (time == start.timeSeconds()) {
            return KeyframeValues.exact(start);
        }

        Vec3 startValue = KeyframeValues.outgoing(start);
        Vec3 endValue = KeyframeValues.incoming(end);
        if (isCatmullRom(start, end)) {
            Vec3 previous = KeyframeValues.outgoing(keyframes.get(Math.max(0, index - 1)));
            Vec3 next = KeyframeValues.incoming(keyframes.get(Math.min(keyframes.size() - 1, index + 2)));
            return CatmullRomInterpolation.apply(previous, startValue, endValue, next, start.timeSeconds(),
                    end.timeSeconds(), time);
        }
        return LinearInterpolation.apply(start.timeSeconds(), startValue, end.timeSeconds(), endValue, time);
    }

    /**
     * @return the index of the keyframe at or before {@code time}, always a valid segment start
     */
    private static int segmentIndex(List<Keyframe> keyframes, double time) {
        int low = 0;
        int high = keyframes.size() - 1;
        while (high - low > 1) {
            int middle = (low + high) >>> 1;
            if (keyframes.get(middle).timeSeconds() <= time) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /**
     * @return {@code true} when either end of the segment declares Catmull-Rom interpolation
     */
    private static boolean isCatmullRom(Keyframe start, Keyframe end) {
        return start.lerpMode() == LerpMode.CATMULLROM || end.lerpMode() == LerpMode.CATMULLROM;
    }
}
