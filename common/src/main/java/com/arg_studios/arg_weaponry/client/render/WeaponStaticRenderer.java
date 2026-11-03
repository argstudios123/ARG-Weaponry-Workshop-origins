package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Draws a weapon as a static 3D model in every display context that is not the first-person view.
 *
 * <h2>How it plugs in</h2>
 * <p>It implements vanilla's {@link SpecialModelRenderer}, the item-model system's own extension point for "this item is drawn by
 * custom code". {@link com.arg_studios.arg_weaponry.client.model.WeaponStaticItemModel} hands one of these to a render-state layer
 * together with the resolved model and texture, so nothing about the item renderer has to be patched: another player's hand, the
 * ground, an item frame, a shelf and a container all go through {@code ItemStackRenderState#submit}, and all of them end up here.</p>
 *
 * <h2>What it draws</h2>
 * <p>Exactly the authored geometry, at the authored pose, through {@link WeaponRenderer#submitStatic}: no animation, no
 * first-person arms, no view sway, no camera transform. The anchor frame that makes the model the right size and orientation is
 * the same one the first-person renderer uses ({@link ViewBoneTransform}), so a gun cannot look larger in the world than in the
 * hand. The per-context {@code display_views} transform is <em>not</em> applied here - vanilla applied it to the pose stack before
 * calling this renderer, and applying it twice is the classic "the item is in the wrong place" bug.</p>
 */
public final class WeaponStaticRenderer implements SpecialModelRenderer<WeaponStaticRenderer.Target> {

    /** The shared instance: it holds no state, the model and texture travel in the argument. */
    public static final WeaponStaticRenderer INSTANCE = new WeaponStaticRenderer();

    /** Extent arrays computed per model, so a frame does not rebuild eight corners per item. */
    private static final Map<WeaponModel, Vector3fc[]> EXTENTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * One drawable weapon: the item (for its pack and definition) and the resources resolved for it.
     *
     * @param item    the weapon item
     * @param model   its geometry
     * @param texture its uploaded texture
     */
    public record Target(WeaponItem item, WeaponModel model, Identifier texture) {
    }

    private WeaponStaticRenderer() {
    }

    @Override
    public void submit(@Nullable Target target, PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                       int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
        if (target == null || poseStack == null || submitNodeCollector == null) {
            return;
        }
        poseStack.pushPose();
        try {
            // The anchor frame only: scale, Bedrock handedness and the anchor pivot at the origin. The display-context
            // transform is already in the pose stack.
            ViewBoneTransform.applyFirstPerson(poseStack, target.model());
            WeaponRenderer.submitStatic(target.item(), poseStack, submitNodeCollector, lightCoords);
        } finally {
            poseStack.popPose();
        }
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        // The layer carries the per-model extents (see extents); a renderer-wide constant would be wrong for every model whose
        // geometry is not a unit cube.
    }

    @Override
    public @Nullable Target extractArgument(ItemStack stack) {
        // Only the baked-JSON path uses this; this renderer is installed directly by WeaponStaticItemModel.
        return null;
    }

    /**
     * @param model a weapon model, may be {@code null}
     * @return a supplier of that model's bounding-box corners, in the space this renderer draws in
     */
    public static Supplier<Vector3fc[]> extents(WeaponModel model) {
        return () -> extentsOf(model);
    }

    /**
     * The eight corners of the model's pivot box, transformed into the renderer's space.
     *
     * <p>Vanilla uses the reported extents for the dropped-item hover height and for the "oversized in GUI" flag, so a box that
     * matches the geometry keeps a dropped gun sitting on the ground instead of sinking into it. The box is built from the bone
     * pivots, which is cheap, stable and always contains the model's parts.</p>
     *
     * @param model a weapon model, may be {@code null}
     * @return the corners, or an empty array when the model has no bones
     */
    public static Vector3fc[] extentsOf(WeaponModel model) {
        if (model == null) {
            return new Vector3fc[0];
        }
        Vector3fc[] cached = EXTENTS.get(model);
        if (cached != null) {
            return cached;
        }
        Vector3fc[] computed = computeExtents(model);
        EXTENTS.put(model, computed);
        return computed;
    }

    private static Vector3fc[] computeExtents(WeaponModel model) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (ModelBone bone : model.bonesInOrder()) {
            Vec3 pivot = bone.pivot();
            minX = Math.min(minX, pivot.x());
            minY = Math.min(minY, pivot.y());
            minZ = Math.min(minZ, pivot.z());
            maxX = Math.max(maxX, pivot.x());
            maxY = Math.max(maxY, pivot.y());
            maxZ = Math.max(maxZ, pivot.z());
        }
        if (!(minX <= maxX)) {
            return new Vector3fc[0];
        }
        // A small margin, so the parts that extend past their bone's pivot are covered as well.
        double margin = 2.0;
        minX -= margin;
        minY -= margin;
        minZ -= margin;
        maxX += margin;
        maxY += margin;
        maxZ += margin;

        Matrix4f placement = ViewBoneTransform.placement(model, ModelBoneNames.DEFAULT_VIEW);
        List<Vector3fc> corners = new ArrayList<>(8);
        for (int cornerX = 0; cornerX < 2; cornerX++) {
            for (int cornerY = 0; cornerY < 2; cornerY++) {
                for (int cornerZ = 0; cornerZ < 2; cornerZ++) {
                    Vector3f corner = new Vector3f(cornerX == 0 ? (float) minX : (float) maxX,
                            cornerY == 0 ? (float) minY : (float) maxY,
                            cornerZ == 0 ? (float) minZ : (float) maxZ);
                    corners.add(placement.transformPosition(corner));
                }
            }
        }
        return corners.toArray(new Vector3fc[0]);
    }

    /** @return how many models have cached extents; for diagnostics and tests. */
    public static int cachedExtentCount() {
        return EXTENTS.size();
    }

    /** Forgets the computed extents, e.g. on a resource reload. */
    public static void clearExtents() {
        EXTENTS.clear();
    }
}
