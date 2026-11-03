package com.arg_studios.arg_weaponry.weapon.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The composition a bone contributes, pinned against an independent reimplementation of Blockbench's transform.
 *
 * <h2>What is compared</h2>
 * <p>The expected side of this test is written from Blockbench's own rules, not from the renderer's code:</p>
 * <ul>
 *   <li>a Bedrock file's positions and pivots are shown mirrored in X, and its rotations are shown with X and Y
 *   negated;</li>
 *   <li>rotations compose in the Z, Y, X order, degree values are used directly;</li>
 *   <li>a bone is {@code T(pivot) R T(-pivot)}, an animated position moves the pivot, an animated rotation is
 *   <strong>added to the authored Euler angles</strong> and the single Z,Y,X matrix is built from the sum, and an
 *   animated scale multiplies about the pivot.</li>
 * </ul>
 * <p>{@link BoneTransformPolicy#steps(ModelBone, Vec3, Vec3, Vec3)} publishes what the renderer will do; this test turns those
 * steps into a matrix with its own small 4x4 implementation and compares the result with the Blockbench model. The hand bones
 * are the interesting case: their authored rotations are large and asymmetric, so a wrong axis, a wrong order, a
 * double-applied transform or a rotation that is composed instead of added cannot hide the way it does on the nearly
 * symmetric weapon body.</p>
 */
class BoneTransformConformanceTest {

    private static final ModelBone RIGHT_HAND = new ModelBone("right_hand", "Right_arm_with_rifle",
            new Vec3(-1.29676F, 1.28467F, 9.79183F), new Vec3(-87.4905F, 4.9952F, -179.7814F), List.of(), List.of());

    private static final ModelBone LEFT_HAND = new ModelBone("left_hand", "magazine_with_LH",
            new Vec3(1.7159F, 4.25175F, -5.82138F), new Vec3(-106.9144F, 53.3595F, 47.7684F), List.of(), List.of());

    private static final ModelBone DUSTCOVER = new ModelBone("dustcover", "gunbody",
            new Vec3(-1.05549F, 6.22887F, 3.66091F), new Vec3(0.0F, 0.0F, 7.5F), List.of(), List.of());

    /** The idle animation's channels for the hand bones: no rotation, a small offset and a 1.5x length. */
    private static final Vec3 IDLE_POSITION = new Vec3(0.0F, 0.0F, 2.0625F);
    private static final Vec3 IDLE_SCALE = new Vec3(1.0F, 1.5F, 1.0F);
    private static final Vec3 NO_ROTATION = Vec3.ZERO;

    /** A real reload keyframe of {@code left_hand}: 16.178, -28.0016, 14.5366 degrees. */
    private static final Vec3 RELOAD_LEFT_HAND_ROTATION = new Vec3(16.178F, -28.0016F, 14.5366F);

    @Test
    void theHandBonesMatchTheBlockbenchTransform() {
        assertMatchesBlockbench(RIGHT_HAND, IDLE_POSITION, NO_ROTATION, IDLE_SCALE);
        assertMatchesBlockbench(LEFT_HAND, new Vec3(0.30056F, -0.76397F, 0.57098F), NO_ROTATION, IDLE_SCALE);
    }

    @Test
    void aBoneWithOnlyARollMatchesTheBlockbenchTransform() {
        assertMatchesBlockbench(DUSTCOVER, null, null, null);
    }

    @Test
    void anAnimatedRotationIsAddedToTheAuthoredEulerAnglesBeforeOneZyxRotation() {
        // Blockbench's BoneAnimator#displayRotation does bone.rotation.x += arr[0]; three.js then builds one ZYX matrix
        // from the sum. The authored left-hand rotation is around -107 / 53 / 48 degrees, so this is the case where
        // composing two matrices instead of adding the angles is visibly different (more than 50 degrees here).
        assertMatchesBlockbench(LEFT_HAND, new Vec3(0.40388F, -1.02659F, 0.76725F), RELOAD_LEFT_HAND_ROTATION,
                IDLE_SCALE);

        List<BoneTransformPolicy.Step> steps = BoneTransformPolicy.steps(LEFT_HAND, null, RELOAD_LEFT_HAND_ROTATION,
                null);
        Vec3 combined = BoneTransformPolicy.authoredPlusAnimated(LEFT_HAND.rotation(), RELOAD_LEFT_HAND_ROTATION);
        Vec3 converted = RotationConvention.toRenderSpace(combined);
        assertEquals(List.of(BoneTransformPolicy.Kind.TRANSLATE,
                        BoneTransformPolicy.Kind.ROTATE_Z,
                        BoneTransformPolicy.Kind.ROTATE_Y,
                        BoneTransformPolicy.Kind.ROTATE_X,
                        BoneTransformPolicy.Kind.TRANSLATE),
                steps.stream().map(BoneTransformPolicy.Step::kind).toList(),
                "one rotation triple only: Z, then Y, then X");
        assertEquals(converted.z(), steps.get(1).value().z(), 1.0E-6, "Z is the sum of the authored and animated Z");
        assertEquals(converted.y(), steps.get(2).value().y(), 1.0E-6, "Y is the sum of the authored and animated Y");
        assertEquals(converted.x(), steps.get(3).value().x(), 1.0E-6, "X is the sum of the authored and animated X");
    }

    @Test
    void composingTwoRotationsIsNotTheSameAsAddingTheirAngles() {
        // Anti-regression: the sixth-attempt implementation multiplied R(authored) * R(animated). This test fails if
        // that shape ever comes back, even if the new expected matrix is accidentally copied into the implementation.
        double[][] actual = compose(BoneTransformPolicy.steps(LEFT_HAND, null, RELOAD_LEFT_HAND_ROTATION, null));

        Vec3 displayedPivot = BlockbenchTransformReference.displayedPosition(LEFT_HAND.pivot());
        double[][] oldShape = BlockbenchTransformReference.translation(displayedPivot.x(), displayedPivot.y(),
                displayedPivot.z());
        oldShape = BlockbenchTransformReference.multiply(oldShape,
                BlockbenchTransformReference.zyx(BlockbenchTransformReference.displayedRotation(LEFT_HAND.rotation())));
        oldShape = BlockbenchTransformReference.multiply(oldShape, BlockbenchTransformReference.zyx(
                BlockbenchTransformReference.displayedRotation(RELOAD_LEFT_HAND_ROTATION)));
        oldShape = BlockbenchTransformReference.multiply(oldShape, BlockbenchTransformReference.translation(
                -displayedPivot.x(), -displayedPivot.y(), -displayedPivot.z()));
        double[][] oldInModelSpace = BlockbenchTransformReference.mirrorConjugate(oldShape);

        assertTrue(largestDifference(actual, oldInModelSpace) > 1.0,
                "the old composition and the Blockbench addition must differ on a hand bone; the fix would otherwise "
                        + "not be observable");
    }

    @Test
    void everySignedTranslationMatchesBlockbench() {
        ModelBone bone = new ModelBone("translated", null, new Vec3(1.5F, -2.25F, 3.75F), Vec3.ZERO, List.of(),
                List.of());
        float[] values = {-7.0F, 7.0F};
        for (int axis = 0; axis < 3; axis++) {
            for (float value : values) {
                assertMatchesBlockbench(bone, component(axis, value), null, null);
            }
        }
        assertMatchesBlockbench(bone, new Vec3(3.0F, -4.0F, 5.0F), null, null);
    }

    @Test
    void everySignedRotationMatchesBlockbench() {
        ModelBone bone = new ModelBone("rotated", null, new Vec3(-2.0F, 3.5F, 1.25F), Vec3.ZERO, List.of(),
                List.of());
        float[] values = {-25.0F, 25.0F};
        for (int axis = 0; axis < 3; axis++) {
            for (float value : values) {
                assertMatchesBlockbench(bone, null, component(axis, value), null);
            }
        }
        assertMatchesBlockbench(bone, null, new Vec3(20.0F, -35.0F, 50.0F), null);
    }

    @Test
    void aCombinedPositionAndRotationMatchesBlockbench() {
        assertMatchesBlockbench(RIGHT_HAND, new Vec3(-4.0F, 2.5F, -1.75F), new Vec3(18.5793F, -6.4485F, -18.3507F),
                new Vec3(1.0F, 1.5F, 1.0F));
        assertMatchesBlockbench(LEFT_HAND, new Vec3(3.0F, -6.0F, 8.0F), RELOAD_LEFT_HAND_ROTATION, null);
    }

    @Test
    void differentPivotsMatchBlockbench() {
        Vec3 rotation = new Vec3(15.0F, -25.0F, 35.0F);
        Vec3 position = new Vec3(-2.0F, 1.0F, 4.0F);
        assertMatchesBlockbench(new ModelBone("origin", null, Vec3.ZERO, Vec3.ZERO, List.of(), List.of()), position,
                rotation, null);
        assertMatchesBlockbench(new ModelBone("positive", null, new Vec3(8.0F, 12.0F, 3.0F), Vec3.ZERO, List.of(),
                List.of()), position, rotation, null);
        assertMatchesBlockbench(new ModelBone("negative", null, new Vec3(-8.0F, -12.0F, -3.0F), Vec3.ZERO, List.of(),
                List.of()), position, rotation, null);
    }

    @Test
    void anAnimationCanCancelTheAuthoredRotation() {
        // The additive rule has a well-defined zero: an animated triple equal to the negative of the rest rotation
        // leaves no rotation at all. That is the same result Blockbench shows, and it is a useful guard against a
        // "sum the angles but also keep the authored rotation" implementation.
        ModelBone bone = new ModelBone("cancelled", null, new Vec3(2.0F, -3.0F, 4.0F), new Vec3(30.0F, -20.0F, 10.0F),
                List.of(), List.of());

        assertMatchesBlockbench(bone, new Vec3(1.0F, 2.0F, 3.0F), new Vec3(-30.0F, 20.0F, -10.0F), null);
    }

    @Test
    void theMirrorConversionFlipsPitchAndRollButNotYaw() {
        Vec3 converted = RotationConvention.toRenderSpace(new Vec3(10.0F, 20.0F, 30.0F));

        assertEquals(-10.0F, converted.x(), 1.0E-6);
        assertEquals(20.0F, converted.y(), 1.0E-6);
        assertEquals(-30.0F, converted.z(), 1.0E-6);
    }

    @Test
    void everyStepUsesTheDocumentedSpacesAndOrder() {
        List<BoneTransformPolicy.Step> steps = BoneTransformPolicy.steps(RIGHT_HAND, IDLE_POSITION, NO_ROTATION,
                IDLE_SCALE);

        assertEquals(List.of(BoneTransformPolicy.Kind.TRANSLATE,
                        BoneTransformPolicy.Kind.TRANSLATE,
                        BoneTransformPolicy.Kind.ROTATE_Z,
                        BoneTransformPolicy.Kind.ROTATE_Y,
                        BoneTransformPolicy.Kind.ROTATE_X,
                        BoneTransformPolicy.Kind.SCALE,
                        BoneTransformPolicy.Kind.TRANSLATE),
                steps.stream().map(BoneTransformPolicy.Step::kind).toList(),
                "position first, then the pivot, then Z/Y/X rotations, the scale and the pivot inverse");

        assertEquals(IDLE_POSITION, steps.getFirst().value(), "the animated offset stays in model units");
        Vec3 converted = RotationConvention.toRenderSpace(RIGHT_HAND.rotation());
        assertEquals(new Vec3(converted.x(), 0.0F, 0.0F), steps.get(4).value(),
                "the authored rotation is converted and composed Z, then Y, then X");
        assertEquals(IDLE_SCALE, steps.get(5).value());
        assertEquals(new Vec3(1.29676F, -1.28467F, -9.79183F), steps.getLast().value(),
                "the pivot inverse closes the sandwich");
    }

    // ---------------------------------------------------------------- comparison helpers

    /** Compares the renderer's steps with Blockbench's own composition. */
    private static void assertMatchesBlockbench(ModelBone bone, Vec3 position, Vec3 rotation, Vec3 scale) {
        double[][] actual = compose(BoneTransformPolicy.steps(bone, position, rotation, scale));
        double[][] expected = BlockbenchTransformReference.modelMatrix(bone, position, rotation, scale);
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                assertEquals(expected[row][column], actual[row][column], 1.0E-4,
                        "matrix [" + row + "][" + column + "] of '" + bone.name() + "'");
            }
        }
    }

    /** Composes the policy's steps exactly the way the renderer executes them. */
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

    private static Vec3 component(int axis, float value) {
        return switch (axis) {
            case 0 -> new Vec3(value, 0.0F, 0.0F);
            case 1 -> new Vec3(0.0F, value, 0.0F);
            default -> new Vec3(0.0F, 0.0F, value);
        };
    }

    private static double largestDifference(double[][] first, double[][] second) {
        double largest = 0.0;
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                largest = Math.max(largest, Math.abs(first[row][column] - second[row][column]));
            }
        }
        return largest;
    }
}
