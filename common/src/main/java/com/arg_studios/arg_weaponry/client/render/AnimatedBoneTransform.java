package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.weapon.animation.SampledBone;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.model.BoneTransformPolicy;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames;
import com.arg_studios.arg_weaponry.weapon.model.RotationConvention;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

/**
 * Turns one bone plus its optional animated pose into a {@link PoseStack} transform.
 *
 * <p>This is the single place where the animation composition policy lives, so the renderer only has to
 * walk the hierarchy and draw. The class is client-side because {@link PoseStack} is, but it holds no
 * state and no model data of its own.</p>
 *
 * <h2>Composition policy</h2>
 * <p>Geometry stays in model space, so every bone keeps the pivot sandwich the static renderer uses and
 * the animated properties are inserted between the pivot translation and its inverse:</p>
 * <pre>
 * T(animatedPosition) * T(pivot) * Rz,Ry,Rx(authored + animated) * S(animatedScale) * T(-pivot)
 * </pre>
 * <ul>
 *     <li><strong>Position</strong> is an <em>additive offset</em> in the bone's parent space, applied
 *     before the pivot, so it does not move the pivot itself. The reference implementation (TaCZ
 *     {@code BedrockPart#translateAndRotateAndScale}) translates the animated offset before the pivot in
 *     exactly the same way, and the M100 DMR animations set hand offsets to zero while those bones carry
 *     large authored rotations, which only makes sense if the offset is added rather than replacing a
 *     transform.</li>
 *     <li><strong>Rotation</strong> is <em>added to the authored Euler angles</em> before the single Z, Y, X
 *     rotation is formed. That is Blockbench's own preview behaviour: {@code BoneAnimator#displayRotation}
 *     adds the keyframe degrees to the bone's Euler components and three.js then builds one matrix from the
 *     sum. Composing {@code R(authored) * R(animated)} instead is a different rotation whenever the two do
 *     not commute, which is exactly the case for the hand bones and was the sixth-attempt bug. See
 *     {@link BoneTransformPolicy} for the full derivation.</li>
 *     <li><strong>Scale</strong> is a per-axis multiplier applied about the pivot, matching TaCZ's
 *     {@code scale(...)} call after the rotations and before the pivot inverse.</li>
 * </ul>
 * <p>Every property is handled independently: a channel that the pose does not animate keeps its authored
 * value, an animated channel replaces nothing but adds the animated delta described above, and an animated
 * zero stays zero (a scale of {@code (0, 0, 0)} collapses the bone instead of being ignored).</p>
 *
 * <h2>Rotation space</h2>
 * <p>The authored rest rotation and the animated rotation are both Bedrock-space triples, summed axis by axis, and the
 * sum is passed through {@link RotationConvention#toRenderSpace(Vec3)} before the Z, Y, X rotation is formed. That
 * conversion is what makes the composed frame match the authoring tool's and the reference implementation's camera-space
 * convention; it inverts pitch and roll and keeps yaw. Without it the geometry is placed correctly but every animated
 * tilt happens in the wrong direction. See {@link RotationConvention} for the full derivation and the references that
 * agree on it.</p>
 *
 * <h2>Camera</h2>
 * <p>The {@code camera} bone is a view-animation target, not a placement anchor. Its channels are parsed,
 * sampled and preserved in the pose, but this class deliberately does not apply them to the weapon: in the shipped
 * M100 DMR {@code camera} is a childless root whose pivot is <em>identical</em> to {@code default_view}'s
 * ({@code 4, 10.82, 20.47}), it is animated only in {@code rotation}, and those rotations never exceed 3.5 degrees -
 * a small view sway authored around the eye, not weapon motion. Applying it to the weapon would move the gun
 * relative to the crosshair by a sway the author put on the camera.</p>
 *
 * <p>The reference implementation separates the two roles the same way and is the confirmation that the split is
 * the intended reading of the format: TaCZ's {@code BedrockAnimatedModel} publishes the constant
 * {@code CAMERA_NODE_NAME = "camera"}, hands <em>only</em> that bone's {@code ROTATION} channel to a dedicated
 * {@code CameraAnimationObject} holding a quaternion, and never lets it reach a model part. A milestone that
 * implements view sway will read the same {@link com.arg_studios.arg_weaponry.weapon.animation.WeaponPose} entry;
 * until then nothing applies it to the weapon and nothing applies it to the world camera either, so the reload
 * pose is exactly the authored weapon pose and Milestones 10I/10J camera-space rendering is untouched.</p>
 */
public final class AnimatedBoneTransform {

    private AnimatedBoneTransform() {
    }

    /**
     * Resolves the pose entry that applies to a bone.
     *
     * @param bone the model bone being drawn
     * @param pose the pose being rendered, or {@code null} for static rendering
     * @return the animated transforms for that bone, or {@code null} to keep the authored transform
     */
    public static SampledBone poseFor(ModelBone bone, WeaponPose pose) {
        if (pose == null || pose.isEmpty()) {
            return null;
        }
        if (ModelBoneNames.CAMERA.equals(bone.name())) {
            return null;
        }
        return pose.bone(bone.name());
    }

    /**
     * Applies a bone's transform.
     *
     * @param poseStack the pose stack of the bone's parent
     * @param bone      the bone being drawn
     * @param pose      the animated transforms for this bone, or {@code null} to keep the authored transform
     */
    public static void apply(PoseStack poseStack, ModelBone bone, SampledBone pose) {
        Vec3 offset = pose == null ? null : pose.position();
        Vec3 animatedRotation = pose == null ? null : pose.rotation();
        Vec3 animatedScale = pose == null ? null : pose.scale();

        // The order and the spaces live in the pure policy, so a unit test can pin them; this method only executes them.
        for (BoneTransformPolicy.Step step : BoneTransformPolicy.steps(bone, offset, animatedRotation, animatedScale)) {
            Vec3 value = step.value();
            switch (step.kind()) {
                case TRANSLATE -> poseStack.translate(value.x(), value.y(), value.z());
                case ROTATE_Z -> poseStack.rotateDegrees(Axis.ZP, value.z());
                case ROTATE_Y -> poseStack.rotateDegrees(Axis.YP, value.y());
                case ROTATE_X -> poseStack.rotateDegrees(Axis.XP, value.x());
                case SCALE -> poseStack.scale(value.x(), value.y(), value.z());
            }
        }
    }

    /**
     * Rotates by the exact inverse of the authored rotation order above.
     *
     * <p>The inverse of {@code Rz * Ry * Rx} is {@code Rx^-1 * Ry^-1 * Rz^-1}, so the axes are visited
     * in the opposite direction and every angle is negated. Only view-anchor resolution uses this: an
     * anchor poses the view, so the anchor's own rotation and the rotations of its ancestors have to be
     * removed from the model exactly once.</p>
     *
     * <p>The caller passes the rotation in the space it is composed in - that is, already converted by
     * {@link RotationConvention#toRenderSpace(Vec3)} - so the frame that is removed is exactly the frame the
     * model is drawn with.</p>
     *
     * @param poseStack the pose stack to rotate
     * @param rotation  the rotation to invert, in degrees; {@link Vec3#ZERO} for none
     */
    public static void rotateInverseZYX(PoseStack poseStack, Vec3 rotation) {
        if (rotation.x() != 0.0F) {
            poseStack.rotateDegrees(Axis.XP, -rotation.x());
        }
        if (rotation.y() != 0.0F) {
            poseStack.rotateDegrees(Axis.YP, -rotation.y());
        }
        if (rotation.z() != 0.0F) {
            poseStack.rotateDegrees(Axis.ZP, -rotation.z());
        }
    }
}
