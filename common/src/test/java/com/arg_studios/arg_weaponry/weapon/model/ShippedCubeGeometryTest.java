package com.arg_studios.arg_weaponry.weapon.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The shipped Bedrock geometry, parsed by the real parser and compared with the authoring tool's own transform.
 *
 * <p>This is the regression guard for the cube path. A Bedrock cube may carry its own {@code pivot} and
 * {@code rotation}, and both have to be interpreted exactly like the authoring tool interprets them: the triple is shown
 * with X and Y negated (Blockbench's {@code bedrock} codec) and the rotation is one {@code Rz * Ry * Rx} matrix about the
 * cube's pivot. The expected side below is computed only from that rule - it never calls the parser's rotation code - so
 * a wrong sign, a wrong order or a missing conversion cannot pass.</p>
 *
 * <p>No animation, pose or renderer code is involved: the geometry is checked right after parsing, which is why this test
 * also answers "does the problem exist with animations disabled" - it does, because the vertices are already wrong before
 * any pose is applied.</p>
 */
class ShippedCubeGeometryTest {

    private static final String PACK_DIRECTORY = "assets/arg_weaponry/weapon_packs/arg_default_pack";
    private static final String MODELS_DIRECTORY = "assets/arg_pack/models/weapons";
    private static final String SPAS12 = "shotguns/spas12/spas12.json";
    private static final String DMR = "dmrs/m100_dmr/m100_dmr.json";

    /** Tolerance in model units for a corner comparison. */
    private static final float TOLERANCE = 1.0E-3F;

    @Test
    void everyCubeOfTheShippedShotgunMatchesTheAuthoringTransform() throws Exception {
        Model parsed = parse(SPAS12);

        assertTrue(parsed.rotatedCubes() > 500,
                "the shipped shotgun must exercise the rotated-cube path, otherwise this test proves nothing");

        assertCubesMatch(parsed, SPAS12);
    }

    @Test
    void theDmrIsUnaffectedBecauseItsCubesCarryNoRotation() throws Exception {
        Model parsed = parse(DMR);

        assertEquals(0, parsed.rotatedCubes(), "the DMR's cubes are unrotated, so the fix cannot move its geometry");
        assertCubesMatch(parsed, DMR);
    }

    @Test
    void aRotatedCubeStillHasUnitNormalsThatFollowItsGeometry() throws Exception {
        Model parsed = parse(SPAS12);

        int checked = 0;
        for (Cube cube : parsed.cubes()) {
            if (!cube.rotated()) {
                continue;
            }
            BoneMesh mesh = parsed.meshesByBone().get(cube.bone());
            assertNotNull(mesh, "bone '" + cube.bone() + "' must have produced a mesh");
            float[] normals = mesh.normalsUnsafe();
            for (int corner = 0; corner < normals.length / 3; corner++) {
                float x = normals[corner * 3];
                float y = normals[corner * 3 + 1];
                float z = normals[corner * 3 + 2];
                assertEquals(1.0F, (float) Math.sqrt(x * x + y * y + z * z), 1.0E-3F,
                        "a cube normal must stay unit length");
            }
            checked++;
        }
        assertTrue(checked > 500, "the normal check must see the rotated cubes");

        // The shading normal of a rotated cube has to be rotated with it: the displayed normals of the model must be the
        // rotated, mirrored face normals of the authored cubes - nothing else.
        Positions expected = new Positions();
        for (Cube cube : parsed.cubes()) {
            for (Vec3 normal : cube.displayedNormals()) {
                expected.add(normal);
            }
        }
        Positions actual = new Positions();
        for (BoneMesh mesh : parsed.meshesByBone().values()) {
            if (mesh.source() != BoneMesh.Source.CUBE) {
                continue;
            }
            float[] normals = mesh.normalsUnsafe();
            for (int corner = 0; corner < normals.length / 3; corner++) {
                actual.add(new Vec3(-normals[corner * 3], normals[corner * 3 + 1], normals[corner * 3 + 2]));
            }
        }
        assertTrue(actual.containsAll(expected),
                "every rotated cube's shading normal must point where its geometry points");
    }

    // ---------------------------------------------------------------- comparison

    private static void assertCubesMatch(Model parsed, String file) {
        Positions expected = new Positions();
        for (Cube cube : parsed.cubes()) {
            for (Vec3 corner : cube.displayedCorners()) {
                expected.add(corner);
            }
        }

        // Only cube meshes are compared: a model may also carry poly_mesh geometry, whose vertices are not cube corners.
        Positions actual = new Positions();
        for (BoneMesh mesh : parsed.meshesByBone().values()) {
            if (mesh.source() != BoneMesh.Source.CUBE) {
                continue;
            }
            float[] positions = mesh.positionsUnsafe();
            for (int corner = 0; corner < positions.length / 3; corner++) {
                actual.add(new Vec3(-positions[corner * 3], positions[corner * 3 + 1], positions[corner * 3 + 2]));
            }
        }

        assertFalse(expected.isEmpty(), file + " must declare cubes");
        assertTrue(actual.containsAll(expected), file + ": the parsed geometry must contain every authored cube corner"
                + " at the position the authoring tool puts it - " + actual.missingFrom(expected));
    }

    // ---------------------------------------------------------------- fixtures

    /** One authored cube, resolved into Blockbench's displayed space. */
    private record Cube(String bone, Vec3 pivot, Vec3 rotation, float minX, float minY, float minZ,
                        float maxX, float maxY, float maxZ, List<String> faces) {

        private boolean rotated() {
            return rotation != null && !rotation.isZero();
        }

        /**
         * @return the corners in Blockbench's displayed space that this cube actually contributes, i.e. the corners of the
         *         faces it declares. A cube may omit hidden faces - the shipped shotgun has cubes with three faces - and a
         *         corner that belongs to no declared face has no vertex at all. Each corner is the authored point mirrored
         *         with the rest of the model, one {@code Rz * Ry * Rx} rotation from the <em>displayed</em> triple about
         *         the <em>displayed</em> pivot, and nothing else: the whole rule the parser has to reproduce.
         */
        private List<Vec3> displayedCorners() {
            double[][] matrix = matrix();
            Vec3 displayedPivot = BlockbenchTransformReference.displayedPosition(pivot);
            List<Vec3> corners = new ArrayList<>(24);
            for (String face : faces) {
                for (Vec3 corner : faceCorners(face)) {
                    Vec3 displayed = BlockbenchTransformReference.displayedPosition(corner);
                    double[] local = {displayed.x() - displayedPivot.x(), displayed.y() - displayedPivot.y(),
                            displayed.z() - displayedPivot.z(), 1.0};
                    double[] result = BlockbenchTransformReference.multiply(matrix, local);
                    corners.add(new Vec3((float) (result[0] + displayedPivot.x()),
                            (float) (result[1] + displayedPivot.y()),
                            (float) (result[2] + displayedPivot.z())));
                }
            }
            return corners;
        }

        /**
         * @return the normals of the declared faces in Blockbench's displayed space. A normal is a direction, so it is
         *         mirrored and rotated by the same displayed triple, with no pivot and no translation involved.
         */
        private List<Vec3> displayedNormals() {
            double[][] matrix = matrix();
            List<Vec3> normals = new ArrayList<>(faces.size());
            for (String face : faces) {
                Vec3 normal = faceNormal(face);
                double[] mirrored = {-normal.x(), normal.y(), normal.z()};
                double[] result = BlockbenchTransformReference.multiply(matrix,
                        new double[]{mirrored[0], mirrored[1], mirrored[2], 1.0});
                normals.add(normalise(new Vec3((float) result[0], (float) result[1], (float) result[2])));
            }
            return normals;
        }

        private double[][] matrix() {
            return rotated()
                    ? BlockbenchTransformReference.zyx(BlockbenchTransformReference.displayedRotation(rotation))
                    : BlockbenchTransformReference.identity();
        }

        /** @return the four corners of one Bedrock face, in model space. */
        private List<Vec3> faceCorners(String face) {
            float[][] values = switch (face) {
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
                default -> new float[0][];
            };
            List<Vec3> corners = new ArrayList<>(4);
            for (float[] value : values) {
                corners.add(new Vec3(value[0], value[1], value[2]));
            }
            return corners;
        }

        private static Vec3 faceNormal(String face) {
            return switch (face) {
                case "north" -> new Vec3(0.0F, 0.0F, -1.0F);
                case "south" -> new Vec3(0.0F, 0.0F, 1.0F);
                case "east" -> new Vec3(1.0F, 0.0F, 0.0F);
                case "west" -> new Vec3(-1.0F, 0.0F, 0.0F);
                case "up" -> new Vec3(0.0F, 1.0F, 0.0F);
                default -> new Vec3(0.0F, -1.0F, 0.0F);
            };
        }

        private static Vec3 normalise(Vec3 vector) {
            float length = (float) Math.sqrt(vector.x() * vector.x() + vector.y() * vector.y()
                    + vector.z() * vector.z());
            if (length < 1.0E-6F) {
                return vector;
            }
            return new Vec3(vector.x() / length, vector.y() / length, vector.z() / length);
        }
    }

    /**
     * A tolerance-aware set of points.
     *
     * <p>Comparing floats by exact string keys fails on values that sit exactly on a rounding boundary, and a rotated
     * corner is such a value. Points are therefore bucketed into cells four times the tolerance and a lookup checks the
     * 27 neighbouring cells with the real distance.</p>
     */
    private static final class Positions {

        private static final float CELL = TOLERANCE * 4.0F;

        private final Map<String, List<Vec3>> cells = new LinkedHashMap<>();

        private void add(Vec3 point) {
            cells.computeIfAbsent(cell(point), key -> new ArrayList<>()).add(point);
        }

        private boolean isEmpty() {
            return cells.isEmpty();
        }

        private boolean containsAll(Positions other) {
            return missingFrom(other) == null;
        }

        /**
         * @return a description of the worst point of {@code other} that this set does not contain, or {@code null} when
         *         every point is present
         */
        private String missingFrom(Positions other) {
            Vec3 worstPoint = null;
            float worstDistance = 0.0F;
            for (List<Vec3> points : other.cells.values()) {
                for (Vec3 point : points) {
                    float nearest = nearestDistance(point);
                    if (nearest > TOLERANCE && nearest > worstDistance) {
                        worstDistance = nearest;
                        worstPoint = point;
                    }
                }
            }
            return worstPoint == null ? null
                    : "the point (" + worstPoint.x() + ", " + worstPoint.y() + ", " + worstPoint.z()
                            + ") is missing; the nearest parsed point is " + worstDistance + " model units away";
        }

        private float nearestDistance(Vec3 point) {
            float nearest = Float.MAX_VALUE;
            int baseX = Math.round(point.x() / CELL);
            int baseY = Math.round(point.y() / CELL);
            int baseZ = Math.round(point.z() / CELL);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Vec3> bucket = cells.get(key(baseX + dx, baseY + dy, baseZ + dz));
                        if (bucket == null) {
                            continue;
                        }
                        for (Vec3 candidate : bucket) {
                            nearest = Math.min(nearest, distance(point, candidate));
                        }
                    }
                }
            }
            return nearest;
        }

        private boolean contains(Vec3 point) {
            int baseX = Math.round(point.x() / CELL);
            int baseY = Math.round(point.y() / CELL);
            int baseZ = Math.round(point.z() / CELL);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Vec3> bucket = cells.get(key(baseX + dx, baseY + dy, baseZ + dz));
                        if (bucket == null) {
                            continue;
                        }
                        for (Vec3 candidate : bucket) {
                            if (distance(point, candidate) <= TOLERANCE) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }

        private static float distance(Vec3 first, Vec3 second) {
            float dx = first.x() - second.x();
            float dy = first.y() - second.y();
            float dz = first.z() - second.z();
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        private static String cell(Vec3 point) {
            return key(Math.round(point.x() / CELL), Math.round(point.y() / CELL), Math.round(point.z() / CELL));
        }

        private static String key(int x, int y, int z) {
            return x + ":" + y + ":" + z;
        }
    }

    /** The parsed model plus the authored cube list it came from. */
    private record Model(List<Cube> cubes, Map<String, BoneMesh> meshesByBone, int rotatedCubes) {
    }

    private static Model parse(String relativePath) throws Exception {
        JsonObject root = readJson(relativePath);
        ValidationReport report = new ValidationReport();
        WeaponModel model = BedrockGeometryParser.parse("arg_default_pack",
                new ResourceId("arg_pack", relativePath.substring(0, relativePath.length() - ".json".length())),
                relativePath, root, report);

        assertNotNull(model, "the shipped model must parse: " + report.diagnostics());
        assertTrue(report.diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.severity() == PackDiagnostic.Severity.ERROR),
                "the shipped model must parse without errors: " + report.diagnostics());

        Map<String, BoneMesh> meshes = new LinkedHashMap<>();
        for (ModelBone bone : model.bonesInOrder()) {
            for (BoneMesh mesh : bone.meshes()) {
                if (mesh.source() == BoneMesh.Source.CUBE) {
                    meshes.put(bone.name(), mesh);
                    break;
                }
            }
        }

        List<Cube> cubes = new ArrayList<>();
        int rotated = 0;
        for (JsonElement geometry : root.get(BedrockGeometryParser.GEOMETRY_KEY).getAsJsonArray()) {
            for (JsonElement boneElement : geometry.getAsJsonObject().getAsJsonArray("bones")) {
                JsonObject bone = boneElement.getAsJsonObject();
                if (!bone.has("cubes")) {
                    continue;
                }
                String boneName = bone.has("name") ? bone.get("name").getAsString() : "";
                for (JsonElement cubeElement : bone.getAsJsonArray("cubes")) {
                    Cube cube = cube(boneName, cubeElement.getAsJsonObject());
                    cubes.add(cube);
                    if (cube.rotated()) {
                        rotated++;
                    }
                }
            }
        }
        return new Model(List.copyOf(cubes), Map.copyOf(meshes), rotated);
    }

    private static Cube cube(String bone, JsonObject cube) {
        float[] origin = vector(cube.get("origin"));
        float[] size = vector(cube.get("size"));
        float inflate = cube.has("inflate") ? cube.get("inflate").getAsFloat() : 0.0F;
        float minX = Math.min(origin[0], origin[0] + size[0]) - inflate;
        float minY = Math.min(origin[1], origin[1] + size[1]) - inflate;
        float minZ = Math.min(origin[2], origin[2] + size[2]) - inflate;
        float maxX = Math.max(origin[0], origin[0] + size[0]) + inflate;
        float maxY = Math.max(origin[1], origin[1] + size[1]) + inflate;
        float maxZ = Math.max(origin[2], origin[2] + size[2]) + inflate;
        Vec3 pivot = cube.has("pivot") ? vec3(cube.get("pivot"))
                : new Vec3((minX + maxX) / 2.0F, (minY + maxY) / 2.0F, (minZ + maxZ) / 2.0F);
        Vec3 rotation = cube.has("rotation") ? vec3(cube.get("rotation")) : Vec3.ZERO;
        return new Cube(bone, pivot, rotation, minX, minY, minZ, maxX, maxY, maxZ, faces(cube));
    }

    /**
     * @param cube the authored cube
     * @return the faces the cube declares a UV for; a box-mapped {@code [u, v]} cube declares all six, because the format
     *         maps the whole box in that form
     */
    private static List<String> faces(JsonObject cube) {
        JsonElement uv = cube.get("uv");
        if (uv == null || !uv.isJsonObject()) {
            return List.of("north", "east", "south", "west", "up", "down");
        }
        List<String> faces = new ArrayList<>(6);
        for (String face : List.of("north", "east", "south", "west", "up", "down")) {
            if (uv.getAsJsonObject().has(face)) {
                faces.add(face);
            }
        }
        return faces;
    }

    private static float[] vector(JsonElement element) {
        JsonArray array = element.getAsJsonArray();
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    private static Vec3 vec3(JsonElement element) {
        float[] values = vector(element);
        return new Vec3(values[0], values[1], values[2]);
    }

    private static JsonObject readJson(String relativePath) throws Exception {
        Path file = packRoot().resolve(MODELS_DIRECTORY).resolve(relativePath);
        JsonElement root = JsoncReader.parse(Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(root.isJsonObject(), file + " must contain a JSON object");
        return root.getAsJsonObject();
    }

    private static Path packRoot() throws Exception {
        URL url = ShippedCubeGeometryTest.class.getClassLoader().getResource(PACK_DIRECTORY);
        if (url != null && "file".equals(url.getProtocol())) {
            return Path.of(url.toURI());
        }
        Path fallback = Path.of("src/main/resources").resolve(PACK_DIRECTORY);
        assertTrue(Files.isDirectory(fallback), "the bundled content pack must be reachable from the test");
        return fallback;
    }
}
