package com.arg_studios.arg_weaponry.weapon.model;

import com.arg_studios.arg_weaponry.json.JsonValues;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * The {@code poly_mesh} geometry layer: one mesh object in, one {@link BoneMesh} out.
 *
 * <p>Independent of {@link BedrockCubeGeometry} and of the cube path in both directions: a poly mesh is never turned into
 * cubes, and cube geometry is never routed through this class. What the two layers share is the {@link BoneMesh} they
 * produce and the {@link MeshGeometryBuilder} that winds triangles - nothing else.</p>
 *
 * <p>A mesh carries its own scene: {@code positions}, optional {@code normals}, optional {@code uvs} (pixels, or
 * {@code 0..1} when {@code normalized_uvs} is set) and {@code polys}, each polygon being a list of
 * {@code [position, normal, uv]} index triples. The output is a triangle fan per polygon, flat-shaded with one normal
 * per polygon:</p>
 * <ul>
 *   <li>{@link #POLYMESH_FLAT_SHADING} - a polygon is shaded with its own geometric normal, oriented by the normalised
 *   average of the authored corner normals so that an exporter with inconsistent winding still faces the right way;</li>
 *   <li>{@link #POLYMESH_V_ORIGIN_IS_BOTTOM} - poly mesh UVs use a bottom-left V origin and are flipped once here, while
 *   cube UVs are already top-left and are not touched.</li>
 * </ul>
 */
final class PolyMeshGeometry {

    /**
     * Bedrock polymesh UVs use a <em>bottom-left</em> V origin, while Minecraft samples textures from
     * the top-left. The two conventions differ by {@code v -> 1 - v}.
     *
     * <p>This was established with a controlled experiment on the M100 DMR instead of assumed: with the
     * flip, the centroid of every one of its 4501 polygons samples a fully opaque texel (mean alpha
     * 255, mean colour 142/117/94); without it only 26% do (mean alpha 66, mean colour 36/30/24, i.e.
     * the black padding of a UV atlas that is 79% transparent). Cube geometry keeps its own, top-left
     * convention and is therefore not flipped.</p>
     */
    private static final boolean POLYMESH_V_ORIGIN_IS_BOTTOM = true;

    /**
     * Whether {@code poly_mesh} polygons are shaded with their own geometric face normal.
     *
     * <p>See the class documentation for the reasoning. Kept as a named constant so the policy is
     * greppable and can be switched without hunting through the parser. When it is {@code false} a
     * polygon is shaded with the normalised average of its authored corner normals — one direction for
     * the whole polygon, not the per-corner directions the exporter wrote, because the corner normals are
     * consumed for orientation either way.</p>
     */
    static final boolean POLYMESH_FLAT_SHADING = true;

    /** Used when neither the geometry nor the authored data can define a direction. */
    private static final Vec3 FALLBACK_NORMAL = new Vec3(0.0F, 1.0F, 0.0F);

    private final GeometryJson json;
    private final GeometryDiagnostics diagnostics;
    private final int textureWidth;
    private final int textureHeight;
    private int polygonCount;
    private int degenerateTriangles;

    /**
     * @param json          the shared JSON reader of this parse
     * @param diagnostics   where problems are reported
     * @param textureWidth  the declared texture width, used to normalise pixel UVs
     * @param textureHeight the declared texture height, used to normalise pixel UVs
     */
    PolyMeshGeometry(GeometryJson json, GeometryDiagnostics diagnostics, int textureWidth, int textureHeight) {
        this.json = json;
        this.diagnostics = diagnostics;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
    }

    GeometryBlock read(JsonObject mesh, String pointer, String boneName) {
        JsonArray positionsArray = json.array(mesh.get("positions"), pointer + ".positions");
        JsonArray polysArray = json.array(mesh.get("polys"), pointer + ".polys");
        if (positionsArray == null || polysArray == null) {
            diagnostics.error(pointer, "The polymesh of bone '" + boneName
                    + "' is missing 'positions' or 'polys' and was skipped");
            return null;
        }

        List<Vec3> positions = json.vectorList(positionsArray, pointer + ".positions");
        if (positions.isEmpty()) {
            diagnostics.error(pointer + ".positions", "The polymesh of bone '" + boneName
                    + "' declares no usable positions and was skipped");
            return null;
        }

        JsonArray normalsArray = json.array(mesh.get("normals"), pointer + ".normals");
        JsonArray uvsArray = json.array(mesh.get("uvs"), pointer + ".uvs");
        List<Vec3> normals = normalsArray == null ? List.of() : json.vectorList(normalsArray, pointer + ".normals");
        List<float[]> uvs = uvsArray == null ? List.of() : json.uvList(uvsArray, pointer + ".uvs");

        boolean normalizedUvs = JsonValues.isBoolean(mesh.get("normalized_uvs"))
                && mesh.get("normalized_uvs").getAsBoolean();
        if (!mesh.has("normalized_uvs")) {
            diagnostics.info(pointer + ".normalized_uvs", "The polymesh of bone '" + boneName
                    + "' declares no 'normalized_uvs'; its UVs are treated as texture pixels and divided by the "
                    + "declared texture size " + textureWidth + "x" + textureHeight);
        }
        if (normals.isEmpty()) {
            diagnostics.warning(pointer + ".normals", "The polymesh of bone '" + boneName
                    + "' declares no normals; flat shading derives the face normals from the polygon geometry, "
                    + "so each face is oriented by its winding alone");
        }
        if (uvs.isEmpty()) {
            diagnostics.warning(pointer + ".uvs", "The polymesh of bone '" + boneName
                    + "' declares no UVs; its geometry samples the top-left texel");
        }

        for (String key : mesh.keySet()) {
            if (!key.equals("positions") && !key.equals("normals") && !key.equals("uvs") && !key.equals("polys")
                    && !key.equals("normalized_uvs")) {
                diagnostics.unsupported(pointer + "." + key, "polymesh property '" + key + "' on bone '" + boneName
                        + "' is not interpreted");
            }
        }

        MeshGeometryBuilder builder = new MeshGeometryBuilder();
        int acceptedPolygons = 0;
        for (int polyIndex = 0; polyIndex < polysArray.size(); polyIndex++) {
            String polyPointer = pointer + ".polys[" + polyIndex + "]";
            JsonElement polyElement = polysArray.get(polyIndex);
            if (!(polyElement instanceof JsonArray poly)) {
                diagnostics.error(polyPointer, "Expected an array of vertices but found "
                        + JsonValues.describeType(polyElement) + "; the polygon was skipped");
                continue;
            }
            if (poly.size() < 3) {
                diagnostics.error(polyPointer, "A polygon needs at least three vertices but has "
                        + poly.size() + "; the polygon was skipped");
                continue;
            }

            int[] corners = new int[poly.size()];
            boolean valid = true;
            for (int cornerIndex = 0; cornerIndex < poly.size() && valid; cornerIndex++) {
                valid = readPolyCorner(poly, cornerIndex, polyPointer, positions, normals, uvs, normalizedUvs,
                        corners, builder);
            }
            if (!valid) {
                continue;
            }

            Vec3 faceNormal = shadeNormal(corners, builder);
            builder.setNormals(corners, faceNormal);
            builder.addPolygon(corners, faceNormal);
            acceptedPolygons++;
        }

        degenerateTriangles += builder.degenerateTriangles();
        polygonCount += acceptedPolygons;
        if (builder.triangleCount() == 0) {
            diagnostics.error(pointer, "The polymesh of bone '" + boneName
                    + "' produced no renderable triangles");
            return null;
        }
        return new GeometryBlock(builder.build(acceptedPolygons, BoneMesh.Source.POLY_MESH), 0,
                builder.degenerateTriangles());
    }


    private Vec3 shadeNormal(int[] corners, MeshGeometryBuilder builder) {
        Vec3 supplied = builder.averageNormal(corners);
        Vec3 geometric = POLYMESH_FLAT_SHADING ? geometricFaceNormal(corners, builder) : null;
        if (geometric == null) {
            return supplied.isZero() ? FALLBACK_NORMAL : supplied;
        }
        if (supplied.isZero()) {
            return geometric;
        }
        float agreement = geometric.x() * supplied.x() + geometric.y() * supplied.y()
                + geometric.z() * supplied.z();
        return agreement < 0.0F ? new Vec3(-geometric.x(), -geometric.y(), -geometric.z()) : geometric;
    }

    /**
     * Computes the polygon's face normal with Newell's method.
     *
     * @param corners the polygon's corner indices, in authored order
     * @param builder the builder holding those corners
     * @return a unit-length face normal, or {@code null} when the polygon has no area
     */
    private static Vec3 geometricFaceNormal(int[] corners, MeshGeometryBuilder builder) {
        double nx = 0.0;
        double ny = 0.0;
        double nz = 0.0;
        for (int index = 0; index < corners.length; index++) {
            Vec3 current = builder.positionAt(corners[index]);
            Vec3 next = builder.positionAt(corners[(index + 1) % corners.length]);
            nx += (double) (current.y() - next.y()) * (current.z() + next.z());
            ny += (double) (current.z() - next.z()) * (current.x() + next.x());
            nz += (double) (current.x() - next.x()) * (current.y() + next.y());
        }
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(length > MeshGeometryBuilder.NORMAL_EPSILON)) {
            return null;
        }
        return new Vec3((float) (nx / length), (float) (ny / length), (float) (nz / length));
    }

    /**
     * Resolves one polygon corner into a mesh corner.
     *
     * @return {@code false} when the corner is malformed (a diagnostic has already been reported)
     */
    private boolean readPolyCorner(JsonArray poly, int cornerIndex, String polyPointer, List<Vec3> positions,
                                   List<Vec3> normals, List<float[]> uvs, boolean normalizedUvs, int[] corners,
                                   MeshGeometryBuilder builder) {
        String cornerPointer = polyPointer + "[" + cornerIndex + "]";
        JsonElement cornerElement = poly.get(cornerIndex);
        if (!(cornerElement instanceof JsonArray corner) || corner.size() < 3) {
            diagnostics.error(cornerPointer, "Expected [position, normal, uv] indices but found "
                    + JsonValues.describeType(cornerElement) + "; the polygon was skipped");
            return false;
        }

        Integer positionIndex = JsonValues.asInt(corner.get(0));
        Integer normalIndex = JsonValues.asInt(corner.get(1));
        Integer uvIndex = JsonValues.asInt(corner.get(2));

        String problem = validateIndex(positionIndex, positions.size(), "position", cornerPointer);
        if (problem == null && normalIndex != null && !normals.isEmpty()) {
            problem = validateIndex(normalIndex, normals.size(), "normal", cornerPointer);
        }
        if (problem == null && uvIndex != null && !uvs.isEmpty()) {
            problem = validateIndex(uvIndex, uvs.size(), "UV", cornerPointer);
        }
        if (problem != null) {
            diagnostics.error(cornerPointer, problem + "; the polygon was skipped");
            return false;
        }

        Vec3 position = positions.get(positionIndex);
        Vec3 normal = normalIndex != null && normalIndex >= 0 && normalIndex < normals.size()
                ? normals.get(normalIndex) : new Vec3(0.0F, 1.0F, 0.0F);
        float[] uv = uvIndex != null && uvIndex >= 0 && uvIndex < uvs.size() ? uvs.get(uvIndex) : new float[]{0.0F, 0.0F};
        float u = normalizedUvs ? uv[0] : uv[0] / textureWidth;
        float v = normalizedUvs ? uv[1] : uv[1] / textureHeight;
        if (POLYMESH_V_ORIGIN_IS_BOTTOM) {
            v = 1.0F - v;
        }

        corners[cornerIndex] = builder.addCorner(position, normal, u, v);
        return true;
    }

    /** @return a description of why an index is unusable, or {@code null} when it is valid. */
    private static String validateIndex(Integer index, int size, String kind, String pointer) {
        if (index == null) {
            return "The " + kind + " index at " + pointer + " is not an integer";
        }
        if (index < 0 || index >= size) {
            return "The " + kind + " index " + index + " is outside the valid range 0.." + (size - 1);
        }
        return null;
    }
}
