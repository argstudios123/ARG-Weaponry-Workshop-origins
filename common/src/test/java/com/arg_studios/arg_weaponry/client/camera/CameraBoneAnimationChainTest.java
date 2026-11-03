package com.arg_studios.arg_weaponry.client.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.animation.SampledBone;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.model.BoneTransformPolicy;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.RotationConvention;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;

/**
 * The camera-bone chain must use the same rotation arithmetic as the geometry.
 *
 * <p>{@link CameraBoneAnimation#chainRotation} used to append the animated rotation as a second quaternion after the
 * authored one. The weapon no longer does that - the animated Euler triple is added to the authored angles before the
 * single Z,Y,X rotation - so a camera that kept the old shape would rotate differently from the weapon it belongs to.
 * This test pins both the composing order and the anti-regression case.</p>
 */
class CameraBoneAnimationChainTest {

    private static final Vec3 ROOT_AUTHORED = new Vec3(10.0F, 20.0F, 30.0F);
    private static final Vec3 CAMERA_AUTHORED = new Vec3(-15.0F, 25.0F, -35.0F);
    private static final Vec3 ROOT_ANIMATED = new Vec3(5.0F, -6.0F, 7.0F);
    private static final Vec3 CAMERA_ANIMATED = new Vec3(8.0F, -9.0F, 10.0F);

    private static final ModelBone CAMERA = new ModelBone("camera", "root",
            new Vec3(4.0F, 10.82F, 20.47F), CAMERA_AUTHORED, List.of(), List.of());
    private static final ModelBone ROOT = new ModelBone("root", null,
            new Vec3(-0.5F, 5.4F, 6.1F), ROOT_AUTHORED, List.of(), List.of(CAMERA));
    private static final WeaponModel MODEL = new WeaponModel(new ResourceId("test", "model/camera"),
            "geometry.test", "1.12.0", 128, 128, List.of(ROOT, CAMERA), List.of(ROOT), 0, 0);

    private static final WeaponPose POSE = new WeaponPose("anim.test", 0.0, Map.of(
            "root", new SampledBone("root", ROOT_ANIMATED, null, null),
            "camera", new SampledBone("camera", CAMERA_ANIMATED, null, null)), List.of());

    @Test
    void eachBoneContributesTheSumOfItsAuthoredAndAnimatedAngles() {
        Quaternionf expected = CameraBoneAnimation.composeRotation(RotationConvention.toRenderSpace(
                BoneTransformPolicy.authoredPlusAnimated(ROOT_AUTHORED, ROOT_ANIMATED)));
        expected.mul(CameraBoneAnimation.composeRotation(RotationConvention.toRenderSpace(
                BoneTransformPolicy.authoredPlusAnimated(CAMERA_AUTHORED, CAMERA_ANIMATED))));

        Quaternionf actual = CameraBoneAnimation.chainRotation(MODEL, POSE, true);
        assertEquals(0.0F, angleDegrees(actual, expected), 1.0E-3F, "the chain must compose the summed angles");
    }

    @Test
    void theRestChainIsTheAuthoredRotationOnly() {
        Quaternionf expected = CameraBoneAnimation.composeRotation(RotationConvention.toRenderSpace(ROOT_AUTHORED));
        expected.mul(CameraBoneAnimation.composeRotation(RotationConvention.toRenderSpace(CAMERA_AUTHORED)));

        Quaternionf actual = CameraBoneAnimation.chainRotation(MODEL, POSE, false);
        assertEquals(0.0F, angleDegrees(actual, expected), 1.0E-3F);
    }

    @Test
    void appendingTheAnimatedRotationAsASecondQuaternionWouldBeDifferent() {
        Quaternionf oldShape = new Quaternionf();
        oldShape.mul(CameraBoneAnimation.composeRotation(RotationConvention.toRenderSpace(ROOT_AUTHORED)));
        oldShape.mul(CameraBoneAnimation.composeRotation(RotationConvention.toRenderSpace(ROOT_ANIMATED)));
        oldShape.mul(CameraBoneAnimation.composeRotation(RotationConvention.toRenderSpace(CAMERA_AUTHORED)));
        oldShape.mul(CameraBoneAnimation.composeRotation(RotationConvention.toRenderSpace(CAMERA_ANIMATED)));

        Quaternionf actual = CameraBoneAnimation.chainRotation(MODEL, POSE, true);
        assertTrue(angleDegrees(actual, oldShape) > 1.0F,
                "the camera chain must not regress to R(authored) * R(animated)");
    }

    @Test
    void anAnimationThatLeavesTheCameraAloneHasNoOrientationChange() {
        WeaponPose still = new WeaponPose("anim.still", 0.0,
                Map.of("camera", new SampledBone("camera", Vec3.ZERO, null, null)), List.of());

        Quaternionf change = CameraBoneAnimation.boneOrientation(MODEL, still);
        assertEquals(0.0F, angleDegrees(change, new Quaternionf()), 1.0E-3F);
    }

    /** @return the smallest angle between two orientations, in degrees, ignoring the quaternion sign. */
    private static float angleDegrees(Quaternionf first, Quaternionf second) {
        double dot = Math.abs(first.x() * second.x() + first.y() * second.y() + first.z() * second.z()
                + first.w() * second.w());
        return (float) Math.toDegrees(2.0 * Math.acos(Math.min(1.0, dot)));
    }
}
