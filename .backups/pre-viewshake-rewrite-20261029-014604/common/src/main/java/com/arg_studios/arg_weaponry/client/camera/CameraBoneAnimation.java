package com.arg_studios.arg_weaponry.client.camera;

import com.arg_studios.arg_weaponry.client.render.ViewBoneTransform;
import com.arg_studios.arg_weaponry.weapon.animation.SampledBone;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames;
import com.arg_studios.arg_weaponry.weapon.model.RotationConvention;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Turns the evaluated {@code camera} bone of a weapon pose into the camera change Minecraft needs.
 *
 * <h2>Which bone, and why it is not weapon geometry</h2>
 * <p>{@code camera} is an animation target, not a placement anchor and not a part of the weapon: the shipped M100 DMR
 * keeps it at the same pivot as {@code default_view}, and its clips animate only that bone's rotation by a few degrees - the
 * authored first-person view sway. {@code AnimatedBoneTransform#poseFor} already refuses to apply it to the weapon, so the
 * same {@link WeaponPose} entry that is ignored there is read here. One animation timeline, two consumers.</p>
 *
 * <h2>The conversion, and the direction bug Milestone 10S fixed</h2>
 * <p>The bone rotation is authored in Bedrock space, so it goes through {@link RotationConvention} exactly like every other
 * rotation this renderer composes, and is composed in the same ZYX order. That gives {@code R_model}, the rotation the bone
 * applies to model-space geometry, and with {@code M = diag(-1, 1, 1)} the bone's <em>orientation</em> as seen from the
 * camera is {@code D = M * R_model * M}.</p>
 *
 * <p>Milestone 10R applied {@code D} to the view, which is wrong in a way that shows up on every axis: a point that is fixed
 * in the world appears in camera space as {@code c = V * w}, so changing the camera's world orientation from {@code R0} to
 * {@code R1} changes it to {@code R1^-1 * R0 * c}. A camera bone describes the <strong>camera</strong>, so the new world
 * orientation is {@code R1 = R0 * D} and the appearance change is therefore {@code R_cam = D^-1}, not {@code D}. Using
 * {@code D} rotates the world the way the model would rotate, which is the exact opposite of turning the camera by that
 * angle - the reported "everything looks inverted" symptom. Applying {@code D^-1} is also what makes the mapping
 * consistent with the position channel, where the camera simply moves with the bone.</p>
 *
 * <p>Minecraft stores the camera as {@code R = Ry(pi - yaw) * Rx(-pitch)} with view {@code R^-1}, so the required new world
 * rotation is {@code R1 = R0 * D}, and {@code D = R_cam^-1}. The rotation is decomposed back into yaw, pitch and an optional
 * roll, and applied with vanilla's own {@code setRotation} plus a roll about the view axis.</p>
 *
 * <h2>The rest pose and the parents</h2>
 * <p>{@code D} is the bone's orientation <em>change</em>, not the animated triple on its own: see
 * {@link #boneOrientation(WeaponModel, WeaponPose)}, which composes {@code R_parent... * R_rest * R_animated} and
 * removes {@code R_parent... * R_rest}. A bone with an identity rest rotation and no rotated parent - including the
 * shipped M100 DMR - gets exactly the same value as before, so this correction only changes models that the old
 * "the animated value is the whole orientation" assumption got wrong.</p>
 *
 * <h2>Position</h2>
 * <p>A position channel is not discarded. It is converted to blocks with the same model-to-view scale the weapon uses and
 * mirrored in X like every other position, and it is applied to the camera position while the view model receives the
 * opposite offset - which is exactly the relationship the rotation has, so the weapon stays visually locked in the world.</p>
 *
 * <h2>Scale</h2>
 * <p>A scale channel has no representation in Minecraft's world-view transform: turning it into a field-of-view change
 * would be a different feature with a different meaning. The authored scale is therefore <em>not</em> applied, and
 * {@link #hasScaleChannel(WeaponPose)} reports it so the caller can log it once instead of ignoring it silently.</p>
 */
public final class CameraBoneAnimation {

    /** The bone the camera animation lives on. */
    public static final String CAMERA_BONE = ModelBoneNames.CAMERA;

    private CameraBoneAnimation() {
    }

    /**
     * @param model the weapon model, may be {@code null}
     * @return whether the model declares a {@code camera} bone at all
     */
    public static boolean hasCameraBone(WeaponModel model) {
        return model != null && model.bonesByName().containsKey(CAMERA_BONE);
    }

    /**
     * @param pose the evaluated pose, may be {@code null}
     * @return whether the pose animates the {@code camera} bone
     */
    public static boolean hasCameraChannels(WeaponPose pose) {
        return pose != null && pose.hasBone(CAMERA_BONE);
    }

    /**
     * @param pose the evaluated pose, may be {@code null}
     * @return whether the pose animates a scale for the {@code camera} bone, which cannot be represented
     */
    public static boolean hasScaleChannel(WeaponPose pose) {
        if (!hasCameraChannels(pose)) {
            return false;
        }
        SampledBone bone = pose.bone(CAMERA_BONE);
        return bone != null && bone.scale() != null;
    }

    /**
     * Turns an evaluated pose into the camera-space change its {@code camera} bone asks for.
     *
     * <p>The result is a pure function of the pose: it contains no camera angles, no time and no previous state, which is
     * what lets it be sampled from whichever frame needs it (the level camera and the view model, at the same timestamp)
     * without either of them drifting or accumulating. The base orientation is applied later, by
     * {@link CameraComposition#compose}, so the live mouse-controlled angles are never replaced by a value captured earlier.</p>
     *
     * @param model the weapon model, for the bone lookup (its static rotation is the rest pose and is not applied)
     * @param pose  the evaluated pose of the animation being rendered
     * @return the transform to apply, or {@code null} when the model has no {@code camera} bone or the pose animates none of
     *         its channels
     */
    public static CameraTransform compute(WeaponModel model, WeaponPose pose) {
        if (!hasCameraBone(model) || !hasCameraChannels(pose)) {
            return null;
        }
        SampledBone sampled = pose.bone(CAMERA_BONE);
        if (sampled == null || !sampled.hasChannels()) {
            return null;
        }

        // D: the bone's orientation relative to its own rest pose, as seen from the camera. R_rel = R_animated * R_rest^-1
        // is the modelled frame's change (parents included), and the mirror M converts it into camera space exactly the
        // way it converts the weapon geometry. R_cam = D^-1 is how camera-space coordinates change, which is the rotation
        // the view model applies.
        Quaternionf boneOrientation = boneOrientation(model, pose);
        Quaternionf cameraSpaceRotation = boneOrientation.conjugate(new Quaternionf());

        // The convenience angles are the transform's rotation measured against a zero base. Callers that need the final
        // orientation compose them with the live camera through CameraComposition; nothing here depends on that camera.
        CameraComposition.ComposedCamera delta = CameraComposition.compose(0.0F, 0.0F,
                new CameraTransform(0.0F, 0.0F, 0.0F, cameraSpaceRotation.x(), cameraSpaceRotation.y(),
                        cameraSpaceRotation.z(), cameraSpaceRotation.w(), 0.0F, 0.0F, 0.0F));

        float scale = ViewBoneTransform.SCALE / ViewBoneTransform.MODEL_UNITS_PER_BLOCK;
        float translationX = 0.0F;
        float translationY = 0.0F;
        float translationZ = 0.0F;
        if (sampled.position() != null) {
            Vec3 position = sampled.position();
            // Mirrored in X like every other position in this pipeline, and converted to blocks with the view scale.
            translationX = -position.x() * scale;
            translationY = position.y() * scale;
            translationZ = position.z() * scale;
        }

        return new CameraTransform(delta.yaw(), delta.pitch(), delta.roll(), cameraSpaceRotation.x(),
                cameraSpaceRotation.y(), cameraSpaceRotation.z(), cameraSpaceRotation.w(), translationX, translationY,
                translationZ);
    }

    /**
     * The camera-space rotation of an authored camera-bone rotation: {@code M * R_model * M}.
     *
     * <p>This is the <em>identity-rest</em> case of {@link #boneOrientation(WeaponModel, WeaponPose)}: it treats the
     * authored triple as the whole rotation, which is exactly right when the {@code camera} bone has no authored rest
     * rotation and no rotated parent - the shipped M100 DMR is such a model.</p>
     *
     * @param authoredRotation the evaluated bone rotation in degrees, may be {@code null}
     * @return the rotation, identity when the channel is absent
     */
    public static Quaternionf cameraSpaceRotation(Vec3 authoredRotation) {
        if (authoredRotation == null) {
            return new Quaternionf();
        }
        return toCameraSpace(composeRotation(RotationConvention.toRenderSpace(authoredRotation)));
    }

    /**
     * The {@code camera} bone's orientation change, from its authored rest orientation to its animated one, expressed in
     * camera space.
     *
     * <h4>Why the rest pose and the parents matter</h4>
     * <p>An animation channel does not replace a bone's authored rotation: {@code AnimatedBoneTransform} composes
     * {@code R(rest) * R(animated)} inside the pivot, and TaCZ's {@code BedrockPart} composes the animated quaternion
     * after the authored one in exactly the same way. So the animated triple is a <strong>delta applied after the rest
     * rotation</strong>, not an absolute orientation. Using it as if it were absolute is only correct when the rest
     * rotation is the identity - true for the shipped M100 DMR camera bone, false for any model whose camera bone is
     * tilted and for any model where a parent of the camera bone is rotated or animated.</p>
     *
     * <p>The modelled orientation of the bone is {@code R_world = R_parent... * R_rest * R_animated}; before the
     * animation it was {@code R_world_rest = R_parent... * R_rest}. The rotation that takes one to the other, expressed
     * in the model frame, is {@code R_rel = R_world * R_world_rest^-1}. Converting it into camera space is the same
     * mirror conjugation the geometry goes through: {@code M * R_rel * M}.</p>
     *
     * @param model the weapon model, may be {@code null}
     * @param pose  the evaluated pose, may be {@code null}
     * @return the orientation change in camera space, identity when there is nothing to apply
     */
    public static Quaternionf boneOrientation(WeaponModel model, WeaponPose pose) {
        Quaternionf animated = chainRotation(model, pose, true);
        Quaternionf rest = chainRotation(model, pose, false);
        // R_world * R_world_rest^-1: left-multiplication keeps the change in the model frame, which is the frame the
        // camera's own orientation lives in.
        Quaternionf relative = animated.mul(rest.conjugate(new Quaternionf()), new Quaternionf());
        return toCameraSpace(relative);
    }

    /**
     * @param model the weapon model, may be {@code null}
     * @return the {@code camera} bone's rest orientation in camera space, i.e. the frame its animation is a delta on
     */
    public static Quaternionf restOrientation(WeaponModel model) {
        return toCameraSpace(chainRotation(model, null, false));
    }

    /**
     * Builds the modelled orientation of the {@code camera} bone, from the roots down to it.
     *
     * <p>Every rotation goes through {@link RotationConvention#toRenderSpace(Vec3)} and the renderer's Z-Y-X
     * composition order, so the value is the same frame {@code AnimatedBoneTransform} draws with - there is exactly one
     * rotation convention in this renderer and the camera reads it instead of restating it.</p>
     *
     * @param model    the weapon model, may be {@code null}
     * @param pose     the pose to include when {@code animated} is {@code true}, may be {@code null}
     * @param animated whether the animated channel of each bone is part of the result
     * @return the modelled orientation, identity when the model has no camera bone
     */
    public static Quaternionf chainRotation(WeaponModel model, WeaponPose pose, boolean animated) {
        Quaternionf total = new Quaternionf();
        if (model == null) {
            return total;
        }
        for (ModelBone bone : chain(model)) {
            total.mul(composeRotation(RotationConvention.toRenderSpace(bone.rotation())));
            if (animated && pose != null) {
                SampledBone sampled = pose.bone(bone.name());
                if (sampled != null && sampled.rotation() != null) {
                    total.mul(composeRotation(RotationConvention.toRenderSpace(sampled.rotation())));
                }
            }
        }
        return total;
    }

    /**
     * @param model the weapon model, may be {@code null}
     * @return the root-to-{@code camera} bone chain, empty when the model has no camera bone
     */
    public static java.util.List<ModelBone> chain(WeaponModel model) {
        if (model == null) {
            return java.util.List.of();
        }
        ModelBone camera = model.bone(CAMERA_BONE);
        if (camera == null) {
            return java.util.List.of();
        }
        java.util.List<ModelBone> reversed = new java.util.ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        ModelBone current = camera;
        while (current != null && visited.add(current.name())) {
            reversed.add(current);
            current = current.parent() == null ? null : model.bone(current.parent());
        }
        java.util.Collections.reverse(reversed);
        return java.util.List.copyOf(reversed);
    }

    /**
     * @param renderRotation an already converted rotation triple in degrees, may be {@code null}
     * @return the Z-Y-X composition of that triple, the order every rotation in this renderer uses
     */
    public static Quaternionf composeRotation(Vec3 renderRotation) {
        if (renderRotation == null) {
            return new Quaternionf();
        }
        return new Quaternionf()
                .rotateZ(degreesToRadians(renderRotation.z()))
                .rotateY(degreesToRadians(renderRotation.y()))
                .rotateX(degreesToRadians(renderRotation.x()));
    }

    /**
     * @param rotation a rotation in the Bedrock model frame
     * @return the same rotation in camera space, {@code M * R * M} with {@code M = diag(-1, 1, 1)}
     */
    public static Quaternionf toCameraSpace(Quaternionf rotation) {
        Matrix3f mirror = new Matrix3f().scaling(-1.0F, 1.0F, 1.0F);
        Matrix3f conjugated = mirror.mul(new Matrix3f().rotation(rotation), new Matrix3f()).mul(mirror);
        return new Quaternionf().setFromNormalized(new org.joml.Matrix4f(conjugated));
    }

    private static float degreesToRadians(float degrees) {
        return (float) Math.toRadians(degrees);
    }
}
