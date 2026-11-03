package com.arg_studios.arg_weaponry.weapon.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The shared Bedrock rotation: composition order, sign handling and pivot behaviour.
 *
 * <p>The expected side is built from explicit 4x4 matrices ({@code Rz * Ry * Rx}), not from the class under test, so the
 * test states the <em>rule</em>: Blockbench's {@code ZYX} order. It also pins the two mistakes this class exists to
 * prevent - rotating in {@code X,Y,Z} order, and applying the triple without the render-space conversion.</p>
 */
class BedrockRotationTest {

    @Test
    void aSingleAxisRotationTurnsTheExpectedDirection() {
        assertVec(new Vec3(0.0F, 1.0F, 0.0F), BedrockRotation.rotate(new Vec3(1.0F, 0.0F, 0.0F),
                new Vec3(0.0F, 0.0F, 90.0F)), "a +90 degree roll turns +X into +Y");
        assertVec(new Vec3(0.0F, 0.0F, 1.0F), BedrockRotation.rotate(new Vec3(0.0F, 1.0F, 0.0F),
                new Vec3(90.0F, 0.0F, 0.0F)), "a +90 degree pitch turns +Y into +Z");
        assertVec(new Vec3(0.0F, 0.0F, -1.0F), BedrockRotation.rotate(new Vec3(1.0F, 0.0F, 0.0F),
                new Vec3(0.0F, 90.0F, 0.0F)), "a +90 degree yaw turns +X into -Z");
    }

    @Test
    void aMultiAxisRotationMatchesRzRyRx() {
        Vec3 degrees = new Vec3(30.0F, -40.0F, 55.0F);
        Vec3 point = new Vec3(1.5F, -2.25F, 3.75F);

        assertVec(expected(point, degrees), BedrockRotation.rotate(point, degrees),
                "the composition must be Rz * Ry * Rx");
    }

    @Test
    void theWrongCompositionOrderWouldGiveADifferentAnswer() {
        Vec3 degrees = new Vec3(30.0F, -40.0F, 55.0F);
        Vec3 point = new Vec3(1.5F, -2.25F, 3.75F);

        Vec3 xyz = xyzComposition(point, degrees);

        assertTrue(distance(BedrockRotation.rotate(point, degrees), xyz) > 0.2F,
                "applying X, then Y, then Z to the point (the old cube path) must not be mistaken for the right order");
    }

    @Test
    void theRenderSpaceConversionInvertsPitchAndRollOfTheComposedRotation() {
        Vec3 authored = new Vec3(-18.75F, 0.0F, 0.0F);
        Vec3 point = new Vec3(0.0F, 0.0F, 7.1F);

        Vec3 applied = BedrockRotation.rotate(point, RotationConvention.toRenderSpace(authored));
        Vec3 unconverted = BedrockRotation.rotate(point, authored);

        assertTrue(distance(applied, unconverted) > 1.0F,
                "a cube rotation applied without the conversion swings a long part the wrong way");
    }

    @Test
    void rotatingAboutAPivotLeavesThePivotInPlace() {
        Vec3 pivot = new Vec3(2.0F, 3.0F, 4.0F);

        assertVec(pivot, BedrockRotation.rotateAround(pivot, pivot, new Vec3(11.0F, 22.0F, 33.0F)),
                "the pivot is the fixed point of its own rotation");
    }

    @Test
    void aZeroRotationReturnsTheInputUnchanged() {
        Vec3 point = new Vec3(1.0F, 2.0F, 3.0F);

        assertEquals(point, BedrockRotation.rotate(point, Vec3.ZERO));
        assertEquals(point, BedrockRotation.rotate(point, null));
        assertEquals(point, BedrockRotation.rotateAround(point, new Vec3(9.0F, 9.0F, 9.0F), Vec3.ZERO));
        assertEquals(Vec3.ZERO, BedrockRotation.rotate(null, new Vec3(1.0F, 2.0F, 3.0F)));
    }

    private static Vec3 expected(Vec3 point, Vec3 degrees) {
        double[][] rotation = BlockbenchTransformReference.zyx(degrees);
        double[] result = BlockbenchTransformReference.multiply(rotation,
                new double[]{point.x(), point.y(), point.z(), 1.0});
        return new Vec3((float) result[0], (float) result[1], (float) result[2]);
    }

    /** The composition the old cube path performed: rotate by X, then Y, then Z. */
    private static Vec3 xyzComposition(Vec3 point, Vec3 degrees) {
        double[][] rotation = BlockbenchTransformReference.multiply(
                BlockbenchTransformReference.multiply(BlockbenchTransformReference.rotationX(degrees.x()),
                        BlockbenchTransformReference.rotationY(degrees.y())),
                BlockbenchTransformReference.rotationZ(degrees.z()));
        double[] result = BlockbenchTransformReference.multiply(rotation,
                new double[]{point.x(), point.y(), point.z(), 1.0});
        return new Vec3((float) result[0], (float) result[1], (float) result[2]);
    }

    private static void assertVec(Vec3 expected, Vec3 actual, String message) {
        assertEquals(expected.x(), actual.x(), 1.0E-4, message + " (x)");
        assertEquals(expected.y(), actual.y(), 1.0E-4, message + " (y)");
        assertEquals(expected.z(), actual.z(), 1.0E-4, message + " (z)");
    }

    private static float distance(Vec3 first, Vec3 second) {
        float dx = first.x() - second.x();
        float dy = first.y() - second.y();
        float dz = first.z() - second.z();
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
