package com.arg_studios.arg_weaponry.client.display;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Turns one authored {@link DisplayTransform} into the matrix a static item render applies.
 *
 * <h2>The conventions, and why they are here</h2>
 * <p>A display transform is authored in vanilla's item-model units and order:</p>
 * <ul>
 *   <li><strong>translation</strong> is in <em>1/16 block</em>, so it is divided by {@link #UNITS_PER_BLOCK} before it becomes a
 *   block-space offset;</li>
 *   <li><strong>rotation</strong> is in degrees and is composed <em>Z, then Y, then X</em>, which is the order
 *   {@link DisplayTransform} documents and the order the weapon geometry itself uses
 *   ({@code BoneTransformPolicy}), so an authored value means the same thing in both places;</li>
 *   <li><strong>scale</strong> is a plain multiplier applied after the rotation.</li>
 * </ul>
 *
 * <p>The result is {@code T * Rz * Ry * Rx * S}, i.e. the model is scaled about its own origin, rotated, then moved. This class
 * is deliberately free of Minecraft types apart from {@link Matrix4f}, so the whole mapping is unit tested against the numbers
 * a content pack really contains.</p>
 */
public final class DisplayTransformMatrix {

    /** Item-model units per block, i.e. the unit a display translation is authored in. */
    public static final float UNITS_PER_BLOCK = 16.0F;

    private DisplayTransformMatrix() {
    }

    /**
     * @param transform the authored transform, may be {@code null}
     * @return the matrix for that transform; the identity for {@code null}
     */
    public static Matrix4f of(DisplayTransform transform) {
        return of(transform, new Matrix4f());
    }

    /**
     * Writes one transform into a matrix, so a caller can reuse it while rendering.
     *
     * @param transform   the authored transform, may be {@code null}
     * @param destination the matrix to overwrite
     * @return {@code destination}
     */
    public static Matrix4f of(DisplayTransform transform, Matrix4f destination) {
        destination.identity();
        if (transform == null) {
            return destination;
        }
        destination.translate(transform.translation().x() / UNITS_PER_BLOCK,
                transform.translation().y() / UNITS_PER_BLOCK,
                transform.translation().z() / UNITS_PER_BLOCK);
        destination.rotateZ((float) Math.toRadians(transform.rotation().z()));
        destination.rotateY((float) Math.toRadians(transform.rotation().y()));
        destination.rotateX((float) Math.toRadians(transform.rotation().x()));
        destination.scale(transform.scale().x(), transform.scale().y(), transform.scale().z());
        return destination;
    }

    /**
     * @param transform the authored transform, may be {@code null}
     * @return {@code true} when the transform cannot change anything
     */
    public static boolean isIdentity(DisplayTransform transform) {
        return transform == null || transform.isIdentity();
    }

    /**
     * @param matrix a matrix produced by this class
     * @return {@code true} when every scale component is zero, so nothing would be drawn
     */
    public static boolean isCollapsed(Matrix4fc matrix) {
        if (matrix == null) {
            return true;
        }
        Matrix4f copy = new Matrix4f(matrix);
        // A zero scale is how a content pack hides an item in a context it does not want to appear in ("head": scale 0). It has
        // to be detected explicitly, because drawing it would still submit geometry that covers nothing.
        float x = copy.m00() * copy.m00() + copy.m10() * copy.m10() + copy.m20() * copy.m20();
        float y = copy.m01() * copy.m01() + copy.m11() * copy.m11() + copy.m21() * copy.m21();
        float z = copy.m02() * copy.m02() + copy.m12() * copy.m12() + copy.m22() * copy.m22();
        return x <= 1.0E-6F || y <= 1.0E-6F || z <= 1.0E-6F;
    }
}
