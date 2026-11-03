package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.weapon.model.BoneMesh;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Turns one {@link BoneMesh} into submitted vertices.
 *
 * <p>This is the lowest layer of weapon rendering and it is deliberately the <em>only</em> shared part of it: both
 * {@link BedrockCubeRenderer} and {@link PolyMeshRenderer} hand their mesh here, because by the time geometry reaches
 * this point the two systems are indistinguishable - a triangle list with per-corner position, normal and UV. What
 * differs between them (how the mesh was built, what it means) is already decided when the mesh was parsed, and what
 * differs in how they are drawn (pipeline selection, bone state) stays in the two renderers.</p>
 *
 * <p>Nothing here is cached and nothing is uploaded: the mesh is walked per draw call into the vanilla
 * custom-geometry node, so vanilla's own batching and buffer sizing apply and no second vertex buffer exists.</p>
 */
final class MeshVertexWriter {

    /**
     * Corner order within one submitted primitive.
     *
     * <p>{@code 0, 2, 1, 1} is the mirrored form of the default {@code 0, 1, 2, 2}: the live triangle stays
     * {@code (first, third, second)} and the fourth corner repeats the third so the quad stays degenerate. See
     * {@link #writeQuads} for why the winding is oriented this way.</p>
     */
    private static final int[] MIRRORED_QUAD_ORDER = {0, 2, 1, 1};

    private MeshVertexWriter() {
    }

    /**
     * Submits one mesh through the custom-geometry node, which keeps vanilla's batching intact.
     *
     * @param mesh        the geometry to draw
     * @param poseStack   the pose of the bone the geometry belongs to
     * @param collector   the collector of the current frame
     * @param renderType  the pipeline to draw with
     * @param lightCoords the packed light of the draw
     */
    static void submit(BoneMesh mesh, PoseStack poseStack, SubmitNodeCollector collector, RenderType renderType,
                       int lightCoords) {
        collector.submitCustomGeometry(poseStack, renderType,
                (pose, buffer) -> writeQuads(mesh, pose, buffer, lightCoords));
    }

    /**
     * Writes one mesh's corners into a vertex consumer whose primitive topology is {@code QUADS}.
     *
     * <p>The topology is not a detail that can be ignored. This Minecraft version builds a custom-geometry
     * buffer from the render type ({@code StagedVertexBuffer#getVertexBuilder} passes
     * {@code renderType.primitiveTopology()} into the {@code BufferBuilder}) and derives the index count
     * from the vertex count alone: {@code PrimitiveTopology.QUADS#indexCount(vertices)} is
     * {@code vertices / 4 * 6}. Every entity pipeline is declared with {@code PrimitiveTopology.QUADS}, so
     * a stream of three-vertex triangles is consumed in groups of four — a triangle's remaining corners
     * become corners of the next quad, every primitive mixes corners of different triangles, the last
     * {@code vertices mod 4} corners are never indexed — and the surface, its UVs and its normals all come
     * out scrambled. That is exactly what a "broken texture" looks like.</p>
     *
     * <p>The mesh therefore emits four corners per primitive: {@code a, b, c, c}. The second triangle of
     * that quad ({@code a, c, c}) is degenerate and rasterises nothing, so the visible geometry is exactly
     * the parsed triangle list, with each corner keeping the UV and the flat face normal the parser gave it.
     * TaCZ's {@code PolyMesh} does the same thing for its triangles, which is the confirmation that
     * degenerate quads are the intended way to feed this topology.</p>
     *
     * <p><strong>Mirrored winding.</strong> The model-to-view transform mirrors the model in X, because
     * Bedrock geometry is authored in the mirror of Minecraft's space (see {@link ViewBoneTransform}).
     * A mirror reverses the apparent winding of every triangle, so with back-face culling enabled the
     * outside of the weapon would be discarded and its interior drawn instead. Each primitive is
     * therefore emitted as {@code a, c, b, b} rather than {@code a, b, c, c}: the live triangle keeps
     * the same three corners, the same UVs and the same normals, but is wound the other way, so it faces
     * the way the parsed face normal says it should. The fourth corner stays a repeat of the third, so
     * the quad is still degenerate, the primitive count is unchanged and every corner is still written
     * exactly once per primitive.</p>
     */
    private static void writeQuads(BoneMesh mesh, PoseStack.Pose pose, VertexConsumer buffer, int lightCoords) {
        float[] positions = mesh.positionsUnsafe();
        float[] normals = mesh.normalsUnsafe();
        float[] uvs = mesh.uvsUnsafe();
        int[] corners = mesh.quadCornerIndicesUnsafe();

        for (int base = 0; base < corners.length; base += 4) {
            for (int offset : MIRRORED_QUAD_ORDER) {
                int corner = corners[base + offset];
                buffer.addVertex(pose, positions[corner * 3], positions[corner * 3 + 1], positions[corner * 3 + 2])
                        .setColor(255, 255, 255, 255)
                        .setUv(uvs[corner * 2], uvs[corner * 2 + 1])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(lightCoords)
                        .setNormal(pose, normals[corner * 3], normals[corner * 3 + 1], normals[corner * 3 + 2]);
            }
        }
    }
}
