package com.arg_studios.arg_weaponry.client.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The mapping from an authored {@code display_views} entry to the matrix a static render applies.
 *
 * <p>A display transform is content: a pack writes a translation in 1/16 block, a rotation in degrees and a scale, and expects
 * the same result the vanilla item model would produce. Each of those three conventions is pinned here, together with the zero
 * scale a pack uses to hide an item in a context.</p>
 */
class DisplayTransformMatrixTest {

    @Test
    void theTranslationIsAuthoredInSixteenthsOfABlock() {
        Matrix4f matrix = DisplayTransformMatrix.of(new DisplayTransform(
                new Vec3(16.0F, -8.0F, 4.0F), Vec3.ZERO, new Vec3(1.0F, 1.0F, 1.0F)));

        Vector3f origin = matrix.transformPosition(new Vector3f());
        assertEquals(1.0F, origin.x, 1.0E-6F, "16 authored units are one block");
        assertEquals(-0.5F, origin.y, 1.0E-6F);
        assertEquals(0.25F, origin.z, 1.0E-6F);
    }

    @Test
    void theScaleIsAppliedAboutTheModelOrigin() {
        Matrix4f matrix = DisplayTransformMatrix.of(new DisplayTransform(
                Vec3.ZERO, Vec3.ZERO, new Vec3(0.3F, 0.3F, 0.3F)));

        Vector3f point = matrix.transformPosition(new Vector3f(10.0F, 0.0F, 0.0F));
        assertEquals(3.0F, point.x, 1.0E-6F);
    }

    @Test
    void theRotationIsComposedZThenYThenX() {
        Matrix4f matrix = DisplayTransformMatrix.of(new DisplayTransform(
                Vec3.ZERO, new Vec3(0.0F, 90.0F, 0.0F), new Vec3(1.0F, 1.0F, 1.0F)));

        // +X turned 90 degrees about Y points along -Z, the same convention the weapon geometry uses.
        Vector3f point = matrix.transformPosition(new Vector3f(1.0F, 0.0F, 0.0F));
        assertEquals(0.0F, point.x, 1.0E-6F);
        assertEquals(0.0F, point.y, 1.0E-6F);
        assertEquals(-1.0F, point.z, 1.0E-6F);
    }

    @Test
    void anAbsentTransformIsTheIdentity() {
        assertIdentity(DisplayTransformMatrix.of(null), "a context the pack does not declare draws unmoved");
        assertIdentity(DisplayTransformMatrix.of(DisplayTransform.IDENTITY), "the identity transform draws unmoved");
        assertFalse(DisplayTransformMatrix.isCollapsed(DisplayTransformMatrix.of(DisplayTransform.IDENTITY)));
    }

    /**
     * @param matrix  the matrix to test
     * @param message the failure message
     */
    private static void assertIdentity(Matrix4f matrix, String message) {
        assertTrue(matrix.equals(new Matrix4f(), 1.0E-6F), message + " (" + matrix + ")");
    }

    @Test
    void aZeroScaleIsRecognisedAsHidden() {
        Matrix4f collapsed = DisplayTransformMatrix.of(new DisplayTransform(
                Vec3.ZERO, Vec3.ZERO, new Vec3(0.0F, 0.0F, 0.0F)));
        assertTrue(DisplayTransformMatrix.isCollapsed(collapsed),
                "a pack hides the weapon in a context by scaling it to nothing");
    }

    @Test
    void aTransformThatOnlyTranslatesIsNotHidden() {
        Matrix4f matrix = DisplayTransformMatrix.of(new DisplayTransform(
                new Vec3(0.0F, -2.75F, -2.5F), Vec3.ZERO, new Vec3(0.3F, 0.3F, 0.3F)));
        assertFalse(DisplayTransformMatrix.isCollapsed(matrix));
    }
}
