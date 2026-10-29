package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The single place where the meaning of {@code pre} and {@code post} is decided.
 *
 * <p>The animation format lets one keyframe declare two values: the value approached from the left
 * ({@code pre}) and the value left towards the right ({@code post}). This helper isolates that policy so a
 * later stage can change it without touching the sampler:</p>
 *
 * <ul>
 *     <li>{@link #outgoing(Keyframe)} - the value a segment <em>leaves</em> a keyframe with:
 *     {@code post} when declared, otherwise the keyframe's effective value. Because {@link
 *     Keyframe#value()} is already {@code post}, else {@code pre}, else the plain vector, this is exactly
 *     {@code keyframe.value()}.</li>
 *     <li>{@link #incoming(Keyframe)} - the value a segment <em>arrives</em> at a keyframe with:
 *     {@code pre} when declared, otherwise the effective value.</li>
 *     <li>{@link #exact(Keyframe)} - the value at the keyframe's own timestamp: {@code outgoing}, i.e. the
 *     curve is right-continuous, so sampling exactly on a discontinuity yields the {@code post} value.</li>
 * </ul>
 *
 * <p>A keyframe that declares only {@code pre} uses that value on both sides, and a keyframe that declares
 * only {@code post} uses that value on both sides, so neither declaration is ever discarded.</p>
 *
 * <p>The interpolation engine in the reference mod (TaCZ {@code CustomInterpolator.doOtherLerp}) uses the
 * same rule for linear segments: the segment start is the current keyframe's {@code post} and the segment
 * end is the next keyframe's {@code pre}.</p>
 */
public final class KeyframeValues {

    private KeyframeValues() {
    }

    /**
     * @param keyframe the keyframe a segment starts from
     * @return {@code post} when declared, otherwise the keyframe's effective value
     */
    public static Vec3 outgoing(Keyframe keyframe) {
        return keyframe.value();
    }

    /**
     * @param keyframe the keyframe a segment ends on
     * @return {@code pre} when declared, otherwise the keyframe's effective value
     */
    public static Vec3 incoming(Keyframe keyframe) {
        return keyframe.hasPre() ? keyframe.pre() : keyframe.value();
    }

    /**
     * @param keyframe the keyframe being sampled exactly
     * @return the right-continuous value of the keyframe, that is {@link #outgoing(Keyframe)}
     */
    public static Vec3 exact(Keyframe keyframe) {
        return outgoing(keyframe);
    }

    /**
     * @param keyframe the keyframe to inspect
     * @return {@code true} when the keyframe declares different {@code pre} and {@code post} values, so the
     *         curve jumps at its timestamp
     */
    public static boolean isDiscontinuous(Keyframe keyframe) {
        return keyframe.hasPre() && keyframe.hasPost() && !keyframe.pre().equals(keyframe.post());
    }
}
