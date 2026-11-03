package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.weapon.model.BedrockRotation;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames;
import com.arg_studios.arg_weaponry.weapon.model.RotationConvention;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Places a weapon model in first-person camera space using one of its view anchor bones.
 *
 * <h2>What the anchor means</h2>
 * <p>{@code default_view} (and, for a later milestone, {@code aiming_view}) is a transform-only bone
 * that the model author positions and rotates in Blockbench. It is a <em>camera</em>, not a steering
 * wheel: the weapon is seen from that bone, so the view has to undo the bone's own frame. In the
 * shipped M100 DMR, {@code default_view} sits exactly where the animation target {@code camera} sits,
 * which is the confirmation that this is how the model was authored.</p>
 *
 * <p>Concretely, for the anchor bone <em>a</em> with ancestor chain {@code root .. parent(a)}:</p>
 * <ul>
 *   <li>{@code q = A_ancestors(P_a)} is the anchor pivot in model space - the point that must land at
 *       the camera position, and</li>
 *   <li>{@code R = R_ancestors * R_a} is the orientation of the anchor's local frame - the axes the
 *       camera looks along.</li>
 * </ul>
 * <p>The placement is therefore {@code S * M * R^-1 * T(-q)}, where {@code M} is the handedness
 * conversion described below: the anchor's position and orientation are resolved from the model
 * (including every ancestor transform) and then removed from the geometry <em>once</em>, the model is
 * mirrored into Minecraft's handedness <em>once</em>, and the single model-to-view scale is applied
 * <em>once</em>. Nothing is applied twice - in particular the ancestors are not re-applied to the
 * geometry, they only contribute to {@code q} and {@code R} - and no per-weapon constant exists:
 * moving or rotating {@code default_view} (or the {@code views} parent it hangs from) in the model
 * file is the only way to change the pose.</p>
 *
 * <h2>Handedness: Bedrock geometry is mirrored in X</h2>
 * <p>A Bedrock geometry file is <em>not</em> written in Minecraft's model space. Blockbench, which is
 * the tool this content is authored in, converts every Bedrock pivot and vertex X to its negative when
 * it loads such a file and negates it again when it exports one (its {@code bedrock} codec negates
 * {@code pivot[0]}, {@code rotation[0]} and {@code rotation[1]} in both directions), while its
 * {@code java_block} codec - the one that produces Minecraft's own model files - converts nothing. So
 * Blockbench's editing viewport, Minecraft's model space and this renderer's camera space are one
 * space, and the Bedrock file is that space mirrored in X.</p>
 *
 * <p>The visible consequences of skipping the conversion are exactly the ones reported for Milestone
 * 10J: a {@code default_view} that the author placed on the weapon's left is read as a point on its
 * right, so the weapon is drawn on the wrong side of the crosshair, and every asymmetric detail (dust
 * cover, bolt, support hand) appears on its mirror-image side. The conversion is a single
 * {@code scale(-1, 1, 1)} applied <em>outside</em> the anchor inverse, which is what makes the anchor
 * pivot and the geometry around it mirror together. Applying it on the other side of the anchor would
 * mirror the geometry without mirroring the anchor, so the anchor would no longer sit at the camera and
 * the weapon would stay on the wrong side. Mirroring reverses triangle winding, so
 * {@link WeaponRenderer} compensates for it when it writes vertices; see
 * {@code MeshVertexWriter}.</p>
 *
 * <p>For cross-checking with the authoring tool: the shipped M100 DMR stores its anchor at Bedrock X
 * {@code 4.0}, which is Blockbench X {@code -4.0} - the same bone, two conventions. The renderer works
 * in Bedrock coordinates internally and converts on the way to the camera, so both numbers remain
 * correct descriptions of the same anchor.</p>
 *
 * <h2>No placement tuning here</h2>
 * <p>This class deliberately has no offsets and no fixed yaw/pitch/roll. Because the anchor already
 * encodes the authored camera, any such number would either be a per-weapon correction that belongs in
 * the model, or a constant that hides a bug in the chain above. The only non-model value is
 * {@link #SCALE}, the project-wide size of one Bedrock unit seen from the eye.</p>
 *
 * <h2>Camera space</h2>
 * <p>The pose stack handed in must be Minecraft's first-person <em>camera space</em>: origin at the
 * camera, {@code +X} right, {@code +Y} up and {@code -Z} along the view direction. That is the pose
 * {@code GameRenderer.renderItemInHand} holds immediately after it multiplies in the inverse view
 * rotation and before it applies the damage tilt and the walking bob, which are transformations of the
 * vanilla hand only. Of those axes, Bedrock models agree on two: the M100 DMR's muzzle is at
 * {@code -Z} and its sights at {@code +Y}, so forward and up need no conversion at all. X is the one
 * axis that differs, and the handedness section above is the whole of that conversion.</p>
 */
public final class ViewBoneTransform {

    /** Bedrock model units per block. */
    public static final float MODEL_UNITS_PER_BLOCK = 16.0F;

    /**
     * Overall scale of the weapon. {@code 1.0} means one model unit is 1/16 block.
     *
     * <p>This is the one view-size knob in the project, and it is global on purpose: content models are
     * authored at different unit scales, so a single number is the only thing that can be tuned without
     * touching the models. The M100 DMR spans about fifty model units along its barrel, which is
     * roughly three blocks at {@code 1.0}; the default shrinks that to a rifle-sized 1.3 blocks.</p>
     */
    public static final float SCALE = 0.42F;

    private ViewBoneTransform() {
    }

    /**
     * Applies the first-person placement for the normal (not aiming) view.
     *
     * @param poseStack the camera-space pose stack handed over by the first-person renderer
     * @param model     the model being drawn
     */
    public static void applyFirstPerson(PoseStack poseStack, WeaponModel model) {
        applyFirstPerson(poseStack, model, ModelBoneNames.DEFAULT_VIEW);
    }

    /**
     * Applies a placement anchored at the given view bone.
     *
     * @param poseStack    the camera-space pose stack handed over by the first-person renderer
     * @param model        the model being drawn
     * @param viewBoneName the anchor bone, {@code default_view} or {@code aiming_view}
     */
    public static void applyFirstPerson(PoseStack poseStack, WeaponModel model, String viewBoneName) {
        poseStack.mulPose(placement(model, viewBoneName));
    }

    /**
     * Builds the placement of one view anchor as a matrix, instead of applying it directly.
     *
     * <p>Milestone 10S blends two placements for aim-down-sights - the ordinary {@code default_view} pose and the
     * {@code aiming_view} pose - so the placement has to be readable as a value. The matrix is produced by the very same
     * code that {@link #applyFirstPerson} applies, so the two can never drift apart, and the blending in
     * {@code ViewPlacement} then interpolates between two results of this method.</p>
     *
     * @param model         the model being drawn
     * @param viewBoneName  the anchor bone, {@code default_view} or {@code aiming_view}
     * @return the placement, to be multiplied into a camera-space pose stack
     */
    public static Matrix4f placement(WeaponModel model, String viewBoneName) {
        PoseStack scratch = new PoseStack();
        buildPlacement(scratch, model, viewBoneName);
        return new Matrix4f(scratch.last().pose());
    }

    private static void buildPlacement(PoseStack poseStack, WeaponModel model, String viewBoneName) {
        List<ModelBone> chain = anchorChain(model, viewBoneName);

        // The coordinate conversion and the global size, applied exactly once and after the anchor
        // work: the anchor is expressed in model units, so this scale is what turns the result into
        // blocks.
        float scale = SCALE / MODEL_UNITS_PER_BLOCK;
        poseStack.scale(scale, scale, scale);

        // Bedrock's model space is Minecraft's mirrored in X (see the class documentation). Mirroring
        // here - outside the anchor inverse below - is what lets the anchor pivot and the geometry
        // around it flip together, which is what puts the weapon on the side the author designed.
        poseStack.scale(-1.0F, 1.0F, 1.0F);

        if (chain.isEmpty()) {
            // No usable anchor: the model origin is the view origin. That is the documented fallback
            // and keeps a model without a view bone visible instead of dropping it.
            return;
        }

        // 1. Remove the anchor frame's orientation, tip first, so the accumulated rotation is
        //    R(anchor)^-1 * ... * R(root)^-1, i.e. the inverse of R(root) * ... * R(anchor). The authored triples
        //    are converted exactly like every other Bedrock rotation this renderer composes, so the frame that is
        //    removed is the same frame the geometry is drawn in.
        for (int index = chain.size() - 1; index >= 0; index--) {
            AnimatedBoneTransform.rotateInverseZYX(poseStack,
                    RotationConvention.toRenderSpace(chain.get(index).rotation()));
        }

        // 2. Move the anchor pivot - which already contains every ancestor transform - onto the camera
        //    position. Together with step 1 this is the single rigid placement R^-1 * T(-q): the model
        //    is seen from the anchor, and no ancestor transform is applied to the geometry a second
        //    time.
        Vec3 anchorPivot = pivotInModelSpace(chain);
        poseStack.translate(-anchorPivot.x(), -anchorPivot.y(), -anchorPivot.z());
    }

    /**
     * Converts a model-space point into the space this class draws in, i.e. applies the Bedrock to
     * Minecraft X handedness conversion.
     *
     * <p>Only X changes; the forward axis ({@code -Z}) and the up axis ({@code +Y}) mean the same thing
     * in both formats. This is used for diagnostics - it is how a Bedrock pivot can be compared with
     * the number the author sees in Blockbench - and it is the exact conversion the render path
     * performs, so the two cannot drift apart.</p>
     *
     * @param point a point in Bedrock model space
     * @return the same point in Minecraft model space
     */
    public static Vec3 toMinecraftSpace(Vec3 point) {
        return new Vec3(-point.x(), point.y(), point.z());
    }

    /**
     * Resolves the anchor bone and its ancestors.
     *
     * @param model        the model
     * @param viewBoneName the anchor bone name
     * @return the chain from the root bone down to the anchor, or an empty list when the model has no
     *         bone with that name; never {@code null}
     */
    public static List<ModelBone> anchorChain(WeaponModel model, String viewBoneName) {
        if (model == null || viewBoneName == null) {
            return List.of();
        }
        Map<String, ModelBone> bonesByName = model.bonesByName();
        ModelBone current = bonesByName.get(viewBoneName);
        if (current == null) {
            return List.of();
        }

        List<ModelBone> reversed = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current.name())) {
            reversed.add(current);
            String parentName = current.parent();
            current = parentName == null ? null : bonesByName.get(parentName);
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /**
     * @param model        the model
     * @param viewBoneName the anchor bone name
     * @return the anchor pivot in model space, i.e. after its ancestors' authored transforms, or the
     *         model origin when the bone does not exist
     */
    public static Vec3 anchorPivot(WeaponModel model, String viewBoneName) {
        List<ModelBone> chain = anchorChain(model, viewBoneName);
        return chain.isEmpty() ? Vec3.ZERO : pivotInModelSpace(chain);
    }

    /**
     * @param chain the root-to-anchor chain, never empty
     * @return the anchor pivot after every ancestor transform
     */
    private static Vec3 pivotInModelSpace(List<ModelBone> chain) {
        Vec3 point = chain.get(chain.size() - 1).pivot();
        for (int index = 0; index < chain.size() - 1; index++) {
            point = transformedPoint(chain.get(index), point);
        }
        return point;
    }

    /**
     * Moves one point through a bone's authored transform without a pose stack.
     *
     * <p>Used only to resolve the anchor pivot analytically. The rotation is the shared
     * {@link BedrockRotation#rotateAround(Vec3, Vec3, Vec3)} composition ({@code Rz * Ry * Rx}), applied to the triple
     * already converted by {@link RotationConvention#toRenderSpace(Vec3)} - the very same two steps the geometry takes,
     * so an anchor cannot end up in a different frame from the model it anchors.</p>
     *
     * @param bone  the bone whose transform is applied
     * @param point the point in the bone's parent space
     * @return the point after the pivot translation, the rotation and the inverse pivot translation
     */
    private static Vec3 transformedPoint(ModelBone bone, Vec3 point) {
        return BedrockRotation.rotateAround(point, bone.pivot(),
                RotationConvention.toRenderSpace(bone.rotation()));
    }
}
