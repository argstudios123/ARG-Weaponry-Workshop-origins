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


/**
 * Parses the Bedrock geometry format used by ARG weapon models, including Meshy-generated {@code poly_mesh} geometry.
 *
 * <p>The parser is deliberately faithful: real vertex positions, normals, UVs, polygon topology, bone hierarchy,
 * pivots and rotations are preserved. Polymesh geometry is never approximated with cubes, no polygon is dropped
 * silently, and constructs that cannot be applied correctly are reported instead of guessed at.</p>
 *
 * <p>What this class does <em>not</em> do is build geometry. That is the job of the two geometry layers, which are
 * independent of each other and of everything above them:</p>
 * <ul>
 *   <li>{@link BedrockCubeGeometry} - a {@code cubes} array to a {@link BoneMesh}, including box and per-face UVs,
 *   inflation and a cube's own rotation;</li>
 *   <li>{@link PolyMeshGeometry} - a {@code poly_mesh} block to a {@link BoneMesh}, including its flat shading and its
 *   UV origin.</li>
 * </ul>
 * <p>Both report problems through a {@link GeometryDiagnostics} sink, so a layer holds no report, no pack id and no file
 * name; this class owns the {@link com.arg_studios.arg_weaponry.core.ValidationReport}, the JSON pointers and the
 * counters. The single value they hand back is a {@link GeometryBlock}. A bone therefore ends up with a list of
 * {@link BoneMesh} entries - of either kind, in declaration order - and nothing downstream assumes one mesh per bone.</p>
 *
 * <h2>Supported geometry</h2>
 * <ul>
 *     <li>{@code poly_mesh} with {@code positions}/{@code normals}/{@code uvs}/{@code polys} and
 *     {@code normalized_uvs}, as one object or as an array of them on one bone;</li>
 *     <li>{@code cubes} with {@code origin}/{@code size}, per-face or box {@code uv}, {@code uv_size} including negative
 *     values, a cube-level <em>or bone-level</em> {@code inflate}, and the cube's own {@code rotation} about its
 *     {@code pivot};</li>
 *     <li>transform-only bones and animation-target bones such as {@code camera} (no geometry required).</li>
 * </ul>
 *
 * <p>Reported rather than applied: {@code mirror} (on a cube or on a bone), per-face {@code uv_rotation},
 * {@code locators} exposure and any unknown key. Nothing here touches GPU resources; the result is pure CPU-side
 * data.</p>
 *
 * <h2>Coordinate spaces</h2>
 * <p>Every vertex this parser emits is in <em>model space</em> - the space of the file itself - and stays there. The
 * renderer owns the one conversion into Minecraft's handedness, a single {@code scale(-1, 1, 1)} applied outside the
 * whole assembly (see {@code ViewBoneTransform}), and the bone hierarchy is applied as {@code PoseStack} transforms
 * around the authored pivots. The one exception is a cube's own rotation, which is baked into the vertices at parse
 * time; it therefore has to be expressed in the space the renderer composes in, which is why it goes through
 * {@link RotationConvention#toRenderSpace(Vec3)} and {@link BedrockRotation} exactly like a bone rotation does.</p>
 */
public final class BedrockGeometryParser {

    /** The geometry-array key used by the format. */
    public static final String GEOMETRY_KEY = "minecraft:geometry";

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

    /** The JSON reader and the diagnostics channel the two geometry layers share. */
    private final GeometryJson json;
    private final GeometryDiagnostics diagnostics;

    private BedrockGeometryParser(String packId, ResourceId resourceId, String sourceName, ValidationReport report) {
        this.packId = packId;
        this.resourceId = resourceId;
        this.sourceName = sourceName;
        this.report = report;
        this.diagnostics = new Diagnostics();
        this.json = new GeometryJson(this.diagnostics);
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

        Vec3 pivot = json.vector(bone.get("pivot"), pointer + ".pivot", Vec3.ZERO, true);
        Vec3 rotation = json.vector(bone.get("rotation"), pointer + ".rotation", Vec3.ZERO, false);

        // The bone-level inflation is the default for every cube of the bone; a cube that declares its own
        // 'inflate' overrides it for itself, exactly as the geometry schema states ("this field overrides the
        // bone's inflate field for this cube only"). Reading it here keeps a bone-wide inflation from being
        // silently dropped, which used to shrink those cubes by 2x inflate per side relative to the authoring tool.
        float inflate = json.floatValue(bone.get("inflate"), pointer + ".inflate", 0.0F);

        for (String key : bone.keySet()) {
            switch (key) {
                case "name", "parent", "pivot", "rotation", "inflate", "poly_mesh", "cubes" -> {
                    // Interpreted below.
                }
                case "mirror" -> reportUnsupported(pointer + ".mirror", "mirroring the UVs of bone '" + name
                        + "' and of its cubes is not applied; the cubes keep their authored UVs");
                case "locators" -> reportUnsupported(pointer + ".locators", "locators on bone '" + name
                        + "' are not exposed as attachment points yet");
                default -> reportUnsupported(pointer + "." + key, "bone property '" + key + "' on bone '" + name
                        + "' is not interpreted");
            }
        }

        List<BoneMesh> meshes = new ArrayList<>();

        // The two geometry systems are read by their own layers and only meet here, as meshes of this bone. Order is the
        // declaration order - poly mesh first, cubes second - and a bone may hold either kind, both, or several meshes of
        // one kind; nothing downstream assumes one mesh per bone.
        JsonElement polyMeshElement = bone.get("poly_mesh");
        if (polyMeshElement != null) {
            readPolyMeshes(polyMeshElement, pointer + ".poly_mesh", name, meshes);
        }

        JsonElement cubesElement = bone.get("cubes");
        if (cubesElement != null) {
            if (cubesElement instanceof JsonArray cubes) {
                accept(new BedrockCubeGeometry(json, diagnostics, textureWidth, textureHeight)
                        .read(cubes, pointer + ".cubes", name, inflate), meshes);
            } else {
                report.error(packId, sourceName, pointer + ".cubes", "Expected an array but found "
                        + JsonValues.describeType(cubesElement) + "; the cubes of bone '" + name + "' were skipped");
            }
        }

        drafts.put(name, new BoneDraft(name, parentName, pivot, rotation, meshes));
    }

    /**
     * Reads the {@code poly_mesh} of a bone, either as one mesh object or as an array of them.
     *
     * <p>The single-object form is the format's own; the array form lets one bone carry several poly meshes, which the
     * model already supports (a bone holds a list of meshes) and which the reference implementation exposes as a list per
     * bone as well. Both forms produce plain {@link BoneMesh} entries of this bone.</p>
     *
     * @param element  the {@code poly_mesh} value
     * @param pointer  the JSON pointer of that value, for diagnostics
     * @param boneName the bone the meshes belong to, for diagnostics
     * @param meshes   the bone's mesh list to append to
     */
    private void readPolyMeshes(JsonElement element, String pointer, String boneName, List<BoneMesh> meshes) {
        if (element instanceof JsonObject single) {
            accept(new PolyMeshGeometry(json, diagnostics, textureWidth, textureHeight)
                    .read(single, pointer, boneName), meshes);
            return;
        }
        if (element instanceof JsonArray array) {
            for (int index = 0; index < array.size(); index++) {
                JsonElement entry = array.get(index);
                String entryPointer = pointer + "[" + index + "]";
                if (entry instanceof JsonObject mesh) {
                    accept(new PolyMeshGeometry(json, diagnostics, textureWidth, textureHeight)
                            .read(mesh, entryPointer, boneName), meshes);
                } else {
                    report.error(packId, sourceName, entryPointer, "Expected a poly mesh object but found "
                            + JsonValues.describeType(entry) + "; the entry was skipped");
                }
            }
            return;
        }
        report.error(packId, sourceName, pointer, "Expected a poly mesh object or an array of them but found "
                + JsonValues.describeType(element) + "; the poly mesh of bone '" + boneName + "' was skipped");
    }

    /**
     * Adds one geometry block to a bone and folds its counters into the model totals.
     *
     * @param block  the block a geometry layer produced, or {@code null} when it produced no geometry
     * @param meshes the bone's mesh list to append to
     */
    private void accept(GeometryBlock block, List<BoneMesh> meshes) {
        if (block == null) {
            return;
        }
        meshes.add(block.mesh());
        cubeCount += block.cubes();
        polygonCount += block.mesh().polygonCount();
        degenerateTriangles += block.degenerateTriangles();
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

    // --- diagnostics ---

    /** Reports a problem at most once per JSON pointer, so repeated polygons do not flood the log. */
    private void reportUnsupported(String pointer, String message) {
        if (reportedPointers.add(pointer)) {
            report.warning(packId, sourceName, pointer, message);
        }
    }

    /**
     * The channel both geometry layers report through.
     *
     * <p>Moving geometry construction into its own classes must not change a single diagnostic, so every entry lands in
     * the same {@link ValidationReport} and under the same JSON pointer as before. "Unsupported" keeps the parser's
     * per-pointer de-duplication.</p>
     */
    private final class Diagnostics implements GeometryDiagnostics {

        @Override
        public void error(String pointer, String message) {
            report.error(packId, sourceName, pointer, message);
        }

        @Override
        public void warning(String pointer, String message) {
            report.warning(packId, sourceName, pointer, message);
        }

        @Override
        public void info(String pointer, String message) {
            report.info(packId, sourceName, pointer, message);
        }

        @Override
        public void unsupported(String pointer, String message) {
            reportUnsupported(pointer, message);
        }
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
}
