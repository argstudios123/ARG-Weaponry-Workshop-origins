package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merges a <em>layer</em> pose into a base pose without replacing the base.
 *
 * <h2>Why an overlay and not another blend</h2>
 * <p>{@link WeaponPoseBlend#blend(WeaponPose, WeaponPose, float)} combines two complete poses: it fills every channel of the
 * result, so a channel the incoming clip does not animate is silently pulled towards an identity value. That is exactly the
 * behaviour an equip cross-fade wants, and exactly the behaviour a movement layer must not have - the shipped walking clips
 * animate {@code root} only and must leave the weapon's hand and weapon-part poses untouched.</p>
 *
 * <p>This overlay therefore walks the <em>layer's</em> bones only, and for each of them copies the position and rotation
 * channels it actually animates, interpolated towards the base by the layer weight. Everything else - every other bone,
 * every channel the layer leaves {@code null} - stays exactly as the base pose produced it. Scale is deliberately never
 * taken from the layer: a movement clip that happens to carry a scale channel cannot rescale the weapon.</p>
 *
 * <h2>How a channel is blended</h2>
 * <ul>
 *   <li><strong>Rotation is interpolated on the shortest arc</strong> ({@link WeaponPoseBlend#blendRotation}). Euler
 *   triples blended component-wise swing through poses that are not between the two on the sphere and take the long way
 *   round across the {@code +-180} wrap; on an arm rig bone that reads as the hand flipping or mirroring. The authored
 *   hand rotations of the shipped rifle sit exactly in that range (about {@code -180} degrees of roll), so the blended
 *   frame has to be geodesic.</li>
 *   <li><strong>Position is interpolated component-wise</strong>, because it is an additive offset in parent space.</li>
 *   <li><strong>A channel the base pose does not animate is blended against the identity delta</strong>, not taken at full
 *   strength. The identity of both channels is {@code (0, 0, 0)}, i.e. "keep the authored transform". Without this, a
 *   layer that animates a channel the base clip leaves alone - the aiming layer on a weapon whose idle clip only animates
 *   hand positions, for instance - would apply its full value on the very first frame of its fade-in: a snap whose size
 *   is the size of the value.</li>
 * </ul>
 * <p>The rule is therefore the same one {@link WeaponPoseBlend} uses, applied per channel instead of per pose.</p>
 */
public final class WeaponPoseLayers {

    private WeaponPoseLayers() {
    }

    /**
     * Overlays one pose on another.
     *
     * @param base   the pose that owns every bone the layer does not animate, may be {@code null}
     * @param layer  the pose to merge in, may be {@code null}
     * @param weight how much of the layer to apply, clamped to {@code [0, 1]}
     * @return the merged pose; exactly {@code base} when nothing can be applied
     */
    public static WeaponPose overlay(WeaponPose base, WeaponPose layer, float weight) {
        float clamped = Float.isFinite(weight) ? Math.max(0.0F, Math.min(1.0F, weight)) : 0.0F;
        if (layer == null || layer.isEmpty() || clamped <= 0.0F) {
            return base;
        }
        WeaponPose source = base == null ? WeaponPose.NONE : base;
        Map<String, SampledBone> bones = new LinkedHashMap<>(source.bones());
        boolean applied = false;
        for (Map.Entry<String, SampledBone> entry : layer.bones().entrySet()) {
            SampledBone layerBone = entry.getValue();
            Vec3 rotation = layerBone.rotation();
            Vec3 position = layerBone.position();
            if (rotation == null && position == null) {
                // Scale-only (or empty) layer bone: nothing this overlay is allowed to apply.
                continue;
            }
            SampledBone baseBone = source.bone(entry.getKey());
            Vec3 baseRotation = baseBone == null ? null : baseBone.rotation();
            Vec3 basePosition = baseBone == null ? null : baseBone.position();
            // A channel the layer does not animate keeps the base value: the layer is an overlay, never a replacement.
            // A channel the base does not animate blends from the identity delta, so a fade-in still starts at the
            // authored transform instead of jumping straight to the layer's value.
            bones.put(entry.getKey(), new SampledBone(entry.getKey(),
                    rotation == null ? baseRotation : WeaponPoseBlend.blendRotation(baseRotation, rotation, clamped),
                    position == null ? basePosition : blendOffset(basePosition, position, clamped),
                    baseBone == null ? null : baseBone.scale()));
            applied = true;
        }
        if (!applied) {
            return source;
        }
        return new WeaponPose(source.animationName(), source.timeSeconds(), bones, source.issues());
    }

    /**
     * Interpolates one position channel from the base offset towards the layer offset.
     *
     * <p>A base that does not animate the channel contributes the identity offset {@code (0, 0, 0)}, so the same weight
     * that scales an animated channel also scales the appearance of a channel that was not animated before.</p>
     *
     * @param base   the base offset, or {@code null} when the base does not animate that channel
     * @param layer  the layer offset, never {@code null}
     * @param weight the layer weight
     * @return the interpolated offset
     */
    private static Vec3 blendOffset(Vec3 base, Vec3 layer, float weight) {
        if (base == null) {
            if (weight >= 1.0F) {
                return layer;
            }
            return new Vec3(layer.x() * weight, layer.y() * weight, layer.z() * weight);
        }
        return new Vec3(
                base.x() + (layer.x() - base.x()) * weight,
                base.y() + (layer.y() - base.y()) * weight,
                base.z() + (layer.z() - base.z()) * weight);
    }
}
