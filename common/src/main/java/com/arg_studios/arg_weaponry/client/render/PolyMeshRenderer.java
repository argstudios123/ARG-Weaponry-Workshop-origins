package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.weapon.model.BoneMesh;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Draws the {@code poly_mesh} meshes of one bone.
 *
 * <p>The counterpart of {@link BedrockCubeRenderer}: same bone, same pose stack, same pipeline, different geometry
 * system. It looks only at meshes whose {@link BoneMesh.Source} is {@link BoneMesh.Source#POLY_MESH} and never refers to
 * cube geometry, so a model that is all poly mesh renders without the cube path being involved and vice versa.</p>
 *
 * <p>A poly mesh arrives here already triangulated, flat-shaded and UV-resolved by {@code PolyMeshGeometry}; several of
 * them may sit on one bone and each is submitted in declaration order. Bone transforms are not applied here - they are
 * on the pose stack, applied once by the hierarchy walk in {@link WeaponRenderer}, which is the whole point of sharing
 * one bone hierarchy between the two geometry systems.</p>
 *
 * <p><strong>Opaque and translucent geometry.</strong> Bedrock geometry carries no per-mesh material, so a poly mesh is
 * drawn with the bone's pipeline: the weapon atlas through the cutout family, or the emissive type when the bone belongs
 * to the model's glowing selection. Cutout discards fragments whose alpha is at or below the pipeline's threshold and
 * keeps the rest opaque, which is the behaviour the shipped content relies on; no separate translucent pass is created
 * because nothing in the format or the content asks for one.</p>
 */
public final class PolyMeshRenderer {

    private PolyMeshRenderer() {
    }

    /**
     * Submits one poly mesh.
     *
     * <p>Called by the hierarchy walk for each mesh of a bone, so the meshes of one bone keep their declaration order in
     * the submitted stream - which matters for the translucent emissive pipeline a glowing bone uses. A mesh of another
     * kind is ignored rather than reported.</p>
     *
     * @param mesh        the geometry to draw, ignored unless it is poly-mesh geometry
     * @param poseStack   the pose stack, already carrying the bone's transform
     * @param collector   the collector of the current frame
     * @param renderType  the pipeline the bone draws with, which already accounts for its glowing state
     * @param lightCoords the packed light of the draw
     * @return whether the mesh was submitted
     */
    public static boolean submit(BoneMesh mesh, PoseStack poseStack, SubmitNodeCollector collector,
                                 RenderType renderType, int lightCoords) {
        if (mesh.source() != BoneMesh.Source.POLY_MESH) {
            return false;
        }
        MeshVertexWriter.submit(mesh, poseStack, collector, renderType, lightCoords);
        return true;
    }
}
