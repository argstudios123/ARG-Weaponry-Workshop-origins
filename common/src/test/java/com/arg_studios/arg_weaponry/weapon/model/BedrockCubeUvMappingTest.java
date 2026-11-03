package com.arg_studios.arg_weaponry.weapon.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arg_studios.arg_weaponry.core.PackDiagnostic;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The UV half of the cube path: <em>which</em> texel each geometric corner of each face samples.
 *
 * <p>The expected side is {@link BlockbenchCubeUvReference}, an independent transcription of the authoring tool's own
 * cube UV code ({@code THREE.BufferGeometry.setShape}, {@code CubeFace.UVToLocal} and
 * {@code NodePreviewController.updateUV}). Nothing in the expected side calls the parser, so a wrong rectangle
 * normalisation, a wrong corner, a wrong sampling direction or a conversion applied twice cannot pass: every assertion
 * names a geometric corner and the texel that corner has to sample.</p>
 *
 * <p>The cases are the ones a cube renderer has to survive, one per test: all six faces with six different asymmetric
 * rectangles, the anchoring of a rectangle's top edge, a negative {@code uv_size}, a rotated cube, inflated and deflated
 * cubes, several cubes sharing one texture, the box-mapped {@code [u, v]} form, and finally the shipped SPAS12 geometry,
 * cube for cube.</p>
 */
class BedrockCubeUvMappingTest {

    /** Declared texture size of the synthetic geometry, so texels and normalised UVs differ by this factor only. */
    private static final int TEXTURE_SIZE = 128;

    /** Tolerance in texture pixels for a UV comparison. */
    private static final float TOLERANCE = 1.0E-3F;

    private static final String PACK_DIRECTORY = "assets/arg_weaponry/weapon_packs/arg_default_pack";
    private static final String MODELS_DIRECTORY = "assets/arg_pack/models/weapons";
    private static final String SPAS12 = "shotguns/spas12/spas12.json";

    // ---------------------------------------------------------------- all six faces

    @Test
    void everyCornerOfEveryFaceSamplesTheTexelTheAuthoringToolSamples() {
        // Six different rectangles, none of them square, so a swapped width and height, a swapped pair of faces or a
        // missing per-face offset cannot pass. The values are deliberately unrelated to any cube size.
        Map<String, float[]> uvs = new LinkedHashMap<>();
        uvs.put("north", new float[]{4.0F, 8.0F, 6.0F, 10.0F});
        uvs.put("east", new float[]{12.0F, 8.0F, 3.0F, 9.0F});
        uvs.put("south", new float[]{20.0F, 8.0F, 6.0F, 10.0F});
        uvs.put("west", new float[]{28.0F, 8.0F, 3.0F, 9.0F});
        uvs.put("up", new float[]{36.0F, 4.0F, 6.0F, 3.0F});
        uvs.put("down", new float[]{44.0F, 4.0F, 6.0F, 3.0F});

        Vec3 origin = new Vec3(1.0F, 2.0F, 3.0F);
        Vec3 size = new Vec3(5.0F, 7.0F, 9.0F);
        Parsed parsed = parse(geometry(bone("body", null, null,
                cube(origin, size, 0.0F, Vec3.ZERO, Vec3.ZERO, perFaceUvs(uvs)))));
        assertNoErrors(parsed);

        Box box = Box.of(origin, size, 0.0F);
        assertMatchesAuthoringTool(parsed.mesh("body"), box, Vec3.ZERO, box.centre(), uvs, "plain cube");
    }

    @Test
    void theTopEdgeOfEveryVerticalFaceSamplesTheTopOfItsRectangle() {
        // The anchoring rule on its own, without the rest of the rectangle: the first two corners of a vertical face are
        // its top edge, and they sample the rectangle's top edge, v0. Reading it the other way round is exactly the
        // "mirror every face vertically" mistake, and it is worth its own assertion because the full-rectangle test
        // would also pass if the rectangle itself were shifted.
        Map<String, float[]> uvs = new LinkedHashMap<>();
        for (String face : BlockbenchCubeUvReference.FACES) {
            uvs.put(face, new float[]{0.0F, 0.0F, 8.0F, 8.0F});
        }

        Vec3 origin = new Vec3(0.0F, 0.0F, 0.0F);
        Vec3 size = new Vec3(4.0F, 4.0F, 6.0F);
        Parsed parsed = parse(geometry(bone("body", null, null,
                cube(origin, size, 0.0F, Vec3.ZERO, Vec3.ZERO, perFaceUvs(uvs)))));
        assertNoErrors(parsed);

        Box box = Box.of(origin, size, 0.0F);
        CornerIndex index = new CornerIndex(parsed.mesh("body"), "square cube");
        for (String face : List.of("north", "east", "south", "west")) {
            Vec3 normal = axisNormal(face);
            List<Vec3> corners = BlockbenchCubeUvReference.faceCorners(face, box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ());
            for (int corner = 0; corner < 4; corner++) {
                Vec3 position = corners.get(corner);
                float[] uv = index.take(position, normal);
                assertNotNull(uv, "face '" + face + "' must have a corner at " + describe(position));
                float expected = corner < 2 ? 0.0F : 8.0F;
                assertEquals(expected / TEXTURE_SIZE, uv[1], TOLERANCE, "face '" + face + "', corner " + corner
                        + " at " + describe(position) + " must sample v = " + expected + " texels");
            }
        }
    }

    // ---------------------------------------------------------------- authored flips

    @Test
    void aNegativeUvSizeFlipsTheCornerOrderOfThatFaceOnly() {
        // east is authored right-to-left and bottom-to-top over exactly the texels the positive version covers, which is
        // how the format and the authoring tool flip one face. The covered texels must not move; the corner that samples
        // each end of the rectangle must.
        Map<String, float[]> positive = distinctRectangles();
        Map<String, float[]> negative = new LinkedHashMap<>(positive);
        negative.put("east", new float[]{15.0F, 17.0F, -3.0F, -9.0F});

        Vec3 origin = new Vec3(-1.0F, 0.5F, 2.0F);
        Vec3 size = new Vec3(3.0F, 9.0F, 5.0F);
        Parsed straight = parse(geometry(bone("body", null, null,
                cube(origin, size, 0.0F, Vec3.ZERO, Vec3.ZERO, perFaceUvs(positive)))));
        Parsed flipped = parse(geometry(bone("body", null, null,
                cube(origin, size, 0.0F, Vec3.ZERO, Vec3.ZERO, perFaceUvs(negative)))));
        assertNoErrors(straight);
        assertNoErrors(flipped);

        Box box = Box.of(origin, size, 0.0F);
        assertMatchesAuthoringTool(straight.mesh("body"), box, Vec3.ZERO, box.centre(), positive, "positive uv_size");
        assertMatchesAuthoringTool(flipped.mesh("body"), box, Vec3.ZERO, box.centre(), negative, "negative uv_size");

        assertEquals(texels(straight.mesh("body"), box, positive), texels(flipped.mesh("body"), box, negative),
                "a negative uv_size must cover the same texels, only in the other corner order");
    }

    // ---------------------------------------------------------------- rotation and inflation

    @Test
    void aRotatedCubeKeepsTheUvOfEveryCornerItCarries() {
        Vec3 origin = new Vec3(0.5F, -2.0F, 1.5F);
        Vec3 size = new Vec3(3.0F, 6.0F, 2.0F);
        Vec3 rotation = new Vec3(30.4F, 40.8F, 20.94F);
        Vec3 pivot = new Vec3(1.25F, 0.75F, 2.5F);
        Map<String, float[]> uvs = distinctRectangles();
        Parsed parsed = parse(geometry(bone("body", null, null,
                cube(origin, size, 0.0F, rotation, pivot, perFaceUvs(uvs)))));
        assertNoErrors(parsed);

        assertMatchesAuthoringTool(parsed.mesh("body"), Box.of(origin, size, 0.0F), rotation, pivot, uvs,
                "rotated cube");
    }

    @Test
    void inflationChangesTheBoxButNotTheUvOfAnyCorner() {
        Vec3 origin = new Vec3(0.0F, 0.0F, 0.0F);
        Vec3 size = new Vec3(2.0F, 4.0F, 6.0F);
        Map<String, float[]> uvs = distinctRectangles();

        Parsed grown = parse(geometry(bone("body", null, null,
                cube(origin, size, 0.5F, Vec3.ZERO, Vec3.ZERO, perFaceUvs(uvs)))));
        assertNoErrors(grown);
        Box inflated = Box.of(origin, size, 0.5F);
        assertMatchesAuthoringTool(grown.mesh("body"), inflated, Vec3.ZERO, inflated.centre(), uvs, "inflated cube");

        Parsed shrunk = parse(geometry(bone("body", null, null,
                cube(origin, size, -0.4F, Vec3.ZERO, Vec3.ZERO, perFaceUvs(uvs)))));
        assertNoErrors(shrunk);
        Box deflated = Box.of(origin, size, -0.4F);
        assertMatchesAuthoringTool(shrunk.mesh("body"), deflated, Vec3.ZERO, deflated.centre(), uvs, "deflated cube");
    }

    @Test
    void boneLevelInflationKeepsTheUvOfEveryCornerToo() {
        Vec3 origin = new Vec3(-3.0F, 1.0F, 0.0F);
        Vec3 size = new Vec3(1.0F, 1.0F, 8.0F);
        Map<String, float[]> uvs = distinctRectangles();
        Parsed parsed = parse(geometry(bone("body", null, 0.25F,
                cube(origin, size, 0.0F, Vec3.ZERO, Vec3.ZERO, perFaceUvs(uvs)))));
        assertNoErrors(parsed);

        Box inflated = Box.of(origin, size, 0.25F);
        assertMatchesAuthoringTool(parsed.mesh("body"), inflated, Vec3.ZERO, inflated.centre(), uvs,
                "bone-level inflation");
    }

    // ---------------------------------------------------------------- several cubes, box UV

    @Test
    void severalCubesSharingOneTextureKeepTheirOwnRectangles() {
        // Two cubes of different sizes and places in one bone, sampling different parts of the same texture. Neither may
        // borrow the other's rectangle, and neither may sample past the end of its own.
        Map<String, float[]> longCube = new LinkedHashMap<>();
        longCube.put("north", new float[]{2.0F, 2.0F, 4.0F, 2.0F});
        longCube.put("east", new float[]{6.0F, 2.0F, 12.0F, 2.0F});
        longCube.put("south", new float[]{2.0F, 2.0F, 4.0F, 2.0F});
        longCube.put("west", new float[]{6.0F, 4.0F, 12.0F, 2.0F});
        longCube.put("up", new float[]{18.0F, 2.0F, 4.0F, 12.0F});
        longCube.put("down", new float[]{22.0F, 2.0F, 4.0F, 12.0F});
        Map<String, float[]> shortCube = new LinkedHashMap<>();
        shortCube.put("north", new float[]{40.0F, 60.0F, 2.0F, 6.0F});
        shortCube.put("east", new float[]{42.0F, 60.0F, 8.0F, 6.0F});
        shortCube.put("south", new float[]{50.0F, 60.0F, 2.0F, 6.0F});
        shortCube.put("west", new float[]{52.0F, 60.0F, 8.0F, 6.0F});
        shortCube.put("up", new float[]{60.0F, 60.0F, 2.0F, 8.0F});
        shortCube.put("down", new float[]{62.0F, 60.0F, 2.0F, 8.0F});

        Vec3 longOrigin = new Vec3(-1.0F, 2.0F, -6.0F);
        Vec3 longSize = new Vec3(2.0F, 2.0F, 12.0F);
        Vec3 shortOrigin = new Vec3(3.0F, 4.0F, 0.0F);
        Vec3 shortSize = new Vec3(2.0F, 6.0F, 8.0F);
        Parsed parsed = parse(geometry(bone("body", null, null,
                cube(longOrigin, longSize, 0.0F, Vec3.ZERO, Vec3.ZERO, perFaceUvs(longCube)),
                cube(shortOrigin, shortSize, 0.0F, Vec3.ZERO, Vec3.ZERO, perFaceUvs(shortCube)))));
        assertNoErrors(parsed);

        BoneMesh mesh = parsed.mesh("body");
        CornerIndex index = new CornerIndex(mesh, "two cubes");
        Box longBox = Box.of(longOrigin, longSize, 0.0F);
        Box shortBox = Box.of(shortOrigin, shortSize, 0.0F);
        assertCube(index, longBox, Vec3.ZERO, longBox.centre(), longCube, "long cube");
        assertCube(index, shortBox, Vec3.ZERO, shortBox.centre(), shortCube, "short cube");
        index.assertFullyConsumed();
    }

    @Test
    void aBoxMappedCubeSamplesTheUnwrapExactly() {
        // The [u, v] form is the authoring tool's own box unwrap: four panels in a horizontal strip with the top and
        // bottom panels above them, the two of which are 180 degrees apart.
        Vec3 origin = new Vec3(0.0F, 0.0F, 0.0F);
        Vec3 size = new Vec3(4.0F, 6.0F, 10.0F);
        Parsed parsed = parse(geometry(bone("body", null, null,
                cube(origin, size, 0.0F, Vec3.ZERO, Vec3.ZERO, boxUv(8.0F, 4.0F)))));
        assertNoErrors(parsed);

        BoneMesh mesh = parsed.mesh("body");
        CornerIndex index = new CornerIndex(mesh, "box-mapped cube");
        Box box = Box.of(origin, size, 0.0F);
        assertBoxCube(index, box, Vec3.ZERO, box.centre(), 8.0F, 4.0F, size, "box-mapped cube");
        index.assertFullyConsumed();
    }

    // ---------------------------------------------------------------- the shipped regression case

    @Test
    void everyCubeOfTheShippedShotgunMapsItsTextureLikeTheAuthoringTool() throws Exception {
        JsonObject root = readJson(SPAS12);
        ValidationReport report = new ValidationReport();
        WeaponModel model = BedrockGeometryParser.parse("arg_default_pack",
                new ResourceId("arg_pack", "models/weapons/shotguns/spas12/spas12"), SPAS12, root, report);
        assertNotNull(model, () -> "the shipped shotgun must parse: " + report.diagnostics());
        assertTrue(report.diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.severity() == PackDiagnostic.Severity.ERROR),
                "the shipped shotgun must parse without errors: " + report.diagnostics());
        assertEquals(128, model.textureWidth(), "the shipped geometry declares a 128 texel atlas");
        assertEquals(128, model.textureHeight(), "the shipped geometry declares a 128 texel atlas");

        Map<String, BoneMesh> meshes = meshesByBone(model);
        int cubes = 0;
        int rotated = 0;
        int faces = 0;
        for (JsonElement geometry : root.get(BedrockGeometryParser.GEOMETRY_KEY).getAsJsonArray()) {
            for (JsonElement boneElement : geometry.getAsJsonObject().getAsJsonArray("bones")) {
                JsonObject bone = boneElement.getAsJsonObject();
                if (!bone.has("cubes")) {
                    continue;
                }
                String boneName = bone.has("name") ? bone.get("name").getAsString() : "";
                BoneMesh mesh = meshes.get(boneName);
                assertNotNull(mesh, "bone '" + boneName + "' must have produced a cube mesh");
                CornerIndex index = new CornerIndex(mesh, boneName);
                for (JsonElement cubeElement : bone.getAsJsonArray("cubes")) {
                    JsonObject cube = cubeElement.getAsJsonObject();
                    Box box = Box.of(vec3(cube.get("origin")), vec3(cube.get("size")), inflate(cube, bone));
                    Vec3 rotation = cube.has("rotation") ? vec3(cube.get("rotation")) : Vec3.ZERO;
                    Vec3 pivot = cube.has("pivot") ? vec3(cube.get("pivot")) : box.centre();
                    JsonElement uv = cube.get("uv");
                    String description = "bone '" + boneName + "' cube " + cubes;
                    if (uv != null && uv.isJsonObject()) {
                        Map<String, float[]> perFace = readPerFaceUvs(uv.getAsJsonObject());
                        assertCube(index, box, rotation, pivot, perFace, description);
                        faces += perFace.size();
                    } else if (uv != null && uv.isJsonArray()) {
                        JsonArray array = uv.getAsJsonArray();
                        assertBoxCube(index, box, rotation, pivot, array.get(0).getAsFloat(),
                                array.get(1).getAsFloat(), vec3(cube.get("size")), description);
                        faces += 6;
                    } else {
                        continue;
                    }
                    cubes++;
                    if (!rotation.isZero()) {
                        rotated++;
                    }
                }
                index.assertFullyConsumed();
            }
        }

        assertTrue(cubes > 600, "the shipped shotgun must be the whole regression case; only " + cubes + " cubes seen");
        assertTrue(rotated > 500, "the shipped shotgun must exercise rotated cubes; only " + rotated + " rotated");
        assertTrue(faces > 3600, "every cube must have been checked face by face; only " + faces + " faces seen");
    }

    // ---------------------------------------------------------------- expected side

    /** Asserts that one cube's mesh samples the authoring tool's texel at every corner of every face it declares. */
    private static void assertMatchesAuthoringTool(BoneMesh mesh, Box box, Vec3 rotation, Vec3 pivot,
                                                   Map<String, float[]> perFaceUvs, String description) {
        CornerIndex index = new CornerIndex(mesh, description);
        assertCube(index, box, rotation, pivot, perFaceUvs, description);
        index.assertFullyConsumed();
    }

    /** Adds one per-face UV cube to the index and asserts it, consuming the corners it explains. */
    private static void assertCube(CornerIndex index, Box box, Vec3 rotation, Vec3 pivot,
                                   Map<String, float[]> perFaceUvs, String description) {
        for (Map.Entry<String, float[]> entry : perFaceUvs.entrySet()) {
            String face = entry.getKey();
            float[] rect = entry.getValue();
            assertFace(index, description, face, box, rotation, pivot,
                    BlockbenchCubeUvReference.perFaceTexels(face, rect[0], rect[1], rect[2], rect[3]));
        }
    }

    /** Adds one box-mapped cube to the index and asserts it, consuming the corners it explains. */
    private static void assertBoxCube(CornerIndex index, Box box, Vec3 rotation, Vec3 pivot, float u, float v,
                                      Vec3 size, String description) {
        for (String face : BlockbenchCubeUvReference.FACES) {
            assertFace(index, description, face, box, rotation, pivot,
                    BlockbenchCubeUvReference.boxTexels(face, u, v, size.x(), size.y(), size.z()));
        }
    }

    /** Asserts the four corner/texel pairs of one face. */
    private static void assertFace(CornerIndex index, String description, String face, Box box, Vec3 rotation,
                                   Vec3 pivot, List<float[]> expected) {
        Vec3 normal = rotateDirection(axisNormal(face), rotation);
        List<Vec3> corners = BlockbenchCubeUvReference.faceCorners(face, box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ());
        for (int corner = 0; corner < 4; corner++) {
            Vec3 position = rotate(corners.get(corner), rotation, pivot);
            float[] texel = expected.get(corner);
            float[] actual = index.take(position, normal);
            String where = description + ": face '" + face + "', corner " + corner + " at " + describe(position);
            assertNotNull(actual, where + " does not exist in the mesh; expected the texel " + describeTexel(texel));
            assertEquals(texel[0] / TEXTURE_SIZE, actual[0], TOLERANCE,
                    where + " must sample u = " + texel[0] + " texels, not " + actual[0] * TEXTURE_SIZE);
            assertEquals(texel[1] / TEXTURE_SIZE, actual[1], TOLERANCE,
                    where + " must sample v = " + texel[1] + " texels, not " + actual[1] * TEXTURE_SIZE);
        }
    }

    /**
     * @param mesh        the cube mesh
     * @param box         the box the cube occupies
     * @param perFaceUvs  the faces the cube declares, with their authored rectangle
     * @return the texel each of those faces' four corners samples, as sorted {@code face|u|v} strings
     */
    private static List<String> texels(BoneMesh mesh, Box box, Map<String, float[]> perFaceUvs) {
        CornerIndex index = new CornerIndex(mesh, "texels");
        List<String> texels = new ArrayList<>();
        for (String face : BlockbenchCubeUvReference.FACES) {
            if (!perFaceUvs.containsKey(face)) {
                continue;
            }
            for (Vec3 corner : BlockbenchCubeUvReference.faceCorners(face, box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ())) {
                float[] uv = index.take(corner, axisNormal(face));
                assertNotNull(uv, "face '" + face + "' must have a corner at " + describe(corner));
                texels.add(face + "|" + texel(uv[0]) + "|" + texel(uv[1]));
            }
        }
        texels.sort(null);
        return texels;
    }

    /** @return six distinct, asymmetric rectangles, one per face. */
    private static Map<String, float[]> distinctRectangles() {
        Map<String, float[]> uvs = new LinkedHashMap<>();
        uvs.put("north", new float[]{4.0F, 8.0F, 6.0F, 10.0F});
        uvs.put("east", new float[]{12.0F, 8.0F, 3.0F, 9.0F});
        uvs.put("south", new float[]{20.0F, 8.0F, 6.0F, 10.0F});
        uvs.put("west", new float[]{28.0F, 8.0F, 3.0F, 9.0F});
        uvs.put("up", new float[]{36.0F, 4.0F, 6.0F, 3.0F});
        uvs.put("down", new float[]{44.0F, 4.0F, 6.0F, 3.0F});
        return uvs;
    }

    private static String texel(float normalised) {
        return String.format(java.util.Locale.ROOT, "%.3f", normalised * TEXTURE_SIZE);
    }

    private static String describeTexel(float[] texel) {
        return "(" + texel[0] + ", " + texel[1] + ") texels";
    }

    private static String describe(Vec3 point) {
        return "(" + point.x() + ", " + point.y() + ", " + point.z() + ")";
    }

    // ---------------------------------------------------------------- the authoring tool's own arithmetic

    /**
     * Where the authoring tool puts one point of a cube after the cube's own rotation: the triple is displayed with X
     * and Y negated, composed as one {@code Rz * Ry * Rx} matrix about the displayed pivot, and mirrored back into the
     * model space of the file.
     */
    private static Vec3 rotate(Vec3 point, Vec3 rotation, Vec3 pivot) {
        if (rotation.isZero()) {
            return point;
        }
        Vec3 displayedPivot = BlockbenchTransformReference.displayedPosition(pivot);
        Vec3 displayed = BlockbenchTransformReference.displayedPosition(point);
        double[] result = BlockbenchTransformReference.multiply(matrix(rotation),
                new double[]{displayed.x() - displayedPivot.x(), displayed.y() - displayedPivot.y(),
                        displayed.z() - displayedPivot.z(), 1.0});
        Vec3 rotated = new Vec3((float) (result[0] + displayedPivot.x()),
                (float) (result[1] + displayedPivot.y()), (float) (result[2] + displayedPivot.z()));
        return BlockbenchTransformReference.displayedPosition(rotated);
    }

    /** @return the direction a face normal points after the cube's own rotation. */
    private static Vec3 rotateDirection(Vec3 direction, Vec3 rotation) {
        if (rotation.isZero()) {
            return direction;
        }
        Vec3 displayed = BlockbenchTransformReference.displayedPosition(direction);
        double[] result = BlockbenchTransformReference.multiply(matrix(rotation),
                new double[]{displayed.x(), displayed.y(), displayed.z(), 1.0});
        return BlockbenchTransformReference.displayedPosition(
                new Vec3((float) result[0], (float) result[1], (float) result[2]));
    }

    private static double[][] matrix(Vec3 rotation) {
        return BlockbenchTransformReference.zyx(BlockbenchTransformReference.displayedRotation(rotation));
    }

    /** @return the outward normal of an unrotated face. */
    private static Vec3 axisNormal(String face) {
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

    /** The box one cube occupies, resolved the way the authoring tool resolves it: inflation on every side. */
    private record Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {

        static Box of(Vec3 origin, Vec3 size, float inflate) {
            return new Box(
                    Math.min(origin.x(), origin.x() + size.x()) - inflate,
                    Math.min(origin.y(), origin.y() + size.y()) - inflate,
                    Math.min(origin.z(), origin.z() + size.z()) - inflate,
                    Math.max(origin.x(), origin.x() + size.x()) + inflate,
                    Math.max(origin.y(), origin.y() + size.y()) + inflate,
                    Math.max(origin.z(), origin.z() + size.z()) + inflate);
        }

        /** @return the box's centre, which is the default rotation pivot of a cube. */
        Vec3 centre() {
            return new Vec3((minX + maxX) / 2.0F, (minY + maxY) / 2.0F, (minZ + maxZ) / 2.0F);
        }
    }

    // ---------------------------------------------------------------- fixtures

    @SafeVarargs
    private static JsonObject geometry(JsonObject... bones) {
        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry.arg_weaponry_uv_test");
        description.addProperty("texture_width", TEXTURE_SIZE);
        description.addProperty("texture_height", TEXTURE_SIZE);

        JsonObject geometry = new JsonObject();
        geometry.add("description", description);
        JsonArray boneArray = new JsonArray();
        for (JsonObject bone : bones) {
            boneArray.add(bone);
        }
        geometry.add("bones", boneArray);

        JsonArray geometries = new JsonArray();
        geometries.add(geometry);
        JsonObject root = new JsonObject();
        root.addProperty("format_version", "1.12.0");
        root.add(BedrockGeometryParser.GEOMETRY_KEY, geometries);
        return root;
    }

    private static JsonObject bone(String name, String parent, Float inflate, JsonObject... cubes) {
        JsonObject bone = new JsonObject();
        bone.addProperty("name", name);
        if (parent != null) {
            bone.addProperty("parent", parent);
        }
        if (inflate != null) {
            bone.addProperty("inflate", inflate);
        }
        JsonArray cubeArray = new JsonArray();
        for (JsonObject cube : cubes) {
            cubeArray.add(cube);
        }
        bone.add("cubes", cubeArray);
        return bone;
    }

    private static JsonObject cube(Vec3 origin, Vec3 size, float inflate, Vec3 rotation, Vec3 pivot,
                                   JsonElement uv) {
        JsonObject cube = new JsonObject();
        cube.add("origin", array(origin));
        cube.add("size", array(size));
        if (inflate != 0.0F) {
            cube.addProperty("inflate", inflate);
        }
        if (!rotation.isZero()) {
            cube.add("rotation", array(rotation));
        }
        if (!pivot.isZero()) {
            cube.add("pivot", array(pivot));
        }
        cube.add("uv", uv);
        return cube;
    }

    /** @return the per-face {@code {uv, uv_size}} object the format uses for a cube with individual face UVs. */
    private static JsonObject perFaceUvs(Map<String, float[]> uvs) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, float[]> entry : uvs.entrySet()) {
            JsonObject face = new JsonObject();
            face.add("uv", pair(entry.getValue()[0], entry.getValue()[1]));
            face.add("uv_size", pair(entry.getValue()[2], entry.getValue()[3]));
            object.add(entry.getKey(), face);
        }
        return object;
    }

    /** @return the authored {@code {uv[0], uv[1], uv_size[0], uv_size[1]}} of every face the object declares. */
    private static Map<String, float[]> readPerFaceUvs(JsonObject uv) {
        Map<String, float[]> uvs = new LinkedHashMap<>();
        for (String face : BlockbenchCubeUvReference.FACES) {
            if (!uv.has(face)) {
                continue;
            }
            JsonObject faceObject = uv.getAsJsonObject(face);
            JsonArray offset = faceObject.getAsJsonArray("uv");
            JsonArray size = faceObject.getAsJsonArray("uv_size");
            uvs.put(face, new float[]{offset.get(0).getAsFloat(), offset.get(1).getAsFloat(),
                    size.get(0).getAsFloat(), size.get(1).getAsFloat()});
        }
        return uvs;
    }

    /** @return the box-mapped {@code [u, v]} form of a cube's UV. */
    private static JsonArray boxUv(float u, float v) {
        return pair(u, v);
    }

    private static JsonArray pair(float first, float second) {
        JsonArray array = new JsonArray();
        array.add(first);
        array.add(second);
        return array;
    }

    private static JsonArray array(Vec3 vector) {
        JsonArray array = new JsonArray();
        array.add(vector.x());
        array.add(vector.y());
        array.add(vector.z());
        return array;
    }

    private static Vec3 vec3(JsonElement element) {
        JsonArray array = element.getAsJsonArray();
        return new Vec3(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
    }

    /** @return the inflation the parser applies to one cube: its own, or the bone's when it declares none. */
    private static float inflate(JsonObject cube, JsonObject bone) {
        if (cube.has("inflate")) {
            return cube.get("inflate").getAsFloat();
        }
        return bone.has("inflate") ? bone.get("inflate").getAsFloat() : 0.0F;
    }

    // ---------------------------------------------------------------- parsing

    /** The parsed model, its diagnostics and its cube meshes by bone name. */
    private record Parsed(WeaponModel model, ValidationReport report, Map<String, BoneMesh> meshes) {

        /** @return the cube mesh of one bone. */
        BoneMesh mesh(String bone) {
            BoneMesh mesh = meshes.get(bone);
            assertNotNull(mesh, "bone '" + bone + "' must have produced a cube mesh");
            return mesh;
        }
    }

    private static Parsed parse(JsonObject root) {
        ValidationReport report = new ValidationReport();
        WeaponModel model = BedrockGeometryParser.parse("arg_default_pack",
                new ResourceId("arg_pack", "models/test_cube"), "models/test_cube.json", root, report);
        assertNotNull(model, () -> "the test model must parse: " + report.diagnostics());
        return new Parsed(model, report, meshesByBone(model));
    }

    private static void assertNoErrors(Parsed parsed) {
        assertTrue(parsed.report().diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.severity() == PackDiagnostic.Severity.ERROR),
                "the test model must parse without errors: " + parsed.report().diagnostics());
    }

    private static Map<String, BoneMesh> meshesByBone(WeaponModel model) {
        Map<String, BoneMesh> meshes = new LinkedHashMap<>();
        for (ModelBone bone : model.bonesInOrder()) {
            for (BoneMesh mesh : bone.meshes()) {
                if (mesh.source() == BoneMesh.Source.CUBE) {
                    meshes.put(bone.name(), mesh);
                    break;
                }
            }
        }
        return meshes;
    }

    private static JsonObject readJson(String relativePath) throws Exception {
        Path file = packRoot().resolve(MODELS_DIRECTORY).resolve(relativePath);
        JsonElement root = JsoncReader.parse(Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(root.isJsonObject(), file + " must contain a JSON object");
        return root.getAsJsonObject();
    }

    private static Path packRoot() throws Exception {
        URL url = BedrockCubeUvMappingTest.class.getClassLoader().getResource(PACK_DIRECTORY);
        if (url != null && "file".equals(url.getProtocol())) {
            return Path.of(url.toURI());
        }
        Path fallback = Path.of("src/main/resources").resolve(PACK_DIRECTORY);
        assertTrue(Files.isDirectory(fallback), "the bundled content pack must be reachable from the test");
        return fallback;
    }

    // ---------------------------------------------------------------- corner index

    /**
     * The corners of one parsed mesh, indexed by position so an expected corner can be found with a tolerance.
     *
     * <p>The expected positions come from a double-precision evaluation of the authoring tool's transform while the mesh
     * holds the parser's single-precision result, so the two never match bit for bit. Positions are therefore bucketed on
     * a grid much coarser than that difference and a lookup probes the neighbouring cells, which also covers a corner
     * that happens to sit on a cell boundary. Corners that share a position - an edge of the box, or two coincident
     * cubes - are told apart by their normal, and a corner is consumed at most once, so the mesh has to provide exactly
     * one corner per expectation.</p>
     */
    private static final class CornerIndex {

        /** Grid size in model units of the position buckets. */
        private static final float CELL = 0.05F;

        private final String description;
        private final float[] positions;
        private final float[] normals;
        private final float[] uvs;
        private final boolean[] used;
        private final Map<String, List<Integer>> buckets = new HashMap<>();

        CornerIndex(BoneMesh mesh, String description) {
            this.description = description;
            this.positions = mesh.positionsUnsafe();
            this.normals = mesh.normalsUnsafe();
            this.uvs = mesh.uvsUnsafe();
            int count = positions.length / 3;
            this.used = new boolean[count];
            for (int corner = 0; corner < count; corner++) {
                String key = key(cell(positions[corner * 3]), cell(positions[corner * 3 + 1]),
                        cell(positions[corner * 3 + 2]));
                buckets.computeIfAbsent(key, existing -> new ArrayList<>()).add(corner);
            }
        }

        /**
         * Consumes the corner at the given position that carries the given normal.
         *
         * @return the UV of that corner, or {@code null} when the mesh has no such corner left
         */
        float[] take(Vec3 position, Vec3 normal) {
            int x = cell(position.x());
            int y = cell(position.y());
            int z = cell(position.z());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Integer> candidates = buckets.get(key(x + dx, y + dy, z + dz));
                        if (candidates == null) {
                            continue;
                        }
                        for (int corner : candidates) {
                            if (used[corner] || !at(position, corner) || !along(normal, corner)) {
                                continue;
                            }
                            used[corner] = true;
                            return new float[]{uvs[corner * 2], uvs[corner * 2 + 1]};
                        }
                    }
                }
            }
            return null;
        }

        /** Asserts that the authored UVs explained every corner of the mesh. */
        void assertFullyConsumed() {
            int remaining = 0;
            for (boolean consumed : used) {
                if (!consumed) {
                    remaining++;
                }
            }
            assertEquals(0, remaining, description + ": " + remaining + " corner(s) of the mesh are not explained "
                    + "by the authored UVs");
        }

        private boolean at(Vec3 position, int corner) {
            return Math.abs(positions[corner * 3] - position.x()) <= TOLERANCE
                    && Math.abs(positions[corner * 3 + 1] - position.y()) <= TOLERANCE
                    && Math.abs(positions[corner * 3 + 2] - position.z()) <= TOLERANCE;
        }

        private boolean along(Vec3 normal, int corner) {
            return Math.abs(normals[corner * 3] - normal.x()) <= TOLERANCE
                    && Math.abs(normals[corner * 3 + 1] - normal.y()) <= TOLERANCE
                    && Math.abs(normals[corner * 3 + 2] - normal.z()) <= TOLERANCE;
        }

        private static int cell(float value) {
            return Math.round(value / CELL);
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }
}
