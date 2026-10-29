package com.arg_studios.arg_weaponry.weapon.model;

import java.util.Arrays;

/**
 * The renderable geometry of one bone, already converted into triangle corners.
 *
 * <p>Vertex positions are kept in <em>model space</em>, exactly as authored: Bedrock geometry stores
 * cube origins and polymesh positions in model space, and a bone rotates its geometry around its
 * pivot. Keeping the data in model space means a future pose can move a bone without touching or
 * re-baking the mesh.</p>
 *
 * <p>Every corner stores its own position, normal and UV, so independently indexed Bedrock data is
 * resolved once at parse time and the renderer can simply walk triangles. Quads are split into two
 * triangles, and winding is corrected against the face normal so back-face culling works even when a
 * generator exported inconsistent winding.</p>
 *
 * <p>Corners are never shared between polygons: a position may appear in several polygons (a shared
 * edge, a shared vertex) and each occurrence keeps its own normal and UV. That is what preserves UV
 * seams, and it is also what lets every polygon carry its own flat-shaded face normal.</p>
 *
 * <p>Instances are immutable and may be shared between renderers.</p>
 */
public final class BoneMesh {

    /** Where a mesh came from, so callers can treat cube and polymesh geometry differently later. */
    public enum Source {
        /** A {@code poly_mesh} block. */
        POLY_MESH,
        /** A {@code cubes} array. */
        CUBE
    }

    private final float[] positions;
    private final float[] normals;
    private final float[] uvs;
    private final int[] indices;
    private final int[] quadCorners;
    private final int polygonCount;
    private final Source source;

    /**
     * @param positions     {@code x, y, z} per corner, model space
     * @param normals       {@code x, y, z} per corner (unit length)
     * @param uvs           {@code u, v} per corner, normalised to {@code 0..1}
     * @param indices       three corner indices per triangle
     * @param polygonCount  how many source polygons produced this mesh, for diagnostics
     * @param source        where the mesh came from
     */
    public BoneMesh(float[] positions, float[] normals, float[] uvs, int[] indices, int polygonCount,
                    Source source) {
        if (positions.length != normals.length) {
            throw new IllegalArgumentException("Position and normal arrays must have the same length");
        }
        if (positions.length / 3 * 2 != uvs.length) {
            throw new IllegalArgumentException("UV array does not match the corner count");
        }
        if (indices.length % 3 != 0) {
            throw new IllegalArgumentException("Index array is not a multiple of three");
        }
        this.positions = Arrays.copyOf(positions, positions.length);
        this.normals = Arrays.copyOf(normals, normals.length);
        this.uvs = Arrays.copyOf(uvs, uvs.length);
        this.indices = Arrays.copyOf(indices, indices.length);
        this.quadCorners = buildQuadCorners(this.indices);
        this.polygonCount = polygonCount;
        this.source = source;
    }

    /**
     * Expands a triangle list into the four corners per primitive a quad pipeline expects.
     *
     * <p>Triangle {@code (a, b, c)} becomes {@code (a, b, c, c)}: a quad whose second triangle is
     * degenerate and therefore rasterises nothing. The mesh data stays a triangle list because that is
     * the representation the parser, the winding correction and the headless tests speak; only the
     * submission step needs quads.</p>
     *
     * @param triangleIndices three corner indices per triangle
     * @return four corner indices per primitive
     */
    private static int[] buildQuadCorners(int[] triangleIndices) {
        int[] quads = new int[triangleIndices.length / 3 * 4];
        for (int index = 0, target = 0; index < triangleIndices.length; index += 3, target += 4) {
            quads[target] = triangleIndices[index];
            quads[target + 1] = triangleIndices[index + 1];
            quads[target + 2] = triangleIndices[index + 2];
            quads[target + 3] = triangleIndices[index + 2];
        }
        return quads;
    }

    /** @return where the mesh came from. */
    public Source source() {
        return source;
    }


    /** @return the number of stored corners. */
    public int cornerCount() {
        return positions.length / 3;
    }

    /** @return the number of triangles. */
    public int triangleCount() {
        return indices.length / 3;
    }

    /** @return how many source polygons produced this mesh. */
    public int polygonCount() {
        return polygonCount;
    }

    public float[] positions() {
        return Arrays.copyOf(positions, positions.length);
    }

    public float[] normals() {
        return Arrays.copyOf(normals, normals.length);
    }

    public float[] uvs() {
        return Arrays.copyOf(uvs, uvs.length);
    }

    public int[] indices() {
        return Arrays.copyOf(indices, indices.length);
    }

    /** Read-only access for the renderer, to avoid copying the arrays every frame. */
    public float[] positionsUnsafe() {
        return positions;
    }

    public float[] normalsUnsafe() {
        return normals;
    }

    public float[] uvsUnsafe() {
        return uvs;
    }

    public int[] indicesUnsafe() {
        return indices;
    }

    /**
     * Read-only view of the corner indices arranged as quads, for a render pipeline whose primitive
     * topology is {@code QUADS}.
     *
     * <p>A quad topology consumes four vertices per primitive and builds its index buffer from the
     * vertex count alone, so a stream of three-vertex triangles is reinterpreted as quads and the model
     * comes out scrambled. This array is the same geometry expressed as {@link #triangleCount()}
     * primitives of four corners each.</p>
     *
     * @return four corner indices per primitive; do not modify
     */
    public int[] quadCornerIndicesUnsafe() {
        return quadCorners;
    }
}
