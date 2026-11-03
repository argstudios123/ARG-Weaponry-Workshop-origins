package com.arg_studios.arg_weaponry.weapon.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Parent-child, pivot and hand-bone transforms, checked against the same independent Blockbench model used by
 * {@link BoneTransformConformanceTest}.
 *
 * <p>The single-bone conformance test cannot catch an error in how a child inherits a parent's transform, nor an error
 * that only appears once a real hand bone's authored rotation is combined with a real animation keyframe. This test
 * covers both: a synthetic three-bone chain with pivots and animated parents, and the shipped {@code left_hand} /
 * {@code right_hand} bones under their real parent chains with the rotation and position values the reload and inspect
 * clips contain.</p>
 */
class BoneTransformHierarchyTest {

    // The shipped M100 DMR chain that carries the two arms.
    private static final ModelBone ROOT = new ModelBone("root", null,
            new Vec3(-0.55147F, 5.4375F, 6.0625F), Vec3.ZERO, List.of(), List.of());
    private static final ModelBone RIGHT_ARM = new ModelBone("Right_arm_with_rifle", "root",
            new Vec3(0.0F, 6.61624F, 6.57838F), Vec3.ZERO, List.of(), List.of());
    private static final ModelBone RIGHT_HAND = new ModelBone("right_hand", "Right_arm_with_rifle",
            new Vec3(-1.29676F, 1.28467F, 9.79183F), new Vec3(-87.4905F, 4.9952F, -179.7814F), List.of(), List.of());
    private static final ModelBone MAGAZINE = new ModelBone("magazine_with_LH", "root",
            new Vec3(0.0F, 1.34375F, 3.28125F), Vec3.ZERO, List.of(), List.of());
    private static final ModelBone LEFT_HAND = new ModelBone("left_hand", "magazine_with_LH",
            new Vec3(1.7159F, 4.25175F, -5.82138F), new Vec3(-106.9144F, 53.3595F, 47.7684F), List.of(), List.of());

    /** Centre of the shipped {@code left_hand} 4x12x4 placeholder cube, in model space. */
    private static final Vec3 LEFT_HAND_CUBE_CENTRE = new Vec3(1.7159F, 8.658F, -5.82138F);

    /** Centre of the shipped {@code right_hand} 4x12x4 placeholder cube, in model space. */
    private static final Vec3 RIGHT_HAND_CUBE_CENTRE = new Vec3(-2.0015F, 5.3087F, 10.14486F);

    @Test
    void aStaticParentChildChainMatchesBlockbench() {
        List<ModelBone> chain = List.of(ROOT, RIGHT_ARM, RIGHT_HAND);

        assertPointMatches(chain, RIGHT_HAND_CUBE_CENTRE, Map.of(), Map.of(), Map.of());
    }

    @Test
    void anAnimatedParentChildChainMatchesBlockbench() {
        ModelBone root = new ModelBone("root", null, new Vec3(1.0F, -2.0F, 3.0F), new Vec3(10.0F, -20.0F, 30.0F),
                List.of(), List.of());
        ModelBone middle = new ModelBone("middle", "root", new Vec3(-4.0F, 5.0F, 6.0F),
                new Vec3(0.0F, 15.0F, -25.0F), List.of(), List.of());
        ModelBone leaf = new ModelBone("leaf", "middle", new Vec3(2.5F, -3.5F, 1.5F),
                new Vec3(40.0F, -50.0F, 60.0F), List.of(), List.of());
        List<ModelBone> chain = List.of(root, middle, leaf);

        Map<String, Vec3> positions = Map.of("root", new Vec3(1.0F, 2.0F, -3.0F),
                "leaf", new Vec3(-2.0F, 4.0F, 1.0F));
        Map<String, Vec3> rotations = Map.of("root", new Vec3(5.0F, -10.0F, 15.0F),
                "middle", new Vec3(0.0F, 20.0F, 0.0F),
                "leaf", new Vec3(30.0F, -40.0F, -10.0F));
        Map<String, Vec3> scales = Map.of("middle", new Vec3(1.0F, 1.0F, 1.0F),
                "leaf", new Vec3(1.0F, 1.5F, 1.0F));

        assertPointMatches(chain, new Vec3(1.5F, -2.5F, 3.5F), positions, rotations, scales);
    }

    @Test
    void theLeftHandUnderItsRealChainMatchesBlockbench() {
        List<ModelBone> chain = List.of(ROOT, MAGAZINE, LEFT_HAND);

        // anim.model.reload at 0.24 s and anim.model.inspect at 3.2083 s.
        assertPointMatches(chain, LEFT_HAND_CUBE_CENTRE,
                Map.of("left_hand", new Vec3(-1.0375F, -6.155F, 9.145F)),
                Map.of("left_hand", new Vec3(16.178F, -28.0016F, 14.5366F)),
                Map.of("left_hand", new Vec3(1.0F, 1.5F, 1.0F)));
        assertPointMatches(chain, LEFT_HAND_CUBE_CENTRE,
                Map.of("left_hand", new Vec3(6.46375F, -6.02F, 5.61125F)),
                Map.of("left_hand", new Vec3(-6.2678F, -77.1174F, -57.3588F)),
                Map.of("left_hand", new Vec3(1.0F, 1.5F, 1.0F)));
    }

    @Test
    void theRightHandUnderItsRealChainMatchesBlockbench() {
        List<ModelBone> chain = List.of(ROOT, RIGHT_ARM, RIGHT_HAND);

        // anim.model.inspect at 10.25 s: a 35 degree pitch on a bone whose authored pitch is -87.49 degrees.
        assertPointMatches(chain, RIGHT_HAND_CUBE_CENTRE,
                Map.of("right_hand", new Vec3(-0.19F, 2.9075F, -0.9025F)),
                Map.of("right_hand", new Vec3(35.4374F, -4.5639F, 4.3688F)),
                Map.of("right_hand", new Vec3(1.0F, 1.5F, 1.0F)));
    }

    @Test
    void aZeroAnimatedRotationLeavesTheAuthoredHandPoseAlone() {
        List<ModelBone> chain = List.of(ROOT, MAGAZINE, LEFT_HAND);

        assertEquals(LEFT_HAND.rotation(), BoneTransformPolicy.authoredPlusAnimated(LEFT_HAND.rotation(), Vec3.ZERO),
                "an animated zero is the identity delta in the Euler addition");
        assertPointMatches(chain, LEFT_HAND_CUBE_CENTRE,
                Map.of("left_hand", new Vec3(0.40388F, -1.02659F, 0.76725F)),
                Map.of("left_hand", Vec3.ZERO),
                Map.of("left_hand", new Vec3(1.0F, 1.5F, 1.0F)));
    }

    // ---------------------------------------------------------------- helpers

    private static void assertPointMatches(List<ModelBone> chain, Vec3 point, Map<String, Vec3> positions,
                                           Map<String, Vec3> rotations, Map<String, Vec3> scales) {
        Vec3 expected = BlockbenchTransformReference.transformPoint(chain, point, positions, rotations, scales);
        Vec3 actual = transform(chain, point, positions, rotations, scales);
        assertEquals(expected.x(), actual.x(), 1.0E-3, "x of the transformed point");
        assertEquals(expected.y(), actual.y(), 1.0E-3, "y of the transformed point");
        assertEquals(expected.z(), actual.z(), 1.0E-3, "z of the transformed point");
    }

    /** Applies the renderer's per-bone steps down the hierarchy to one model-space point. */
    private static Vec3 transform(List<ModelBone> chain, Vec3 point, Map<String, Vec3> positions,
                                  Map<String, Vec3> rotations, Map<String, Vec3> scales) {
        double[][] total = BlockbenchTransformReference.identity();
        for (ModelBone bone : chain) {
            total = BlockbenchTransformReference.multiply(total, compose(BoneTransformPolicy.steps(bone,
                    positions.get(bone.name()), rotations.get(bone.name()), scales.get(bone.name()))));
        }
        double[] transformed = BlockbenchTransformReference.multiply(total,
                new double[] {point.x(), point.y(), point.z(), 1.0});
        return new Vec3((float) transformed[0], (float) transformed[1], (float) transformed[2]);
    }

    private static double[][] compose(List<BoneTransformPolicy.Step> steps) {
        double[][] result = BlockbenchTransformReference.identity();
        for (BoneTransformPolicy.Step step : steps) {
            Vec3 value = step.value();
            double[][] next = switch (step.kind()) {
                case TRANSLATE -> BlockbenchTransformReference.translation(value.x(), value.y(), value.z());
                case ROTATE_X -> BlockbenchTransformReference.rotationX(value.x());
                case ROTATE_Y -> BlockbenchTransformReference.rotationY(value.y());
                case ROTATE_Z -> BlockbenchTransformReference.rotationZ(value.z());
                case SCALE -> BlockbenchTransformReference.scale(value.x(), value.y(), value.z());
            };
            result = BlockbenchTransformReference.multiply(result, next);
        }
        return result;
    }
}
