package com.arg_studios.arg_weaponry.weapon.muzzle;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The four corners of a muzzle-flash quad, expressed in the space of the attachment bone.
 *
 * <h2>Why the corners live in bone space</h2>
 * <p>A muzzle flash belongs to the weapon, not to the screen. The quad is therefore built in the bone's own frame - centred on
 * the bone's pivot, lying in the bone's local {@code X/Y} plane and facing along the bone's local {@code Z} axis, which is the
 * axis Bedrock models point their barrel down. The renderer only has to submit these corners through the bone's accumulated
 * transform, and the flash then inherits the whole hierarchy: the weapon placement, the aiming blend, the recoil kickback, the
 * sway, and every animated bone transform above and including the muzzle bone.</p>
 *
 * <p>A camera-facing billboard is the one thing this geometry must not be: it would rotate with the view instead of with the
 * weapon, which is exactly the artefact this class replaces.</p>
 *
 * <h2>Units</h2>
 * <p>The configured {@code size} is in blocks, because that is the unit a creator can reason about, while bone space is model
 * units. The conversion factor is supplied by the caller as <em>blocks per model unit</em> - the uniform scale of the
 * accumulated pose - so the quad keeps its authored size in the world whichever transform chain produced it (first person,
 * aiming view or an item transform).</p>
 *
 * <p>Pure: no Minecraft types, no allocation per vertex beyond the returned array, so the placement rule is unit tested
 * directly and the renderer only converts.</p>
 */
public final class MuzzleFlashQuad {

    /** The number of corners one flash primitive is written with. */
    public static final int CORNER_COUNT = 4;

    /**
     * One corner of the quad.
     *
     * @param x the model-space x, in the bone's own frame
     * @param y the model-space y, in the bone's own frame
     * @param z the model-space z, in the bone's own frame
     * @param u the horizontal texture coordinate
     * @param v the vertical texture coordinate
     */
    public record Corner(float x, float y, float z, float u, float v) {
    }

    /**
     * Reads the uniform scale of a pose matrix from one of its basis columns.
     *
     * <p>The renderer needs to know how many blocks one model unit covers, and the accumulated pose is the only place that
     * knows: the first-person placement, the blended aiming placement and an item transform all scale the model differently.
     * The length of a basis column is exactly that factor, and it is deliberately computed from the vector length rather
     * than read from a signed scale helper, so a mirrored matrix (the model-to-view transform mirrors Bedrock's X
     * handedness) yields the same positive factor as an unmirrored one instead of a negative value that would invert or
     * collapse the quad.</p>
     *
     * @param xAxisX the first component of the matrix' first basis column ({@code m00})
     * @param xAxisY the second component ({@code m01})
     * @param xAxisZ the third component ({@code m02})
     * @return the blocks-per-model-unit factor, or {@code 1} when the column is degenerate
     */
    public static float blocksPerUnit(float xAxisX, float xAxisY, float xAxisZ) {
        double length = Math.sqrt((double) xAxisX * xAxisX + (double) xAxisY * xAxisY + (double) xAxisZ * xAxisZ);
        return length > 0.0D && Double.isFinite(length) ? (float) length : 1.0F;
    }

    private MuzzleFlashQuad() {
    }

    /**
     * Builds the corners of one flash quad.
     *
     * <p>The corners are returned in primitive order - bottom-left, bottom-right, top-right, top-left - so the four vertices
     * form a convex quad whose two triangles do not cross. The quad is centred exactly on the pivot, so a creator only has to
     * place the bone at the muzzle.</p>
     *
     * <p>The vertical texture coordinate is mapped so the sprite reads the right way up: {@code v = 0} is the top row of the
     * image and therefore belongs to the quad's upper edge, because the bone's own {@code +Y} is up in model space.</p>
     *
     * @param pivot         the attachment bone's pivot in model space, may be {@code null} (treated as the origin)
     * @param sizeBlocks    the authored edge length in blocks; clamped to a non-negative value
     * @param blocksPerUnit how many blocks one model unit covers, i.e. the uniform scale of the accumulated pose; a
     *                      non-finite, zero or negative value means "one block per unit"
     * @param frame         the sprite-sheet frame to sample, never {@code null}
     * @return four corners, in primitive order
     */
    public static Corner[] corners(Vec3 pivot, double sizeBlocks, float blocksPerUnit,
                                   MuzzleFlashSpriteSheet.Frame frame) {
        Vec3 centre = pivot == null ? Vec3.ZERO : pivot;
        float scale = Float.isFinite(blocksPerUnit) && blocksPerUnit > 0.0F ? blocksPerUnit : 1.0F;
        double size = Double.isFinite(sizeBlocks) ? Math.max(0.0D, sizeBlocks) : 0.0D;
        float half = (float) (0.5D * size / scale);

        float left = centre.x() - half;
        float right = centre.x() + half;
        float bottom = centre.y() - half;
        float top = centre.y() + half;
        float z = centre.z();
        // v = 0 is the top of the image, so the quad's upper corners take it.
        float topV = frame.v0();
        float bottomV = frame.v1();

        return new Corner[] {
                new Corner(left, bottom, z, frame.u0(), bottomV),
                new Corner(right, bottom, z, frame.u1(), bottomV),
                new Corner(right, top, z, frame.u1(), topV),
                new Corner(left, top, z, frame.u0(), topV)
        };
    }
}
