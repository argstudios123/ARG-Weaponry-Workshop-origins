package com.arg_studios.arg_weaponry.weapon.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The corner sink both geometry layers build their triangles in.
 *
 * <p>Cube geometry and {@code poly_mesh} geometry resolve very different inputs - six faces of a box with per-face UV
 * rectangles on one side, an indexed position/normal/UV soup with n-gons on the other - but both end up as a triangle
 * list that has to be wound consistently against the normal it is shaded with, with degenerate triangles counted rather
 * than emitted as garbage. That last step is identical for both, so it lives here instead of in either geometry class:
 * {@link BedrockCubeGeometry} calls {@link #addQuad}, {@link PolyMeshGeometry} calls {@link #addPolygon}, and neither
 * depends on the other.</p>
 *
 * <p>Corners are never shared between polygons: a position may appear in several polygons (a shared edge, a shared
 * vertex) and each occurrence keeps its own normal and UV. That is what preserves UV seams, and it is also what lets
 * every polygon carry its own flat-shaded face normal.</p>
 */
final class MeshGeometryBuilder {

    /**
     * Normals shorter than this are degenerate, not normalised into noise.
     */
    static final float NORMAL_EPSILON = 1.0E-6F;

    private final List<Float> positions = new ArrayList<>();
    private final List<Float> normals = new ArrayList<>();
    private final List<Float> uvs = new ArrayList<>();
    private final List<Integer> indices = new ArrayList<>();
    private int degenerateTriangles;

    int addCorner(Vec3 position, Vec3 normal, float u, float v) {
        int index = positions.size() / 3;
        positions.add(position.x());
        positions.add(position.y());
        positions.add(position.z());
        normals.add(normal.x());
        normals.add(normal.y());
        normals.add(normal.z());
        uvs.add(u);
        uvs.add(v);
        return index;
    }

    /**
     * @return the position of an already added corner.
     */
    Vec3 positionAt(int corner) {
        return new Vec3(positions.get(corner * 3), positions.get(corner * 3 + 1), positions.get(corner * 3 + 2));
    }

    /**
     * Averages the authored normals of the given corners.
     *
     * @param corners the corners to average
     * @return the normalised average, or {@link Vec3#ZERO} when no normal was authored or the
     * authored normals cancel each other out
     */
    Vec3 averageNormal(int[] corners) {
        float x = 0.0F;
        float y = 0.0F;
        float z = 0.0F;
        for (int corner : corners) {
            x += normals.get(corner * 3);
            y += normals.get(corner * 3 + 1);
            z += normals.get(corner * 3 + 2);
        }
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (!(length > NORMAL_EPSILON)) {
            return Vec3.ZERO;
        }
        return new Vec3(x / length, y / length, z / length);
    }

    /**
     * Replaces the authored normals of the given corners with one shading normal.
     */
    void setNormals(int[] corners, Vec3 normal) {
        for (int corner : corners) {
            normals.set(corner * 3, normal.x());
            normals.set(corner * 3 + 1, normal.y());
            normals.set(corner * 3 + 2, normal.z());
        }
    }

    void addQuad(int a, int b, int c, int d, Vec3 faceNormal) {
        addTriangle(a, b, c, faceNormal);
        addTriangle(a, c, d, faceNormal);
    }

    void addPolygon(int[] corners, Vec3 faceNormal) {
        for (int i = 1; i < corners.length - 1; i++) {
            addTriangle(corners[0], corners[i], corners[i + 1], faceNormal);
        }
    }

    /**
     * Emits one triangle, winding it so that its geometric normal agrees with the face normal.
     *
     * <p>The reference normal is the normal the corners are shaded with, so the emitted winding and
     * the emitted normal always describe the same side of the surface; that is what keeps back-face
     * culling and lighting consistent. A triangle without area is emitted unchanged (it rasterises
     * nothing) and counted, because its geometry cannot say which way it faces.</p>
     */
    private void addTriangle(int a, int b, int c, Vec3 faceNormal) {
        float ax = positions.get(a * 3);
        float ay = positions.get(a * 3 + 1);
        float az = positions.get(a * 3 + 2);
        float bx = positions.get(b * 3) - ax;
        float by = positions.get(b * 3 + 1) - ay;
        float bz = positions.get(b * 3 + 2) - az;
        float cx = positions.get(c * 3) - ax;
        float cy = positions.get(c * 3 + 1) - ay;
        float cz = positions.get(c * 3 + 2) - az;

        float nx = by * cz - bz * cy;
        float ny = bz * cx - bx * cz;
        float nz = bx * cy - by * cx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < NORMAL_EPSILON) {
            degenerateTriangles++;
            indices.add(a);
            indices.add(b);
            indices.add(c);
            return;
        }

        boolean flipped = (nx * faceNormal.x() + ny * faceNormal.y() + nz * faceNormal.z()) < 0.0F;

        indices.add(a);
        indices.add(flipped ? c : b);
        indices.add(flipped ? b : c);
    }

    int triangleCount() {
        return indices.size() / 3;
    }

    BoneMesh build(int polygonCount, BoneMesh.Source source) {
        float[] positionArray = new float[positions.size()];
        float[] normalArray = new float[normals.size()];
        float[] uvArray = new float[uvs.size()];
        int[] indexArray = new int[indices.size()];
        for (int i = 0; i < positionArray.length; i++) {
            positionArray[i] = positions.get(i);
        }
        for (int i = 0; i < normalArray.length; i++) {
            normalArray[i] = normals.get(i);
        }
        for (int i = 0; i < uvArray.length; i++) {
            uvArray[i] = uvs.get(i);
        }
        for (int i = 0; i < indexArray.length; i++) {
            indexArray[i] = indices.get(i);
        }
        return new BoneMesh(positionArray, normalArray, uvArray, indexArray, polygonCount, source);
    }

    /**
     * @return how many emitted triangles had no area.
     */
    int degenerateTriangles() {
        return degenerateTriangles;
    }
}
