package com.arg_studios.arg_weaponry.client.camera;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.animation.WeaponViewControllers;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.client.render.WeaponDisplayCache;
import com.arg_studios.arg_weaponry.client.render.WeaponModelCache;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * The client's camera-bone animation state: which weapon owns it, and how to sample it for a frame.
 *
 * <h2>What changed in the hotfix, and why</h2>
 * <p>Milestone 10S evaluated this transform on the client tick and stored an <em>absolute</em> camera orientation in it, using
 * {@code player.getViewYRot(1.0F)} / {@code getViewXRot(1.0F)}. The camera hook then applied that orientation every frame.
 * Between two ticks the mouse could move freely and the camera kept answering with the angle of the tick that had already
 * passed, so the world view advanced at 20 Hz while the frame rate stayed high - the reported "jitter / feels like 5 FPS",
 * and the reason it only happened while a clip that animates the {@code camera} bone was playing (firing, reloading,
 * inspecting).</p>
 *
 * <p>This class therefore no longer stores an orientation at all. It stores the <em>owner</em> (the weapon and its model,
 * resolved on the tick, which is cheap and cached) and samples the pose when a frame asks for it. Sampling never advances the
 * timeline, so the perspective-continuity property of 10S is untouched: the animation clock is still owned by
 * {@code WeaponViewControllers#tickHeldWeapons}.</p>
 *
 * <h2>Composition</h2>
 * <p>{@link #transformForFrame(float)} returns the pose-derived camera-space transform; the level camera combines it with its
 * own live orientation through {@link CameraComposition#compose}, and the view model applies the same rotation and the
 * negated translation to stay locked to the world. Because both are sampled from the same pose with the same partial tick,
 * they can never disagree by a frame, and because neither keeps a running total, nothing can accumulate.</p>
 *
 * <h2>Scope</h2>
 * <p>Everything is gated by {@link CameraGate}: nothing is applied or returned in third person, for a detached camera or when
 * the camera no longer follows the local player. {@link #clientTick} drops the state when the context changes, and
 * {@link #reset} clears it on disconnect and on a resource reload.</p>
 */
public final class FirstPersonCamera {

    private static WeaponModel model;
    private static String ownerItemId;
    private static String animationName;
    private static boolean reportedScaleChannel;

    private FirstPersonCamera() {
    }

    /**
     * Binds the camera animation to the weapon the player is holding.
     *
     * <p>Called from the client tick, right after the held weapon's timeline was advanced. It resolves the model (a cached
     * lookup) and records the owner; it does <em>not</em> read the player's view angles, which is what removes the 10S bug.</p>
     *
     * @param item the weapon held in the main hand, may be {@code null}
     */

    public static void update(WeaponItem item) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || item == null) {
            clear();
            return;
        }
        WeaponModel resolved = modelFor(item);
        if (resolved == null) {
            clear();
            return;
        }
        model = resolved;
        ownerItemId = String.valueOf(BuiltInRegistries.ITEM.getKey(item));
        animationName = null;
    }

    /**
     * Samples the camera-bone transform the current frame must apply.
     *
     * <p>Called from the camera hook before it sets the orientation, and once more (or not at all) by the view-model hook; the
     * result is a pure function of the sampled pose, so calling it again in the same frame returns the same value instead of
     * advancing anything.</p>
     *
     * @param partialTick the frame's partial tick, used to sample the animation timeline exactly like the weapon pose does
     * @return the transform, or {@code null} when no camera animation applies
     */
    public static CameraTransform transformForFrame(float partialTick) {
        if (model == null || !isActiveFor(Minecraft.getInstance().player)) {
            return null;
        }
        WeaponPose pose = WeaponViewControllers.instance().mainHandPoseForFrame(partialTick);
        if (pose == null) {
            return null;
        }
        if (CameraBoneAnimation.hasScaleChannel(pose) && !reportedScaleChannel) {
            reportedScaleChannel = true;
            Constants.LOG.info("[ARG] The animation '{}' scales the '{}' bone. A camera cannot be scaled in Minecraft's "
                            + "world-view transform, so that channel is not applied; rotation and position are",
                    pose.animationName(), CameraBoneAnimation.CAMERA_BONE);
        }
        CameraTransform computed = CameraBoneAnimation.compute(model, pose);
        animationName = pose.animationName();
        return computed;
    }

    /**
     * @param item the weapon being held
     * @return its model, resolved through the same caches the renderer uses, or {@code null}
     */
    private static WeaponModel modelFor(WeaponItem item) {
        return WeaponModelCache.instance().get(item.pack(), WeaponDisplayCache.instance()
                .get(item.pack(), item.definition()).map(WeaponDisplay::model).orElse(null));
    }

    /**
     * @param player the local player, may be {@code null}
     * @return whether the camera animation belongs to the weapon that player is holding right now
     */
    public static boolean isActiveFor(LocalPlayer player) {
        if (player == null || model == null || ownerItemId == null) {
            return false;
        }
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        return stack.getItem() instanceof WeaponItem
                && ownerItemId.equals(String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem())));
    }

    /**
     * The transform the camera hook may apply this frame, or {@code null}.
     *
     * <p>{@code Camera#setRotation} and {@code Camera#setPosition} are protected; this method owns the decision of whether the
     * hook may touch them at all.</p>
     *
     * @param firstPerson   whether the active camera type is first person
     * @param detached      whether the camera is detached
     * @param followsPlayer whether the camera follows the local player
     * @param partialTick   the frame's partial tick
     * @return the transform to apply, or {@code null} when nothing may be applied
     */
    public static CameraTransform transformForCamera(boolean firstPerson, boolean detached, boolean followsPlayer,
                                                     float partialTick) {
        if (!CameraGate.mayApply(firstPerson, detached, followsPlayer, isActiveFor(Minecraft.getInstance().player))) {
            return null;
        }
        return transformForFrame(partialTick);
    }

    /**
     * Applies the compensating rotation and translation to the first-person view model.
     *
     * <p>The pose stack is in camera space; the camera-space rotation is applied to it and the translation is applied
     * negated, which keeps the weapon and the world aligned while the camera moves. Applying it here - once, before the
     * anchor transform - is what makes the weapon follow the authored camera animation instead of ignoring it. The sample is
     * the same one the camera hook took, because both are sampled from the same pose with the same partial tick.</p>
     *
     * @param poseStack   the camera-space pose stack of the first-person pass
     * @param partialTick the frame's partial tick
     */
    public static void applyToViewModel(PoseStack poseStack, float partialTick) {
        CameraTransform current = transformForFrame(partialTick);
        if (!CameraGate.mayApplyToViewModel(current != null)) {
            return;
        }
        if (current.hasTranslation()) {
            poseStack.translate(-current.translationX(), -current.translationY(), -current.translationZ());
        }
        if (current.hasRotation()) {
            poseStack.mulPose(new Matrix4f().rotation(new Quaternionf(current.rotationX(), current.rotationY(),
                    current.rotationZ(), current.rotationW())));
        }
    }

    /**
     * Clears the state when it no longer belongs to the current situation.
     *
     * @param minecraft the client
     */
    public static void clientTick(Minecraft minecraft) {
        if (model == null) {
            return;
        }
        LocalPlayer player = minecraft.player;
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        boolean valid = player != null && player.isAlive() && firstPerson && isActiveFor(player);
        if (!valid) {
            clear();
        }
    }

    /** Drops the stored owner and model. */
    public static void clear() {
        model = null;
        ownerItemId = null;
        animationName = null;
    }

    /** Forgets the one-time diagnostics as well; used by a resource reload. */
    public static void reset() {
        clear();
        reportedScaleChannel = false;
    }

    /**
     * @param partialTick the frame's partial tick
     * @return the transform for that frame, for diagnostics and tests; {@code null} when none is active
     */
    public static CameraTransform current(float partialTick) {
        return transformForFrame(partialTick);
    }

    /** @return the animation the last sample came from, or {@code null} */
    public static String currentAnimation() {
        return animationName;
    }

    /**
     * @param degrees an angle in degrees
     * @return the same angle wrapped into {@code (-180, 180]}
     */
    public static float wrap(float degrees) {
        return Mth.wrapDegrees(degrees);
    }
}
