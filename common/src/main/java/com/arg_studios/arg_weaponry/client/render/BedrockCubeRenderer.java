package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.weapon.model.BoneMesh;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Draws the Bedrock <strong>cube</strong> meshes of one bone.
 *
 * <p>The counterpart of {@link PolyMeshRenderer}: same bone, same pose stack, same pipeline, different geometry system.
 * The two are independent by construction - this class looks only at meshes whose {@link BoneMesh.Source} is
 * {@link BoneMesh.Source#CUBE}, and it never refers to a poly mesh, so the cube path renders correctly on a model that
 * contains no poly mesh at all and keeps working if the poly path is disabled.</p>
 *
 * <p>Geometry is already resolved when it gets here: the cube layer baked each cube's own rotation and emitted six faces
 * with their positions, flat face normals and UVs (see {@code BedrockCubeGeometry}). This class therefore does not
 * transform geometry or touch UVs - it applies the bone's pose (already on the pose stack) and submits. Bone transforms
 * are applied exactly once, by the single hierarchy walk in {@link WeaponRenderer}, which is what keeps cubes and poly
 * meshes in the same animation.</p>
 */
public final class BedrockCubeRenderer {

    private BedrockCubeRenderer() {
    }

    /**
     * Submits one cube mesh.
     *
     * <p>Called by the hierarchy walk for each mesh of a bone, so the meshes of one bone keep their declaration order in
     * the submitted stream - which matters for the translucent emissive pipeline a glowing bone uses. A mesh of another
     * kind is ignored rather than reported, so cube geometry can never be drawn through the poly path or the other way
     * round, even if a caller gets the dispatch wrong.</p>
     *
     * @param mesh        the geometry to draw, ignored unless it is cube geometry
     * @param poseStack   the pose stack, already carrying the bone's transform
     * @param collector   the collector of the current frame
     * @param renderType  the pipeline the bone draws with, which already accounts for its glowing state
     * @param lightCoords the packed light of the draw
     * @return whether the mesh was submitted
     */
    public static boolean submit(BoneMesh mesh, PoseStack poseStack, SubmitNodeCollector collector,
                                 RenderType renderType, int lightCoords) {
        if (mesh.source() != BoneMesh.Source.CUBE) {
            return false;
        }
        MeshVertexWriter.submit(mesh, poseStack, collector, renderType, lightCoords);
        return true;
    }
}
