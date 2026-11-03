package com.arg_studios.arg_weaponry.weapon.model;

import com.arg_studios.arg_weaponry.json.JsonValues;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Bedrock cube geometry layer: one {@code cubes} array in, one {@link BoneMesh} out.
 *
 * <p>This is geometry construction and nothing else. It reads a cube's {@code origin}/{@code size}, its {@code inflate},
 * its own {@code rotation} about its {@code pivot} and its UV (box-mapped {@code [u, v]} or per-face
 * {@code {uv, uv_size}}), and emits the six faces as corners with positions, normals and UVs. It knows nothing about
 * bones, animation, textures, pipelines or the {@code poly_mesh} system next door, which is why the cube path can be
 * reasoned about - and tested - on its own.</p>
 *
 * <p>Two properties of the emitted geometry are deliberate and must not regress:</p>
 * <ul>
 *   <li><strong>UV orientation.</strong> {@link #UV_CORNERS} anchors the top of every face's rectangle on the edge the
 *   authoring tool anchors it on, which is what keeps a cube's artwork upright and on the right face. It is stated once,
 *   for all six faces, and both the per-face and the box-mapped form are expressed in that one orientation.</li>
 *   <li><strong>Rotation space.</strong> A cube's own rotation is baked into its corners here - around its pivot, through
 *   {@link RotationConvention#toRenderSpace(Vec3)} and {@link BedrockRotation} - because that is the only place that
 *   knows the authored pivot. Bone rotations are <em>not</em> baked here: they are the renderer's PoseStack, applied once
 *   while the hierarchy is walked.</li>
 * </ul>
 *
 * <p>The returned {@link GeometryBlock} carries the mesh plus the counters of the call - how many cubes produced
 * geometry and how many emitted triangles were degenerate - so the parser keeps owning the model-wide totals and this
 * class stays free of document state.</p>
 */
final class BedrockCubeGeometry {

    private static final List<String> FACE_NAMES = List.of("north", "east", "south", "west", "up", "down");

    /**
     * Where each of a face's four corners sits inside its authored UV rectangle, as {@code (s, t)} with
     * {@code u = u0 + s * width} and {@code v = v0 + t * height}.
     *
     * <p>The face's corners are listed by {@link #faceCorners(String, CubeBox)} in the order the authoring tool gives
     * them: its cube mesh keeps four vertices per face ({@code THREE.BufferGeometry.setShape}, added by Blockbench in
     * {@code three_custom.js}) and that order is exactly what {@code faceCorners} reproduces. {@code updateUV} in
     * {@code outliner/types/cube.js} then writes the face's rectangle into those four slots as
     * {@code [(x1, y1), (x2, y1), (x1, y2), (x2, y2)]} of the internal rectangle, whose origin is its <em>top-left</em>
     * corner in the texture, U running right and V running down. Because Blockbench uploads its UVs with a bottom-left
     * origin, it stores {@code 1 - v} for each of them, so the four texels it actually samples are</p>
     *
     * <pre>
     * slot 0: (u0, v1)   slot 1: (u1, v1)      // v1 is the bottom edge of the rectangle
     * slot 2: (u1, v0)   slot 3: (u0, v0)      // v0 is its top edge
     * </pre>
     *
     * <p>In other words the <em>first</em> corner pair of every face takes the rectangle's bottom edge and the last pair
     * its top edge, so {@code t} runs {@code 1, 1, 0, 0}. Anchoring {@code t = 0} at the first pair instead (which is
     * what {@code 0, 0, 1, 1} does) mirrors the texture of all six faces vertically: every rectangle is still sampled,
     * every corner is still inside it, and only the artwork lands in the wrong rows - which is why that mistake shows up
     * as wrong-looking strips and bands rather than as an obviously broken mapping.</p>
     *
     * <p>The same table serves the per-face and the box-UV path because {@link #readCubeUvs} expresses both rectangle
     * sets in this one orientation; the box unwrap's top and bottom panels are 180 degrees apart from each other, which
     * is encoded there by the sign of the {@code down} rectangle's height.</p>
     */
    private static final float[][] UV_CORNERS = {
            {0.0F, 1.0F}, {1.0F, 1.0F}, {1.0F, 0.0F}, {0.0F, 0.0F}};

    private final GeometryJson json;
    private final GeometryDiagnostics diagnostics;
    private final int textureWidth;
    private final int textureHeight;
    private int cubeCount;
    private int polygonCount;
    private int degenerateTriangles;

    /**
     * @param json          the shared JSON reader of this parse
     * @param diagnostics   where problems are reported
     * @param textureWidth  the declared texture width, used to normalise pixel UVs
     * @param textureHeight the declared texture height, used to normalise pixel UVs
     */
    BedrockCubeGeometry(GeometryJson json, GeometryDiagnostics diagnostics, int textureWidth, int textureHeight) {
        this.json = json;
        this.diagnostics = diagnostics;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
    }

    GeometryBlock read(JsonArray cubes, String pointer, String boneName, float boneInflate) {
        MeshGeometryBuilder builder = new MeshGeometryBuilder();
        int cubeFaces = 0;

        for (int cubeIndex = 0; cubeIndex < cubes.size(); cubeIndex++) {
            String cubePointer = pointer + "[" + cubeIndex + "]";
            JsonElement cubeElement = cubes.get(cubeIndex);
            if (!(cubeElement instanceof JsonObject cube)) {
                diagnostics.error(cubePointer, "Expected a cube object but found "
                        + JsonValues.describeType(cubeElement) + "; the cube was skipped");
                continue;
            }

            Vec3 origin = json.vector(cube.get("origin"), cubePointer + ".origin", null, true);
            Vec3 size = json.vector(cube.get("size"), cubePointer + ".size", null, true);
            if (origin == null || size == null) {
                diagnostics.error(cubePointer,
                        "The cube has no usable 'origin'/'size'; it was skipped");
                continue;
            }

            float inflate = resolveInflate(cube, cubePointer, size, boneInflate);
            if (cube.has("mirror")) {
                diagnostics.unsupported(cubePointer + ".mirror", "cube mirroring on bone '" + boneName
                        + "' is not applied; the cube's faces keep their authored UVs");
            }

            CubeBox box = CubeBox.of(origin, size, inflate);

            Map<String, FaceUv> faceUvs = readCubeUvs(cube.get("uv"), cubePointer + ".uv", size);

            Vec3 rotation = readCubeRotation(cube, cubePointer);
            Vec3 pivot = json.vector(cube.get("pivot"), cubePointer + ".pivot", box.centre(), false);

            int facesOfCube = 0;
            for (String face : FACE_NAMES) {
                FaceUv faceUv = faceUvs.get(face);
                if (faceUv == null) {
                    continue;
                }
                Vec3 normal = BedrockRotation.rotate(faceNormal(face), rotation);
                float[][] corners = faceCorners(face, box);
                // The rectangle is sampled through UV_CORNERS: the face's first two corners take its rectangle's bottom
                // edge (left then right) and its last two that rectangle's top edge, which is the order the authoring
                // tool samples them in. That is what lets a negative uv_size reverse the sampling direction of a face
                // without moving any corner, and it is why every face shows its artwork the way the authoring tool shows
                // it. A corner is never shared between faces, so each face keeps the full rectangle even where two faces
                // meet.

                int[] indices = new int[4];
                for (int corner = 0; corner < 4; corner++) {
                    Vec3 position = new Vec3(corners[corner][0], corners[corner][1], corners[corner][2]);
                    position = BedrockRotation.rotateAround(position, pivot, rotation);
                    float u = faceUv.u() + UV_CORNERS[corner][0] * faceUv.width();
                    float v = faceUv.v() + UV_CORNERS[corner][1] * faceUv.height();
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

        degenerateTriangles += builder.degenerateTriangles();
        if (builder.triangleCount() == 0) {
            if (!cubes.isEmpty()) {
                diagnostics.error(pointer, "The cubes of bone '" + boneName
                        + "' produced no renderable triangles");
            }
            return null;
        }
        polygonCount += cubeFaces;
        return new GeometryBlock(builder.build(cubeFaces, BoneMesh.Source.CUBE), cubeCount,
                builder.degenerateTriangles());
    }

    /**
     * Reads the {@code uv} of a cube, either as a box-mapped {@code [u, v]} pair or as a per-face object.
     *
     * <p>Both forms are reduced to one {@link FaceUv} rectangle per face, oriented the way {@link #UV_CORNERS} expects:
     * {@code (u, v)} is the rectangle's top-left corner in texture space and the width/height carry its extent, with a
     * negative value expressing a rectangle that was authored right-to-left or bottom-to-top. The box form is the
     * authoring tool's own unwrap ({@code east | north | west | south} with the top and bottom panels above them), and
     * the per-face form is used verbatim - including a negative {@code uv_size}, which is how the format and the
     * authoring tool flip a single face.</p>
     */
    private Map<String, FaceUv> readCubeUvs(JsonElement uvElement, String pointer, Vec3 size) {
        Map<String, FaceUv> faceUvs = new LinkedHashMap<>();
        if (uvElement instanceof JsonArray boxUv && boxUv.size() >= 2) {
            Double u = JsonValues.asDouble(boxUv.get(0));
            Double v = JsonValues.asDouble(boxUv.get(1));
            if (u == null || v == null) {
                diagnostics.error(pointer, "Expected numeric box UV coordinates but found "
                        + uvElement + "; the cube faces are not rendered");
                return faceUvs;
            }
            float width = Math.abs(size.x());
            float height = Math.abs(size.y());
            float depth = Math.abs(size.z());
            faceUvs.put("up", faceUv(u + depth, v, width, depth));
            // The unwrap draws its bottom panel 180 degrees from its top one: the authoring tool's box-UV face list
            // gives 'up' a negative width *and* height but 'down' only a negative width, and Minecraft's own box
            // mapping passes the bottom panel's V range the other way round as well. Anchoring the down rectangle at
            // its lower edge keeps that rotation with the one UV_CORNERS table; the texels covered are unchanged.
            faceUvs.put("down", faceUv(u + depth + width, v + depth, width, -depth));
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
                    diagnostics.warning(pointer, "The cube has no UV for face '" + face
                            + "'; that face is not rendered");
                    continue;
                }
                if (!(faceElement instanceof JsonObject faceObject)) {
                    diagnostics.error(pointer + "." + face, "Expected an object but found "
                            + JsonValues.describeType(faceElement) + "; the face is not rendered");
                    continue;
                }
                JsonArray uv = json.array(faceObject.get("uv"), pointer + "." + face + ".uv");
                JsonArray uvSize = json.array(faceObject.get("uv_size"), pointer + "." + face + ".uv_size");
                if (uv == null || uvSize == null || uv.size() < 2 || uvSize.size() < 2) {
                    diagnostics.error(pointer + "." + face,
                            "The face needs 'uv' and 'uv_size' arrays with two numbers; the face is not rendered");
                    continue;
                }
                Double u = JsonValues.asDouble(uv.get(0));
                Double v = JsonValues.asDouble(uv.get(1));
                Double uvWidth = JsonValues.asDouble(uvSize.get(0));
                Double uvHeight = JsonValues.asDouble(uvSize.get(1));
                if (u == null || v == null || uvWidth == null || uvHeight == null) {
                    diagnostics.error(pointer + "." + face,
                            "The face UV values are not numbers; the face is not rendered");
                    continue;
                }
                for (String key : faceObject.keySet()) {
                    if (!key.equals("uv") && !key.equals("uv_size") && !key.equals("uv_rotation")) {
                        diagnostics.unsupported(pointer + "." + face + "." + key, "cube face property '" + key
                                + "' is not interpreted");
                    }
                }
                if (faceObject.has("uv_rotation")) {
                    diagnostics.unsupported(pointer + "." + face + ".uv_rotation",
                            "per-face UV rotation is not applied; the face keeps its authored UV orientation");
                }
                faceUvs.put(face, faceUv(u, v, uvWidth, uvHeight));
            }
            return faceUvs;
        }

        if (uvElement != null) {
            diagnostics.error(pointer, "Expected a [u, v] array or a per-face object but found "
                    + JsonValues.describeType(uvElement) + "; the cube faces are not rendered");
        } else {
            diagnostics.warning(pointer, "The cube declares no UV; its faces are not rendered");
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

    /**
     * The min/max extents of one cube, i.e. the eight corners its six faces are built from.
     *
     * <p>A cube is authored as an {@code origin} plus a {@code size}; either may be negative on any axis, because the
     * format only requires the pair to describe a box. {@link #of(Vec3, Vec3, float)} normalises them into a real
     * min/max pair and then applies the inflation to both extents, so the faces and the default pivot all read one
     * consistent box instead of each recomputing the arithmetic.</p>
     *
     * @param minX the smallest x, after inflation
     * @param minY the smallest y, after inflation
     * @param minZ the smallest z, after inflation
     * @param maxX the largest x, after inflation
     * @param maxY the largest y, after inflation
     * @param maxZ the largest z, after inflation
     */
    private record CubeBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {

        /**
         * @param origin  the authored {@code origin}, which is the box's lower corner in model units
         * @param size    the authored {@code size}, whose components may be negative
         * @param inflate the additive inflation per side, already clamped by {@link #resolveInflate}
         * @return the box the six faces are built from
         */
        static CubeBox of(Vec3 origin, Vec3 size, float inflate) {
            return new CubeBox(
                    Math.min(origin.x(), origin.x() + size.x()) - inflate,
                    Math.min(origin.y(), origin.y() + size.y()) - inflate,
                    Math.min(origin.z(), origin.z() + size.z()) - inflate,
                    Math.max(origin.x(), origin.x() + size.x()) + inflate,
                    Math.max(origin.y(), origin.y() + size.y()) + inflate,
                    Math.max(origin.z(), origin.z() + size.z()) + inflate);
        }

        /**
         * @return the centre of the box, which is the default rotation pivot of a cube. Inflation cannot move it,
         * because both extents move by the same amount.
         */
        Vec3 centre() {
            return new Vec3((minX + maxX) / 2.0F, (minY + maxY) / 2.0F, (minZ + maxZ) / 2.0F);
        }
    }

    /**
     * @return the four corners of a face, ordered so that the s/t UV axes run along the face.
     */
    private static float[][] faceCorners(String face, CubeBox box) {
        float minX = box.minX();
        float minY = box.minY();
        float minZ = box.minZ();
        float maxX = box.maxX();
        float maxY = box.maxY();
        float maxZ = box.maxZ();
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

    /**
     * Reads a cube's own {@code rotation}, already converted into the space the renderer composes in.
     *
     * <p>The conversion is the same one every bone rotation takes ({@link RotationConvention#toRenderSpace(Vec3)}),
     * because a cube rotation and a bone rotation are authored in the same space and are drawn under the same mirror.
     * Applying the authored triple verbatim is what makes long rotated parts swing the wrong way.</p>
     *
     * @param cube        the cube object
     * @param cubePointer the JSON pointer of the cube, for diagnostics
     * @return the rotation to bake into the vertices, {@link Vec3#ZERO} when the cube declares none
     */
    private Vec3 readCubeRotation(JsonObject cube, String cubePointer) {
        Vec3 rotation = json.vector(cube.get("rotation"), cubePointer + ".rotation", Vec3.ZERO, false);
        if (rotation.isZero()) {
            return Vec3.ZERO;
        }
        return RotationConvention.toRenderSpace(rotation);
    }

    /**
     * Resolves the inflation that applies to one cube.
     *
     * <p>Inflation is <em>additive on every side</em>, which is why it is applied to the box extents and not to its size:
     * {@code inflate} is subtracted from the minimum and added to the maximum, so positive values grow the cube, negative
     * values shrink it, and either kind works on a cube that has a zero-length side. The box's centre - and therefore the
     * default rotation pivot - does not move, because both extents move by the same amount.</p>
     *
     * <p>Two things are decided here:</p>
     * <ul>
     *     <li>a <em>bone-level</em> {@code inflate} is the default for every cube of the bone, and a cube-level
     *     {@code inflate} overrides it for that cube only - the geometry schema says so explicitly ("this field overrides
     *     the bone's inflate field for this cube only");</li>
     *     <li>a deflation larger than half of the cube's thinnest side would give that side a negative length, which is
     *     not a smaller cube but an inside-out one: the faces would keep the normals they are shaded with while their
     *     corners swapped sides, so every face would be lit from behind. Such a value is clamped to the point where the
     *     thinnest side reaches zero thickness - the smallest box a uniform deflation can produce - and is reported.</li>
     * </ul>
     *
     * @param cube        the authored cube
     * @param cubePointer the JSON pointer of the cube, for diagnostics
     * @param size        the authored size, used to find the thinnest side
     * @param boneInflate the bone-level inflation, used when the cube declares none
     * @return the inflation to apply, never able to invert the box
     */
    private float resolveInflate(JsonObject cube, String cubePointer, Vec3 size, float boneInflate) {
        float inflate = json.floatValue(cube.get("inflate"), cubePointer + ".inflate", boneInflate);
        float thinnest = Math.min(Math.abs(size.x()), Math.min(Math.abs(size.y()), Math.abs(size.z())));
        float limit = -thinnest / 2.0F;
        if (inflate < limit) {
            diagnostics.warning(cubePointer + ".inflate", "Deflating the cube by " + inflate
                    + " would give it a negative side length (its thinnest side is " + thinnest + " model units); the"
                    + " value is clamped to " + limit + " so the box collapses to a plane instead of turning inside out");
            return limit;
        }
        return inflate;
    }

    /**
     * One normalised face UV rectangle; negative sizes flip the sampling direction.
     */
    private record FaceUv(float u, float v, float width, float height) {
    }
}
