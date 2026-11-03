package com.arg_studios.arg_weaponry.weapon.aiming;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.animation.SampledBone;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ADS constraint: the corrective transform Point Blank Extra Features calls {@code AdsConstraint}.
 *
 * <h2>The rule, exactly as the reference implements it</h2>
 * <p>Every axis has a <em>retention</em> in {@code [0, 1]}. At full ADS a retained axis keeps that fraction of the
 * animated deviation and loses the rest; at partial progress the loss is scaled by the actual ADS progress:</p>
 * <pre>
 * corrected = animated + (animated - 0) * (retention - 1) * progress
 * </pre>
 * <p>The retention is not authored as a separate number. It is encoded in the {@code ads_offset} bone: the absolute
 * value of its authored plus animated rotation in degrees, and the absolute value of its animated position, clamped to
 * one. A value of {@code 0.2} therefore keeps 20 % of that axis at full ADS. This is the reference implementation's
 * {@code constraintRotation} / {@code positionRetention} rule.</p>
 *
 * <h2>What is corrected</h2>
 * <ul>
 *   <li>the weapon's {@code main} bone (ARG's {@code root} is accepted when a model has no {@code main}), because the
 *   reference corrects its root {@code main} snapshot;</li>
 *   <li>optionally the {@code camera} bone. When {@code camera_constraint_modifier} is declared, that retention is used
 *   for all six camera axes; otherwise the camera uses the same {@code ads_offset} values. This is a retention, not an
 *   additive offset: {@code 0.7} keeps 70 % of the camera movement and removes 30 %.</li>
 * </ul>
 * <p>Only animated channels are touched. Authored Blockbench transforms are never rewritten, the {@code ads_offset} bone
 * itself is never modified, and a model missing the retention bone leaves the main pose untouched. When the weapon
 * declares an explicit camera retention, the camera can still be corrected without it. The function is pure and returns
 * the original pose object when nothing changes, so it cannot accumulate corrections across frames.</p>
 */
public final class AdsConstraint {

    /** The bone the reference authors as the weapon's main/root transform. */
    public static final String MAIN_BONE = ModelBoneNames.MAIN;

    /** The fallback main bone used by ARG models that predate the explicit {@code main} bone. */
    public static final String MAIN_BONE_FALLBACK = ModelBoneNames.MAIN_FALLBACK;

    /** The dedicated Blockbench bone that encodes the retention values. */
    public static final String ADS_OFFSET_BONE = ModelBoneNames.ADS_OFFSET;

    /** The camera bone whose animated movement may be constrained too. */
    public static final String CAMERA_BONE = ModelBoneNames.CAMERA;

    private AdsConstraint() {
    }

    /**
     * @param model the weapon model, may be {@code null}
     * @return the bone the constraint corrects, or {@code null} when the model has neither {@code main} nor {@code root}
     */
    public static String mainBoneName(WeaponModel model) {
        if (model == null) {
            return null;
        }
        if (model.bone(MAIN_BONE) != null) {
            return MAIN_BONE;
        }
        return model.bone(MAIN_BONE_FALLBACK) != null ? MAIN_BONE_FALLBACK : null;
    }

    /**
     * Applies the constraint to a pose.
     *
     * @param pose     the sampled animation pose, may be {@code null}
     * @param model    the weapon model, may be {@code null}
     * @param config   the weapon's {@code ads_constraint} block, may be {@code null} or disabled
     * @param progress the actual eased ADS progress in {@code [0, 1]}
     * @return the corrected pose, or the original object when nothing changes
     */
    public static WeaponPose apply(WeaponPose pose, WeaponModel model, WeaponDefinition.AdsConstraint config,
                                   double progress) {
        if (pose == null || pose.isEmpty() || model == null || config == null || !config.enabled()) {
            return pose;
        }
        double weight = Double.isFinite(progress) ? Math.max(0.0, Math.min(1.0, progress)) : 0.0;
        if (weight <= 0.0) {
            return pose;
        }
        String mainName = mainBoneName(model);
        ModelBone offsetBone = model.bone(ADS_OFFSET_BONE);
        if (mainName == null && offsetBone == null
                && !(config.camera() && config.hasCameraConstraint())) {
            return pose;
        }

        SampledBone offset = offsetBone == null ? null : pose.bone(ADS_OFFSET_BONE);
        double[] rotationRetention = offsetBone == null ? new double[] {1.0, 1.0, 1.0}
                : rotationRetention(offsetBone, offset);
        double[] positionRetention = offsetBone == null ? new double[] {1.0, 1.0, 1.0}
                : positionRetention(offset);

        Map<String, SampledBone> corrected = new LinkedHashMap<>(pose.bones());
        boolean changed = false;
        if (mainName != null && offsetBone != null) {
            changed = correct(corrected, pose.bone(mainName), rotationRetention, positionRetention, weight);
        }
        if (config.camera() && !CAMERA_BONE.equals(mainName)) {
            double[] cameraRotation;
            double[] cameraPosition;
            if (config.hasCameraConstraint()) {
                double retention = config.cameraConstraintModifier();
                cameraRotation = new double[] {retention, retention, retention};
                cameraPosition = new double[] {retention, retention, retention};
            } else {
                cameraRotation = rotationRetention;
                cameraPosition = positionRetention;
            }
            changed |= correct(corrected, pose.bone(CAMERA_BONE), cameraRotation, cameraPosition, weight);
        }
        return changed ? new WeaponPose(pose.animationName(), pose.timeSeconds(), corrected, pose.issues()) : pose;
    }

    /**
     * @param bone     the {@code ads_offset} model bone, never {@code null}
     * @param animated its sampled channels, may be {@code null}
     * @return the retention of each rotation axis
     */
    static double[] rotationRetention(ModelBone bone, SampledBone animated) {
        Vec3 authored = bone.rotation();
        Vec3 delta = animated == null ? null : animated.rotation();
        return new double[] {
                retention(authored.x() + (delta == null ? 0.0 : delta.x())),
                retention(authored.y() + (delta == null ? 0.0 : delta.y())),
                retention(authored.z() + (delta == null ? 0.0 : delta.z()))
        };
    }

    /**
     * @param animated the {@code ads_offset} sample, may be {@code null}
     * @return the retention of each position axis; a missing channel is zero, exactly like the reference's zero snapshot
     */
    static double[] positionRetention(SampledBone animated) {
        Vec3 position = animated == null ? null : animated.position();
        if (position == null) {
            return new double[] {0.0, 0.0, 0.0};
        }
        return new double[] {retention(position.x()), retention(position.y()), retention(position.z())};
    }

    /**
     * @param value an authored-plus-animated rotation in degrees or an animated position
     * @return {@code abs(value)} clamped into {@code [0, 1]}
     */
    static double retention(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, Math.abs(value)));
    }

    /**
     * Corrects one bone in the pose map.
     *
     * @return whether the bone's channels changed
     */
    private static boolean correct(Map<String, SampledBone> bones, SampledBone target, double[] rotationRetention,
                                   double[] positionRetention, double weight) {
        if (target == null) {
            return false;
        }
        Vec3 rotation = target.rotation();
        Vec3 position = target.position();
        if (rotation == null && position == null) {
            return false;
        }
        Vec3 correctedRotation = rotation == null ? null : correct(rotation, rotationRetention, weight);
        Vec3 correctedPosition = position == null ? null : correct(position, positionRetention, weight);
        if (same(rotation, correctedRotation) && same(position, correctedPosition)) {
            return false;
        }
        bones.put(target.boneName(), new SampledBone(target.boneName(), correctedRotation, correctedPosition,
                target.scale()));
        return true;
    }

    private static Vec3 correct(Vec3 value, double[] retention, double weight) {
        return new Vec3(
                (float) correct(value.x(), retention[0], weight),
                (float) correct(value.y(), retention[1], weight),
                (float) correct(value.z(), retention[2], weight));
    }

    /**
     * @param animated  the animated channel value
     * @param retention the retained fraction, {@code 1} means untouched
     * @param weight    the ADS progress
     * @return {@code animated + animated * (retention - 1) * weight}
     */
    static double correct(double animated, double retention, double weight) {
        if (!Double.isFinite(animated) || retention >= 1.0 || weight <= 0.0) {
            return animated;
        }
        return animated + animated * (retention - 1.0) * weight;
    }

    private static boolean same(Vec3 first, Vec3 second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        return first.x() == second.x() && first.y() == second.y() && first.z() == second.z();
    }
}
