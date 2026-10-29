package com.arg_studios.arg_weaponry.weapon.model;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsonValues;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public final class BedrockGeometryParser {

    /** The geometry-array key used by the format. */
    public static final String GEOMETRY_KEY = "minecraft:geometry";

    private static final List<String> FACE_NAMES = List.of("north", "east", "south", "west", "up", "down");

    private final String packId;
    private final ResourceId resourceId;
    private final String sourceName;
    private final ValidationReport report;

    private final Map<String, BoneDraft> drafts = new LinkedHashMap<>();
    private final Set<String> reportedPointers = new LinkedHashSet<>();
    private int polygonCount;
    private int cubeCount;
    private int degenerateTriangles;
    private String identifier = "";
    private String formatVersion = "";
    private int textureWidth = 16;
    private int textureHeight = 16;

    private BedrockGeometryParser(String packId, ResourceId resourceId, String sourceName, ValidationReport report) {
        this.packId = packId;
        this.resourceId = resourceId;
        this.sourceName = sourceName;
        this.report = report;
    }

    /**
     * Parses a Bedrock geometry document.
     *
     * @param packId     the owning content pack, for diagnostics
     * @param resourceId the resource the document was read from, for diagnostics
     * @param sourceName the pack-relative file name, for diagnostics
     * @param root       the parsed document root
     * @param report     the report to collect diagnostics in
     * @return the parsed model, or {@code null} when the document contains no usable geometry
     */
    public static WeaponModel parse(String packId, ResourceId resourceId, String sourceName, JsonObject root,
                                    ValidationReport report) {
        return new BedrockGeometryParser(packId, resourceId, sourceName, report).run(root);
    }

    // --- top level ---

    private WeaponModel run(JsonObject root) {
        if (JsonValues.isString(root.get("format_version"))) {
            formatVersion = root.get("format_version").getAsString();
        } else {
            report.warning(packId, sourceName, "$.format_version",
                    "The model declares no 'format_version' string; its structure is parsed as written");
        }

        JsonElement geometriesElement = root.get(GEOMETRY_KEY);
        if (!(geometriesElement instanceof JsonArray geometries)) {
            report.error(packId, sourceName, "$." + GEOMETRY_KEY, "Expected an array of geometry objects but found "
                    + JsonValues.describeType(geometriesElement) + "; the model cannot be used");
            return null;
        }

        for (int index = 0; index < geometries.size(); index++) {
            JsonElement geometryElement = geometries.get(index);
            String pointer = "$." + GEOMETRY_KEY + "[" + index + "]";
            if (!(geometryElement instanceof JsonObject geometry)) {
                report.error(packId, sourceName, pointer, "Expected a geometry object but found "
                        + JsonValues.describeType(geometryElement) + "; the entry was skipped");
                continue;
            }
            readDescription(geometry, pointer);
            readBones(geometry, pointer);
        }

        return build();
    }

    private void readDescription(JsonObject geometry, String pointer) {
        JsonElement descriptionElement = geometry.get("description");
        if (!(descriptionElement instanceof JsonObject description)) {
            report.warning(packId, sourceName, pointer + ".description", "The geometry declares no description, so "
                    + "no texture size is known; 16x16 is assumed for pixel-based UVs");
            return;
        }

        if (JsonValues.isString(description.get("identifier"))) {
            identifier = description.get("identifier").getAsString();
        }

        Integer width = JsonValues.asInt(description.get("texture_width"));
        Integer height = JsonValues.asInt(description.get("texture_height"));
        if (width != null && height != null && width > 0 && height > 0) {
            textureWidth = width;
            textureHeight = height;
        } else {
            report.warning(packId, sourceName, pointer + ".description", "'texture_width'/'texture_height' are "
                    + "missing or not positive integers; 16x16 is assumed for pixel-based UVs");
        }

        for (String key : description.keySet()) {
            if (!key.equals("identifier") && !key.equals("texture_width") && !key.equals("texture_height")
                    && !key.startsWith("visible_bounds")) {
                reportUnsupported(pointer + ".description." + key, "description property '" + key
                        + "' is not interpreted (it does not affect geometry)");
            }
        }
    }

    // --- bones ---

    private void readBones(JsonObject geometry, String geometryPointer) {
        JsonElement bonesElement = geometry.get("bones");
        if (!(bonesElement instanceof JsonArray bones)) {
            report.error(packId, sourceName, geometryPointer + ".bones",
                    "Expected an array of bones but found " + JsonValues.describeType(bonesElement));
            return;
        }

        for (int index = 0; index < bones.size(); index++) {
            JsonElement boneElement = bones.get(index);
            String pointer = geometryPointer + ".bones[" + index + "]";
            if (!(boneElement instanceof JsonObject bone)) {
                report.error(packId, sourceName, pointer, "Expected a bone object but found "
                        + JsonValues.describeType(boneElement) + "; the entry was skipped");
                continue;
            }
            readBone(bone, pointer);
        }
    }

    private void readBone(JsonObject bone, String pointer) {
        JsonElement nameElement = bone.get("name");
        if (!JsonValues.isString(nameElement) || nameElement.getAsString().isBlank()) {
            report.error(packId, sourceName, pointer + ".name",
                    "The bone has no usable name; a bone without a name cannot be addressed and was skipped");
            return;
        }
        String name = nameElement.getAsString();

        if (drafts.containsKey(name)) {
            report.error(packId, sourceName, pointer + ".name", "Duplicate bone name '" + name
                    + "'; the duplicate was skipped and the first declaration is kept");
            return;
        }

        String parentName = null;
        if (bone.has("parent")) {
            if (JsonValues.isString(bone.get("parent"))) {
                parentName = bone.get("parent").getAsString();
            } else {
                report.error(packId, sourceName, pointer + ".parent", "Expected a bone name string but found "
                        + JsonValues.describeType(bone.get("parent")) + "; the bone is treated as a root");
            }
        }

        Vec3 pivot = readVector(bone.get("pivot"), pointer + ".pivot", Vec3.ZERO, true);
        Vec3 rotation = readVector(bone.get("rotation"), pointer + ".rotation", Vec3.ZERO, false);

        for (String key : bone.keySet()) {
            switch (key) {
                case "name", "parent", "pivot", "rotation", "poly_mesh", "cubes" -> {
                    // Interpreted below.
                }
                case "locators" -> reportUnsupported(pointer + ".locators", "locators on bone '" + name
                        + "' are not exposed as attachment points yet");
                default -> reportUnsupported(pointer + "." + key, "bone property '" + key + "' on bone '" + name
                        + "' is not interpreted");
            }
        }

        List<BoneMesh> meshes = new ArrayList<>();

        JsonElement polyMeshElement = bone.get("poly_mesh");
        if (polyMeshElement != null) {
            if (polyMeshElement instanceof JsonObject polyMesh) {
                BoneMesh mesh = readPolyMesh(polyMesh, pointer + ".poly_mesh", name);
                if (mesh != null) {
                    meshes.add(mesh);
                }
            } else {
                report.error(packId, sourceName, pointer + ".poly_mesh", "Expected an object but found "
                        + JsonValues.describeType(polyMeshElement) + "; the polymesh of bone '" + name
                        + "' was skipped");
            }
        }

        JsonElement cubesElement = bone.get("cubes");
        if (cubesElement != null) {
            if (cubesElement instanceof JsonArray cubes) {
                BoneMesh mesh = readCubes(cubes, pointer + ".cubes", name);
                if (mesh != null) {
                    meshes.add(mesh);
                }
            } else {
                report.error(packId, sourceName, pointer + ".cubes", "Expected an array but found "
                        + JsonValues.describeType(cubesElement) + "; the cubes of bone '" + name + "' were skipped");
            }
        }

        drafts.put(name, new BoneDraft(name, parentName, pivot, rotation, meshes));
    }

    // --- polymesh geometry ---

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
    public static final boolean POLYMESH_FLAT_SHADING = true;

    /** Normals shorter than this are degenerate, not normalised into noise. */
    private static final float NORMAL_EPSILON = 1.0E-6F;

    /** Used when neither the geometry nor the authored data can define a direction. */
    private static final Vec3 FALLBACK_NORMAL = new Vec3(0.0F, 1.0F, 0.0F);

    private BoneMesh readPolyMesh(JsonObject mesh, String pointer, String boneName) {
        JsonArray positionsArray = readArray(mesh.get("positions"), pointer + ".positions");
        JsonArray polysArray = readArray(mesh.get("polys"), pointer + ".polys");
        if (positionsArray == null || polysArray == null) {
            report.error(packId, sourceName, pointer, "The polymesh of bone '" + boneName
                    + "' is missing 'positions' or 'polys' and was skipped");
            return null;
        }

        List<Vec3> positions = readVectorList(positionsArray, pointer + ".positions");
        if (positions.isEmpty()) {
            report.error(packId, sourceName, pointer + ".positions", "The polymesh of bone '" + boneName
                    + "' declares no usable positions and was skipped");
            return null;
        }

        JsonArray normalsArray = readArray(mesh.get("normals"), pointer + ".normals");
        JsonArray uvsArray = readArray(mesh.get("uvs"), pointer + ".uvs");
        List<Vec3> normals = normalsArray == null ? List.of() : readVectorList(normalsArray, pointer + ".normals");
        List<float[]> uvs = uvsArray == null ? List.of() : readUvList(uvsArray, pointer + ".uvs");

        boolean normalizedUvs = JsonValues.isBoolean(mesh.get("normalized_uvs"))
                && mesh.get("normalized_uvs").getAsBoolean();
        if (!mesh.has("normalized_uvs")) {
            report.info(packId, sourceName, pointer + ".normalized_uvs", "The polymesh of bone '" + boneName
                    + "' declares no 'normalized_uvs'; its UVs are treated as texture pixels and divided by the "
                    + "declared texture size " + textureWidth + "x" + textureHeight);
        }
        if (normals.isEmpty()) {
            report.warning(packId, sourceName, pointer + ".normals", "The polymesh of bone '" + boneName
                    + "' declares no normals; flat shading derives the face normals from the polygon geometry, "
                    + "so each face is oriented by its winding alone");
        }
        if (uvs.isEmpty()) {
            report.warning(packId, sourceName, pointer + ".uvs", "The polymesh of bone '" + boneName
                    + "' declares no UVs; its geometry samples the top-left texel");
        }

        for (String key : mesh.keySet()) {
            if (!key.equals("positions") && !key.equals("normals") && !key.equals("uvs") && !key.equals("polys")
                    && !key.equals("normalized_uvs")) {
                reportUnsupported(pointer + "." + key, "polymesh property '" + key + "' on bone '" + boneName
                        + "' is not interpreted");
            }
        }

        MeshBuilder builder = new MeshBuilder();
        int acceptedPolygons = 0;
        for (int polyIndex = 0; polyIndex < polysArray.size(); polyIndex++) {
            String polyPointer = pointer + ".polys[" + polyIndex + "]";
            JsonElement polyElement = polysArray.get(polyIndex);
            if (!(polyElement instanceof JsonArray poly)) {
                report.error(packId, sourceName, polyPointer, "Expected an array of vertices but found "
                        + JsonValues.describeType(polyElement) + "; the polygon was skipped");
                continue;
            }
            if (poly.size() < 3) {
                report.error(packId, sourceName, polyPointer, "A polygon needs at least three vertices but has "
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

        degenerateTriangles += builder.degenerateTriangles;
        polygonCount += acceptedPolygons;
        if (builder.triangleCount() == 0) {
            report.error(packId, sourceName, pointer, "The polymesh of bone '" + boneName
                    + "' produced no renderable triangles");
            return null;
        }
        return builder.build(acceptedPolygons, BoneMesh.Source.POLY_MESH);
    }


    private Vec3 shadeNormal(int[] corners, MeshBuilder builder) {
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
    private static Vec3 geometricFaceNormal(int[] corners, MeshBuilder builder) {
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
        if (!(length > NORMAL_EPSILON)) {
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
                                   MeshBuilder builder) {
        String cornerPointer = polyPointer + "[" + cornerIndex + "]";
        JsonElement cornerElement = poly.get(cornerIndex);
        if (!(cornerElement instanceof JsonArray corner) || corner.size() < 3) {
            report.error(packId, sourceName, cornerPointer, "Expected [position, normal, uv] indices but found "
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
            report.error(packId, sourceName, cornerPointer, problem + "; the polygon was skipped");
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

    // --- cube geometry ---

    /**
     * Converts every cube of one bone into a single triangle mesh.
     *
     * <p>Cube coordinates are model space, like polymesh positions. Per-face UVs are normalised with the
     * declared texture size, negative {@code uv_size} values flip the sampling direction, {@code inflate}
     * expands the cube and a cube {@code rotation} is baked into the vertices around the cube pivot.</p>
     */
    private BoneMesh readCubes(JsonArray cubes, String pointer, String boneName) {
        MeshBuilder builder = new MeshBuilder();
        int cubeFaces = 0;

        for (int cubeIndex = 0; cubeIndex < cubes.size(); cubeIndex++) {
            String cubePointer = pointer + "[" + cubeIndex + "]";
            JsonElement cubeElement = cubes.get(cubeIndex);
            if (!(cubeElement instanceof JsonObject cube)) {
                report.error(packId, sourceName, cubePointer, "Expected a cube object but found "
                        + JsonValues.describeType(cubeElement) + "; the cube was skipped");
                continue;
            }

            Vec3 origin = readVector(cube.get("origin"), cubePointer + ".origin", null, true);
            Vec3 size = readVector(cube.get("size"), cubePointer + ".size", null, true);
            if (origin == null || size == null) {
                report.error(packId, sourceName, cubePointer,
                        "The cube has no usable 'origin'/'size'; it was skipped");
                continue;
            }

            float inflate = readFloat(cube.get("inflate"), cubePointer + ".inflate", 0.0F);
            if (cube.has("mirror")) {
                reportUnsupported(cubePointer + ".mirror", "cube mirroring on bone '" + boneName
                        + "' is not applied; the cube's faces keep their authored UVs");
            }

            float minX = Math.min(origin.x(), origin.x() + size.x()) - inflate;
            float minY = Math.min(origin.y(), origin.y() + size.y()) - inflate;
            float minZ = Math.min(origin.z(), origin.z() + size.z()) - inflate;
            float maxX = Math.max(origin.x(), origin.x() + size.x()) + inflate;
            float maxY = Math.max(origin.y(), origin.y() + size.y()) + inflate;
            float maxZ = Math.max(origin.z(), origin.z() + size.z()) + inflate;

            Map<String, FaceUv> faceUvs = readCubeUvs(cube.get("uv"), cubePointer + ".uv", size);

            Vec3 rotation = readVector(cube.get("rotation"), cubePointer + ".rotation", Vec3.ZERO, false);
            Vec3 pivot = readVector(cube.get("pivot"), cubePointer + ".pivot",
                    new Vec3((minX + maxX) / 2.0F, (minY + maxY) / 2.0F, (minZ + maxZ) / 2.0F), false);

            int facesOfCube = 0;
            for (String face : FACE_NAMES) {
                FaceUv faceUv = faceUvs.get(face);
                if (faceUv == null) {
                    continue;
                }
                Vec3 normal = faceNormal(face);
                float[][] corners = faceCorners(face, minX, minY, minZ, maxX, maxY, maxZ);
                float[][] uvSt = {{0.0F, 0.0F}, {1.0F, 0.0F}, {1.0F, 1.0F}, {0.0F, 1.0F}};

                int[] indices = new int[4];
                for (int corner = 0; corner < 4; corner++) {
                    Vec3 position = new Vec3(corners[corner][0], corners[corner][1], corners[corner][2]);
                    if (!rotation.isZero()) {
                        position = rotateAround(position, pivot, rotation);
                    }
                    float u = faceUv.u() + uvSt[corner][0] * faceUv.width();
                    float v = faceUv.v() + uvSt[corner][1] * faceUv.height();
                    indices[corner] = builder.addCorner(position, normal, u, v);
                }
                builder.addQuad(indices[0], indices[1], indices[2], indices[3], normal);
                facesOfCube++;
            }

            if (facesOfCube > 0) {
                cubeCount++;
                cubeFaces += facesOfCube;
            }
        }

        degenerateTriangles += builder.degenerateTriangles;
        if (builder.triangleCount() == 0) {
            if (!cubes.isEmpty()) {
                report.error(packId, sourceName, pointer, "The cubes of bone '" + boneName
                        + "' produced no renderable triangles");
            }
            return null;
        }
        polygonCount += cubeFaces;
        return builder.build(cubeFaces, BoneMesh.Source.CUBE);
    }

    /**
     * Reads the {@code uv} of a cube, either as a box-mapped {@code [u, v]} pair or as a per-face object.
     */
    private Map<String, FaceUv> readCubeUvs(JsonElement uvElement, String pointer, Vec3 size) {
        Map<String, FaceUv> faceUvs = new LinkedHashMap<>();
        if (uvElement instanceof JsonArray boxUv && boxUv.size() >= 2) {
            Double u = JsonValues.asDouble(boxUv.get(0));
            Double v = JsonValues.asDouble(boxUv.get(1));
            if (u == null || v == null) {
                report.error(packId, sourceName, pointer, "Expected numeric box UV coordinates but found "
                        + uvElement + "; the cube faces are not rendered");
                return faceUvs;
            }
            float width = Math.abs(size.x());
            float height = Math.abs(size.y());
            float depth = Math.abs(size.z());
            faceUvs.put("up", faceUv(u + depth, v, width, depth));
            faceUvs.put("down", faceUv(u + depth + width, v, width, depth));
            faceUvs.put("east", faceUv(u, v + depth, depth, height));
            faceUvs.put("north", faceUv(u + depth, v + depth, width, height));
            faceUvs.put("west", faceUv(u + depth + width, v + depth, depth, height));
            faceUvs.put("south", faceUv(u + depth + width + depth, v + depth, width, height));
            return faceUvs;
        }

        if (uvElement instanceof JsonObject perFace) {
            for (String face : FACE_NAMES) {
                JsonElement faceElement = perFace.get(face);
                if (faceElement == null) {
                    report.warning(packId, sourceName, pointer, "The cube has no UV for face '" + face
                            + "'; that face is not rendered");
                    continue;
                }
                if (!(faceElement instanceof JsonObject faceObject)) {
                    report.error(packId, sourceName, pointer + "." + face, "Expected an object but found "
                            + JsonValues.describeType(faceElement) + "; the face is not rendered");
                    continue;
                }
                JsonArray uv = readArray(faceObject.get("uv"), pointer + "." + face + ".uv");
                JsonArray uvSize = readArray(faceObject.get("uv_size"), pointer + "." + face + ".uv_size");
                if (uv == null || uvSize == null || uv.size() < 2 || uvSize.size() < 2) {
                    report.error(packId, sourceName, pointer + "." + face,
                            "The face needs 'uv' and 'uv_size' arrays with two numbers; the face is not rendered");
                    continue;
                }
                Double u = JsonValues.asDouble(uv.get(0));
                Double v = JsonValues.asDouble(uv.get(1));
                Double uvWidth = JsonValues.asDouble(uvSize.get(0));
                Double uvHeight = JsonValues.asDouble(uvSize.get(1));
                if (u == null || v == null || uvWidth == null || uvHeight == null) {
                    report.error(packId, sourceName, pointer + "." + face,
                            "The face UV values are not numbers; the face is not rendered");
                    continue;
                }
                for (String key : faceObject.keySet()) {
                    if (!key.equals("uv") && !key.equals("uv_size") && !key.equals("uv_rotation")) {
                        reportUnsupported(pointer + "." + face + "." + key, "cube face property '" + key
                                + "' is not interpreted");
                    }
                }
                if (faceObject.has("uv_rotation")) {
                    reportUnsupported(pointer + "." + face + ".uv_rotation",
                            "per-face UV rotation is not applied; the face keeps its authored UV orientation");
                }
                faceUvs.put(face, faceUv(u, v, uvWidth, uvHeight));
            }
            return faceUvs;
        }

        if (uvElement != null) {
            report.error(packId, sourceName, pointer, "Expected a [u, v] array or a per-face object but found "
                    + JsonValues.describeType(uvElement) + "; the cube faces are not rendered");
        } else {
            report.warning(packId, sourceName, pointer, "The cube declares no UV; its faces are not rendered");
        }
        return faceUvs;
    }

    /**
     * Normalises one face UV rectangle. Negative sizes are kept, because they flip the sampling direction
     * exactly as the format intends.
     */
    private FaceUv faceUv(double u, double v, double width, double height) {
        return new FaceUv((float) (u / textureWidth), (float) (v / textureHeight),
                (float) (width / textureWidth), (float) (height / textureHeight));
    }

    private static Vec3 faceNormal(String face) {
        return switch (face) {
            case "north" -> new Vec3(0.0F, 0.0F, -1.0F);
            case "south" -> new Vec3(0.0F, 0.0F, 1.0F);
            case "east" -> new Vec3(1.0F, 0.0F, 0.0F);
            case "west" -> new Vec3(-1.0F, 0.0F, 0.0F);
            case "up" -> new Vec3(0.0F, 1.0F, 0.0F);
            case "down" -> new Vec3(0.0F, -1.0F, 0.0F);
            default -> throw new IllegalArgumentException("Unknown face '" + face + "'");
        };
    }

    /** @return the four corners of a face, ordered so that the s/t UV axes run along the face. */
    private static float[][] faceCorners(String face, float minX, float minY, float minZ,
                                         float maxX, float maxY, float maxZ) {
        return switch (face) {
            case "north" -> new float[][]{
                    {minX, minY, minZ}, {maxX, minY, minZ}, {maxX, maxY, minZ}, {minX, maxY, minZ}};
            case "south" -> new float[][]{
                    {maxX, minY, maxZ}, {minX, minY, maxZ}, {minX, maxY, maxZ}, {maxX, maxY, maxZ}};
            case "east" -> new float[][]{
                    {maxX, minY, maxZ}, {maxX, minY, minZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ}};
            case "west" -> new float[][]{
                    {minX, minY, minZ}, {minX, minY, maxZ}, {minX, maxY, maxZ}, {minX, maxY, minZ}};
            case "up" -> new float[][]{
                    {minX, maxY, minZ}, {maxX, maxY, minZ}, {maxX, maxY, maxZ}, {minX, maxY, maxZ}};
            case "down" -> new float[][]{
                    {minX, minY, maxZ}, {maxX, minY, maxZ}, {maxX, minY, minZ}, {minX, minY, minZ}};
            default -> throw new IllegalArgumentException("Unknown face '" + face + "'");
        };
    }

    /** Rotates {@code point} around {@code pivot} by {@code rotation} degrees, applying Z, then Y, then X. */
    private static Vec3 rotateAround(Vec3 point, Vec3 pivot, Vec3 rotation) {
        double x = point.x() - pivot.x();
        double y = point.y() - pivot.y();
        double z = point.z() - pivot.z();

        double radiansZ = Math.toRadians(rotation.z());
        double cosZ = Math.cos(radiansZ);
        double sinZ = Math.sin(radiansZ);
        double x1 = x * cosZ - y * sinZ;
        double y1 = x * sinZ + y * cosZ;

        double radiansY = Math.toRadians(rotation.y());
        double cosY = Math.cos(radiansY);
        double sinY = Math.sin(radiansY);
        double x2 = x1 * cosY + z * sinY;
        double z2 = -x1 * sinY + z * cosY;

        double radiansX = Math.toRadians(rotation.x());
        double cosX = Math.cos(radiansX);
        double sinX = Math.sin(radiansX);
        double y3 = y1 * cosX - z2 * sinX;
        double z3 = y1 * sinX + z2 * cosX;

        return new Vec3((float) (x2 + pivot.x()), (float) (y3 + pivot.y()), (float) (z3 + pivot.z()));
    }

    // --- hierarchy assembly ---

    private WeaponModel build() {
        if (drafts.isEmpty()) {
            report.error(packId, sourceName, resourceId.asString(),
                    "The model declares no bones, so there is nothing to render");
            return null;
        }

        Map<String, String> parents = new LinkedHashMap<>();
        for (BoneDraft draft : drafts.values()) {
            parents.put(draft.name, validateParent(draft));
        }

        Map<String, List<String>> childNames = new LinkedHashMap<>();
        List<String> rootNames = new ArrayList<>();
        for (BoneDraft draft : drafts.values()) {
            String parent = parents.get(draft.name);
            if (parent == null) {
                rootNames.add(draft.name);
            } else {
                childNames.computeIfAbsent(parent, key -> new ArrayList<>()).add(draft.name);
            }
        }

        List<ModelBone> ordered = new ArrayList<>();
        List<ModelBone> roots = new ArrayList<>();
        for (String rootName : rootNames) {
            roots.add(buildBone(rootName, parents, childNames, ordered));
        }

        int withGeometry = 0;
        int triangles = 0;
        for (ModelBone bone : ordered) {
            if (!bone.meshes().isEmpty()) {
                withGeometry++;
            }
            triangles += bone.triangleCount();
        }
        if (triangles == 0) {
            report.error(packId, sourceName, resourceId.asString(),
                    "The model contains no renderable triangles, so it cannot be drawn");
            return null;
        }
        if (degenerateTriangles > 0) {
            report.info(packId, sourceName, resourceId.asString(), degenerateTriangles
                    + " degenerate triangle(s) with zero area were kept as authored; they are not visible");
        }
        report.info(packId, sourceName, resourceId.asString(), "Parsed " + ordered.size() + " bone(s), "
                + withGeometry + " with geometry, " + polygonCount + " polygon(s), " + cubeCount + " cube(s) and "
                + "a " + textureWidth + "x" + textureHeight + " declared texture");

        return new WeaponModel(resourceId, identifier, formatVersion, textureWidth, textureHeight,
                ordered, roots, polygonCount, cubeCount);
    }

    /**
     * Builds one bone and its children. The parent is always inserted before its children, so callers can
     * render {@link WeaponModel#bonesInOrder()} sequentially and still apply the whole hierarchy.
     */
    private ModelBone buildBone(String name, Map<String, String> parents, Map<String, List<String>> childNames,
                                List<ModelBone> ordered) {
        BoneDraft draft = drafts.get(name);
        int position = ordered.size();
        ordered.add(null);

        List<ModelBone> children = new ArrayList<>();
        for (String childName : childNames.getOrDefault(name, List.of())) {
            children.add(buildBone(childName, parents, childNames, ordered));
        }

        ModelBone bone = new ModelBone(draft.name, parents.get(name), draft.pivot, draft.rotation,
                draft.meshes, children);
        ordered.set(position, bone);
        return bone;
    }

    /**
     * @return the parent to use, or {@code null} when the bone becomes a root because the declared parent
     *         does not exist or would produce a cycle (both cases are reported)
     */
    private String validateParent(BoneDraft draft) {
        String parent = draft.parentName;
        if (parent == null) {
            return null;
        }
        if (!drafts.containsKey(parent)) {
            report.error(packId, sourceName, resourceId.asString() + "#" + draft.name,
                    "The parent '" + parent + "' does not exist; the bone is rendered as a root so its geometry "
                            + "stays visible");
            return null;
        }

        Set<String> seen = new HashSet<>();
        seen.add(draft.name);
        String current = parent;
        while (current != null) {
            if (!seen.add(current)) {
                report.error(packId, sourceName, resourceId.asString() + "#" + draft.name,
                        "The bone hierarchy contains a cycle through '" + current + "'; the bone is rendered as a "
                                + "root so its geometry stays visible");
                return null;
            }
            BoneDraft parentDraft = drafts.get(current);
            current = parentDraft == null ? null : parentDraft.parentName;
        }
        return parent;
    }

    // --- JSON helpers ---

    private JsonArray readArray(JsonElement element, String pointer) {
        if (element == null) {
            return null;
        }
        if (element instanceof JsonArray array) {
            return array;
        }
        report.error(packId, sourceName, pointer, "Expected an array but found " + JsonValues.describeType(element));
        return null;
    }

    private List<Vec3> readVectorList(JsonArray array, String pointer) {
        List<Vec3> vectors = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            Vec3 vector = readVector(array.get(index), pointer + "[" + index + "]", null, true);
            if (vector == null) {
                return List.of();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private List<float[]> readUvList(JsonArray array, String pointer) {
        List<float[]> uvs = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            String elementPointer = pointer + "[" + index + "]";
            if (!(array.get(index) instanceof JsonArray uv) || uv.size() < 2) {
                report.error(packId, sourceName, elementPointer, "Expected [u, v] but found "
                        + JsonValues.describeType(array.get(index)));
                return List.of();
            }
            Double u = JsonValues.asDouble(uv.get(0));
            Double v = JsonValues.asDouble(uv.get(1));
            if (u == null || v == null) {
                report.error(packId, sourceName, elementPointer, "The UV coordinates are not numbers");
                return List.of();
            }
            uvs.add(new float[]{u.floatValue(), v.floatValue()});
        }
        return uvs;
    }

    private Vec3 readVector(JsonElement element, String pointer, Vec3 fallback, boolean required) {
        if (element == null) {
            return fallback;
        }
        if (!(element instanceof JsonArray array) || array.size() < 3) {
            if (required) {
                report.error(packId, sourceName, pointer, "Expected an array of three numbers but found "
                        + JsonValues.describeType(element));
            } else {
                report.warning(packId, sourceName, pointer, "Expected an array of three numbers but found "
                        + JsonValues.describeType(element) + "; the value is treated as zero");
            }
            return fallback;
        }
        Double x = JsonValues.asDouble(array.get(0));
        Double y = JsonValues.asDouble(array.get(1));
        Double z = JsonValues.asDouble(array.get(2));
        if (x == null || y == null || z == null) {
            report.error(packId, sourceName, pointer, "The vector components are not all numbers; "
                    + (required ? "the value is unusable" : "the value is treated as zero"));
            return fallback;
        }
        return new Vec3(x.floatValue(), y.floatValue(), z.floatValue());
    }

    private float readFloat(JsonElement element, String pointer, float fallback) {
        if (element == null) {
            return fallback;
        }
        Double value = JsonValues.asDouble(element);
        if (value == null) {
            report.warning(packId, sourceName, pointer, "Expected a number but found "
                    + JsonValues.describeType(element) + "; " + fallback + " is used");
            return fallback;
        }
        return value.floatValue();
    }

    /** Reports a problem at most once per JSON pointer, so repeated polygons do not flood the log. */
    private void reportUnsupported(String pointer, String message) {
        if (reportedPointers.add(pointer)) {
            report.warning(packId, sourceName, pointer, message);
        }
    }

    // --- internal value types ---

    /** One bone as declared, before the hierarchy is resolved. */
    private static final class BoneDraft {

        private final String name;
        private final String parentName;
        private final Vec3 pivot;
        private final Vec3 rotation;
        private final List<BoneMesh> meshes;

        private BoneDraft(String name, String parentName, Vec3 pivot, Vec3 rotation, List<BoneMesh> meshes) {
            this.name = name;
            this.parentName = parentName;
            this.pivot = pivot;
            this.rotation = rotation;
            this.meshes = List.copyOf(meshes);
        }
    }

    /** One normalised face UV rectangle; negative sizes flip the sampling direction. */
    private record FaceUv(float u, float v, float width, float height) {
    }

    /**
     * Accumulates resolved corners and triangles, correcting the winding of every triangle against its
     * authored normal so back-face culling is correct even when a generator exported inconsistent
     * winding.
     */
    private static final class MeshBuilder {

        private final List<Float> positions = new ArrayList<>();
        private final List<Float> normals = new ArrayList<>();
        private final List<Float> uvs = new ArrayList<>();
        private final List<Integer> indices = new ArrayList<>();
        private int degenerateTriangles;

        private int addCorner(Vec3 position, Vec3 normal, float u, float v) {
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

        /** @return the position of an already added corner. */
        private Vec3 positionAt(int corner) {
            return new Vec3(positions.get(corner * 3), positions.get(corner * 3 + 1), positions.get(corner * 3 + 2));
        }

        /**
         * Averages the authored normals of the given corners.
         *
         * @param corners the corners to average
         * @return the normalised average, or {@link Vec3#ZERO} when no normal was authored or the
         *         authored normals cancel each other out
         */
        private Vec3 averageNormal(int[] corners) {
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

        /** Replaces the authored normals of the given corners with one shading normal. */
        private void setNormals(int[] corners, Vec3 normal) {
            for (int corner : corners) {
                normals.set(corner * 3, normal.x());
                normals.set(corner * 3 + 1, normal.y());
                normals.set(corner * 3 + 2, normal.z());
            }
        }

        private void addQuad(int a, int b, int c, int d, Vec3 faceNormal) {
            addTriangle(a, b, c, faceNormal);
            addTriangle(a, c, d, faceNormal);
        }

        private void addPolygon(int[] corners, Vec3 faceNormal) {
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

        private int triangleCount() {
            return indices.size() / 3;
        }

        private BoneMesh build(int polygonCount, BoneMesh.Source source) {
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
    }
}
