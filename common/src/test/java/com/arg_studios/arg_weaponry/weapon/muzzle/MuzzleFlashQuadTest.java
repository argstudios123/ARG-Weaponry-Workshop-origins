package com.arg_studios.arg_weaponry.weapon.muzzle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The muzzle-flash quad: where it sits, which way it faces and how its sprite frame is sampled.
 *
 * <p>The quad is the part of the flash that was wrong: it used to be rebuilt in camera space, so it turned with the view.
 * These tests pin the corrected contract - centred on the attachment bone's pivot, lying in that bone's own {@code X/Y}
 * plane (which is what makes it follow the barrel instead of the camera), sized in blocks, and sampling exactly one
 * sprite-sheet frame.</p>
 */
class MuzzleFlashQuadTest {

    /** A pivot that is not the origin, so a mistake in the centring cannot pass by accident. */
    private static final Vec3 PIVOT = new Vec3(-0.06F, 6.6F, -24.7F);

    /** The scale the first-person placement produces: {@code 0.42 / 16} blocks per model unit. */
    private static final float BLOCKS_PER_UNIT = 0.42F / 16.0F;

    @Test
    void everyCornerIsCentredOnTheAttachmentBonesPivot() {
        MuzzleFlashQuad.Corner[] corners =
                MuzzleFlashQuad.corners(PIVOT, 0.35D, BLOCKS_PER_UNIT, frame(0, 3));

        float meanX = 0.0F;
        float meanY = 0.0F;
        float meanZ = 0.0F;
        for (MuzzleFlashQuad.Corner corner : corners) {
            meanX += corner.x() / corners.length;
            meanY += corner.y() / corners.length;
            meanZ += corner.z() / corners.length;
        }
        assertEquals(PIVOT.x(), meanX, 1.0E-5F, "the flash is centred on the muzzle bone");
        assertEquals(PIVOT.y(), meanY, 1.0E-5F);
        assertEquals(PIVOT.z(), meanZ, 1.0E-5F);
    }

    @Test
    void theQuadLiesInTheBonesOwnPlaneAndThereforeFollowsTheBarrel() {
        MuzzleFlashQuad.Corner[] corners =
                MuzzleFlashQuad.corners(PIVOT, 0.35D, BLOCKS_PER_UNIT, frame(0, 3));

        for (MuzzleFlashQuad.Corner corner : corners) {
            assertEquals(PIVOT.z(), corner.z(), 1.0E-6F,
                    "every corner keeps the pivot's local z: the quad is perpendicular to the bone's barrel axis and is "
                            + "never rotated towards the camera");
        }
        assertTrue(Math.abs(corners[1].x() - corners[0].x()) > 0.0F, "the quad has a horizontal extent");
        assertTrue(Math.abs(corners[2].y() - corners[1].y()) > 0.0F, "the quad has a vertical extent");
    }

    @Test
    void theEdgeLengthIsTheConfiguredSizeInBlocks() {
        double size = 0.35D;

        MuzzleFlashQuad.Corner[] corners = MuzzleFlashQuad.corners(PIVOT, size, BLOCKS_PER_UNIT, frame(0, 3));

        float widthInBlocks = Math.abs(corners[1].x() - corners[0].x()) * BLOCKS_PER_UNIT;
        float heightInBlocks = Math.abs(corners[2].y() - corners[1].y()) * BLOCKS_PER_UNIT;
        assertEquals(size, widthInBlocks, 1.0E-4F, "the authored size is expressed in blocks, not model units");
        assertEquals(size, heightInBlocks, 1.0E-4F);
    }

    @Test
    void oneFrameOfTheSheetIsSampledThroughTheUvs() {
        MuzzleFlashQuad.Corner[] corners =
                MuzzleFlashQuad.corners(PIVOT, 0.35D, BLOCKS_PER_UNIT, frame(1, 3));

        assertEquals(1.0F / 3.0F, corners[0].u(), 1.0E-6F, "the bottom-left corner is the frame's left edge");
        assertEquals(2.0F / 3.0F, corners[1].u(), 1.0E-6F);
        assertEquals(2.0F / 3.0F, corners[2].u(), 1.0E-6F);
        assertEquals(1.0F / 3.0F, corners[3].u(), 1.0E-6F);
        // v = 0 is the top row of the image, so the upper corners take it: the sprite is not drawn upside down.
        assertEquals(1.0F, corners[0].v(), 1.0E-6F);
        assertEquals(1.0F, corners[1].v(), 1.0E-6F);
        assertEquals(0.0F, corners[2].v(), 1.0E-6F);
        assertEquals(0.0F, corners[3].v(), 1.0E-6F);
        assertTrue(corners[2].y() > corners[0].y(), "the corners that sample the top of the image are the upper ones");
    }

    @Test
    void theCornersAreInPrimitiveOrderSoTheQuadIsNotATwistedRibbon() {
        MuzzleFlashQuad.Corner[] corners =
                MuzzleFlashQuad.corners(PIVOT, 0.35D, BLOCKS_PER_UNIT, frame(0, 3));

        // bottom-left -> bottom-right -> top-right -> top-left: the diagonals are longer than the edges, which is only
        // true when the corners are ordered around the perimeter instead of criss-crossing.
        double edge = distance(corners[0], corners[1]);
        assertEquals(edge, distance(corners[1], corners[2]), 1.0E-5D, "the quad is a square");
        assertEquals(edge, distance(corners[2], corners[3]), 1.0E-5D);
        assertEquals(edge, distance(corners[3], corners[0]), 1.0E-5D);
        assertEquals(edge * Math.sqrt(2.0D), distance(corners[0], corners[2]), 1.0E-4D,
                "the corners are ordered around the perimeter, so the quad's two triangles do not cross");
        assertEquals(edge * Math.sqrt(2.0D), distance(corners[1], corners[3]), 1.0E-4D);
    }

    @Test
    void anUnusableScaleFallsBackInsteadOfCollapsingTheQuad() {
        for (float scale : new float[] {0.0F, -1.0F, Float.NaN, Float.POSITIVE_INFINITY}) {
            MuzzleFlashQuad.Corner[] corners = MuzzleFlashQuad.corners(PIVOT, 0.5D, scale, frame(0, 1));

            for (MuzzleFlashQuad.Corner corner : corners) {
                assertTrue(Float.isFinite(corner.x()) && Float.isFinite(corner.y()) && Float.isFinite(corner.z()),
                        "a degenerate scale must not produce NaN corners (scale " + scale + ")");
            }
            assertEquals(0.5D, Math.abs(corners[1].x() - corners[0].x()), 1.0E-5D,
                    "the fallback is one block per model unit, so the size is still the authored one");
        }
    }

    @Test
    void aMissingPivotAndANegativeSizeAreSurvivable() {
        MuzzleFlashQuad.Corner[] centred = MuzzleFlashQuad.corners(null, 0.35D, BLOCKS_PER_UNIT, frame(0, 1));
        for (MuzzleFlashQuad.Corner corner : centred) {
            assertEquals(0.0F, corner.z(), 1.0E-6F);
        }

        MuzzleFlashQuad.Corner[] collapsed = MuzzleFlashQuad.corners(PIVOT, -2.0D, BLOCKS_PER_UNIT, frame(0, 1));
        for (MuzzleFlashQuad.Corner corner : collapsed) {
            assertTrue(Float.isFinite(corner.x()), "a negative size is clamped, not inverted");
        }
    }

    @Test
    void theScaleFactorIsReadFromThePoseMatrixIncludingAMirroredOne() {
        // The first-person placement scales the model uniformly and mirrors its X axis, so a signed scale helper would
        // return a negative factor; the length of the basis column is positive either way.
        assertEquals(0.42F / 16.0F, MuzzleFlashQuad.blocksPerUnit(0.42F / 16.0F, 0.0F, 0.0F), 1.0E-8F);
        assertEquals(0.42F / 16.0F, MuzzleFlashQuad.blocksPerUnit(-0.42F / 16.0F, 0.0F, 0.0F), 1.0E-8F,
                "a mirrored placement must not produce a negative or inverted size factor");

        // A rotated column keeps its length, which is what makes the factor independent of the weapon's orientation.
        float component = (float) (0.42D / 16.0D / Math.sqrt(3.0D));
        assertEquals(0.42F / 16.0F, MuzzleFlashQuad.blocksPerUnit(component, component, component), 1.0E-7F);

        assertEquals(1.0F, MuzzleFlashQuad.blocksPerUnit(0.0F, 0.0F, 0.0F),
                "a degenerate pose falls back to one block per unit instead of dividing by zero");
        assertEquals(1.0F, MuzzleFlashQuad.blocksPerUnit(Float.NaN, 0.0F, 0.0F));
    }

    // ---------------------------------------------------------------- helpers

    private static MuzzleFlashSpriteSheet.Frame frame(int index, int frameCount) {
        return MuzzleFlashSpriteSheet.frame(index, frameCount);
    }

    private static double distance(MuzzleFlashQuad.Corner first, MuzzleFlashQuad.Corner second) {
        double dx = second.x() - first.x();
        double dy = second.y() - first.y();
        double dz = second.z() - first.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
