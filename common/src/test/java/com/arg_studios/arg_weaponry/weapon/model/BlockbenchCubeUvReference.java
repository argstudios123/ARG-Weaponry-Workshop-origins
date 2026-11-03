package com.arg_studios.arg_weaponry.weapon.model;

import java.util.ArrayList;
import java.util.List;

/**
 * An independent transcription of the authoring tool's cube UV mapping, used as the expected side of
 * {@link BedrockCubeUvMappingTest}.
 *
 * <p>Nothing here is derived from {@link BedrockGeometryParser}: no rectangle normalisation, no corner table and no
 * arithmetic is shared with it. The rules are transcribed from Blockbench's own source, which is the tool the shipped
 * content is authored in:</p>
 *
 * <ul>
 *   <li>{@code THREE.BufferGeometry.setShape} ({@code js/util/three_custom.js}) stores four vertices per cube face, and
 *   {@link #faceCorners} reproduces that order verbatim. It is expressed in the Bedrock file's model space, which is the
 *   mirror of the space the tool displays: its {@code bedrock} codec negates every cube X on load
 *   ({@code base_cube.from[0] = -(origin[0] + size[0])}) and negates it again on export, while its
 *   {@code java_block} codec converts nothing;</li>
 *   <li>{@code CubeFace.UVToLocal} and {@code NodePreviewController.updateUV} ({@code js/outliner/types/cube.js}) sample
 *   the face's rectangle at those four vertices as {@code [(x1, y1), (x2, y1), (x1, y2), (x2, y2)]} of the face's
 *   internal rectangle. That rectangle is kept in the tool's own bottom-left UV space - {@code updateUV} writes
 *   {@code 1 - v} into every vertex - so in the top-left texture space the game samples with, the four texels are
 *   {@code [(u0, v1), (u1, v1), (u1, v0), (u0, v0)]};</li>
 *   <li>the {@code bedrock} codec rotates the rectangle of an {@code up} or {@code down} face by 180 degrees on load
 *   ({@code face.uv = [face.uv[2], face.uv[3], face.uv[0], face.uv[1]]}), so those two faces anchor the rectangle on the
 *   corner {@code (u + uv_size[0], v + uv_size[1])} instead of on {@code (u, v)};</li>
 *   <li>a box-mapped cube takes its six rectangles from the codec's own unwrap ({@code face_list} in
 *   {@code updateUV}), whose top and bottom panels are 180 degrees apart from each other.</li>
 * </ul>
 *
 * <p>{@code u} and {@code v} are texture pixels here, exactly as written in the geometry file; a caller that compares
 * them with normalised vertex UVs divides by the declared texture size itself.</p>
 */
final class BlockbenchCubeUvReference {

    /** The six face names, in the order this project parses them. */
    static final List<String> FACES = List.of("north", "east", "south", "west", "up", "down");

    private BlockbenchCubeUvReference() {
    }

    /**
     * The four corners of one face, in the order the authoring tool stores that face's four vertices.
     *
     * <p>The order - and therefore which corner takes which end of the rectangle - is {@code setShape}'s: a vertical
     * face lists its top edge first and its bottom edge second, {@code up} lists {@code minZ} first and {@code down}
     * {@code maxZ} first. That order is the whole point of this reference and is reproduced verbatim.</p>
     *
     * <p>Which <em>side</em> of the box each name sits on is the file's own axis naming ({@code north} = {@code -Z},
     * {@code east} = {@code +X}, {@code up} = {@code +Y}), because that is the convention the protected cube geometry
     * of this project is pinned to. The authoring tool displays a Bedrock file with its X mirrored (its {@code bedrock}
     * codec negates every cube X), so its {@code east} panel is the mirror of the file's {@code east} side; the
     * renderer applies that same mirror to the whole model, which is why the side a name refers to is a question about
     * the geometry pipeline and not about the UV association this class exists to check.</p>
     *
     * @param face the face name
     * @param minX the box's smallest x, inflation already applied
     * @param minY the box's smallest y, inflation already applied
     * @param minZ the box's smallest z, inflation already applied
     * @param maxX the box's largest x, inflation already applied
     * @param maxY the box's largest y, inflation already applied
     * @param maxZ the box's largest z, inflation already applied
     * @return the four corners, in the file's model space
     */
    static List<Vec3> faceCorners(String face, float minX, float minY, float minZ,
                                  float maxX, float maxY, float maxZ) {
        return switch (face) {
            case "east" -> List.of(
                    new Vec3(maxX, maxY, maxZ), new Vec3(maxX, maxY, minZ),
                    new Vec3(maxX, minY, maxZ), new Vec3(maxX, minY, minZ));
            case "west" -> List.of(
                    new Vec3(minX, maxY, minZ), new Vec3(minX, maxY, maxZ),
                    new Vec3(minX, minY, minZ), new Vec3(minX, minY, maxZ));
            case "up" -> List.of(
                    new Vec3(maxX, maxY, minZ), new Vec3(minX, maxY, minZ),
                    new Vec3(maxX, maxY, maxZ), new Vec3(minX, maxY, maxZ));
            case "down" -> List.of(
                    new Vec3(maxX, minY, maxZ), new Vec3(minX, minY, maxZ),
                    new Vec3(maxX, minY, minZ), new Vec3(minX, minY, minZ));
            case "south" -> List.of(
                    new Vec3(maxX, maxY, maxZ), new Vec3(minX, maxY, maxZ),
                    new Vec3(maxX, minY, maxZ), new Vec3(minX, minY, maxZ));
            case "north" -> List.of(
                    new Vec3(minX, maxY, minZ), new Vec3(maxX, maxY, minZ),
                    new Vec3(minX, minY, minZ), new Vec3(maxX, minY, minZ));
            default -> throw new IllegalArgumentException("Unknown face '" + face + "'");
        };
    }

    /**
     * The texel each of a face's four corners samples, for a cube that declares a UV per face.
     *
     * @param face   the face name
     * @param u      the authored {@code uv[0]} of that face, in texture pixels
     * @param v      the authored {@code uv[1]} of that face, in texture pixels
     * @param width  the authored {@code uv_size[0]}, signed
     * @param height the authored {@code uv_size[1]}, signed
     * @return four {@code {u, v}} pairs in texture pixels, one per corner of {@link #faceCorners}
     */
    static List<float[]> perFaceTexels(String face, float u, float v, float width, float height) {
        if (face.equals("up") || face.equals("down")) {
            // The codec's up/down swap: the rectangle is anchored on its opposite corner.
            return slots(u + width, v + height, u, v);
        }
        return slots(u, v, u + width, v + height);
    }

    /**
     * The texel each of a face's four corners samples, for a box-mapped cube.
     *
     * <p>This is the codec's own unwrap: a horizontal strip of {@code east | north | west | south} with the top and
     * bottom panels above it, each panel written as an origin plus a signed size so that the top and bottom panels are
     * rotated by 180 degrees relative to each other.</p>
     *
     * @param face   the face name
     * @param u      the authored box {@code uv[0]}, in texture pixels
     * @param v      the authored box {@code uv[1]}, in texture pixels
     * @param width  the box's x size
     * @param height the box's y size
     * @param depth  the box's z size
     * @return four {@code {u, v}} pairs in texture pixels, one per corner of {@link #faceCorners}
     */
    static List<float[]> boxTexels(String face, float u, float v, float width, float height, float depth) {
        float fromX;
        float fromY;
        float sizeX;
        float sizeY;
        switch (face) {
            case "east" -> {
                fromX = 0.0F;
                fromY = depth;
                sizeX = depth;
                sizeY = height;
            }
            case "west" -> {
                fromX = depth + width;
                fromY = depth;
                sizeX = depth;
                sizeY = height;
            }
            case "up" -> {
                fromX = depth + width;
                fromY = depth;
                sizeX = -width;
                sizeY = -depth;
            }
            case "down" -> {
                fromX = depth + width * 2.0F;
                fromY = 0.0F;
                sizeX = -width;
                sizeY = depth;
            }
            case "south" -> {
                fromX = depth * 2.0F + width;
                fromY = depth;
                sizeX = width;
                sizeY = height;
            }
            case "north" -> {
                fromX = depth;
                fromY = depth;
                sizeX = width;
                sizeY = height;
            }
            default -> throw new IllegalArgumentException("Unknown face '" + face + "'");
        }
        float x1 = u + fromX;
        float y1 = v + fromY;
        return slots(x1, y1, x1 + sizeX, y1 + sizeY);
    }

    /** @return the four texels of a rectangle given its two opposite corners, in the tool's slot order. */
    private static List<float[]> slots(float x1, float y1, float x2, float y2) {
        List<float[]> texels = new ArrayList<>(4);
        texels.add(new float[]{x1, y1});
        texels.add(new float[]{x2, y1});
        texels.add(new float[]{x1, y2});
        texels.add(new float[]{x2, y2});
        return texels;
    }
}
