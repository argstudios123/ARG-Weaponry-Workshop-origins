package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.client.render.FirstPersonWeaponPass;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the first-person weapon in camera space, before vanilla turns that pose into a hand pose.
 *
 * <h2>What the vanilla method does, and why the injection point matters</h2>
 * <p>{@code GameRenderer.renderItemInHand} is the whole first-person item pass. In this Minecraft
 * version it performs, in order:</p>
 * <ol>
 *   <li>a fresh {@link PoseStack}, then {@code mulPose(inverse(viewRotationMatrix))} - the pose is now
 *       camera space,</li>
 *   <li>{@code bobHurt(camera, poseStack)} - the damage tilt,</li>
 *   <li>{@code bobView(camera, poseStack)} when the {@code View Bobbing} option is on - the walking
 *       bob,</li>
 *   <li>{@code FirstPersonHandsAndItemsRenderer.submitHandsWithItems(...)}, which adds the {@code 0.1}
 *       view-lag rotations and then submits the arm and the item.</li>
 * </ol>
 *
 * <p>Steps 2 to 4 exist so the vanilla hand and item move with the world while walking. For a weapon
 * whose entire pose comes from its own {@code default_view} bone they are exactly the unwanted
 * "held item bobbing", so this injection runs between step 1 and step 2, where the pose is still
 * camera space. {@link FirstPersonWeaponPass} rebuilds that camera-space pose from the camera state and
 * hands it to the renderer; the pose stack vanilla is about to modify is never touched.</p>
 *
 * <p>Nothing global is changed: the {@code View Bobbing} option, the camera orientation and the
 * rendering of every ordinary item are untouched, because only the mod's own weapon geometry is
 * submitted here. The vanilla arm/item submission for a weapon stack is skipped by
 * {@code FirstPersonHandsAndItemsRendererMixin}, and only when {@code WeaponRenderer} can actually draw
 * that weapon.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFirstPersonMixin {

    /**
     * The collector the first-person hand and screen geometry is submitted to.
     *
     * <p>It is the same storage {@code renderItemInHand} passes to
     * {@code submitHandsWithItems}, so the weapon shares the pass, the batching and the depth of the
     * vanilla hand instead of being rendered through a separate path.</p>
     */
    @Shadow
    @Final
    private SubmitNodeStorage handAndScreenSubmitNodeStorage;

    /**
     * Submits the held weapon(s) in camera space.
     *
     * @param cameraState the camera render state, the source of camera space
     * @param playerState the player render state holding both hand stacks
     * @param depthView   the depth view of the hand pass; unused by the weapon pass
     * @param callback    the injection callback
     */
    @Inject(method = "renderItemInHand",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt("
                            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;)V",
                    shift = At.Shift.BEFORE))
    private void argWeaponry$submitFirstPersonWeapon(CameraRenderState cameraState, PlayerRenderState playerState,
                                                     GpuTextureView depthView, CallbackInfo callback) {
        FirstPersonWeaponPass.submit(playerState, cameraState, handAndScreenSubmitNodeStorage);
    }
}
