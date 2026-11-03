package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.client.animation.FirstPersonVisualWeapons;
import com.arg_studios.arg_weaponry.client.render.WeaponRenderer;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops the vanilla hand/item pass from drawing an ARG weapon that the mod has already drawn.
 *
 * <h2>What this hook is and is not</h2>
 * <p>{@code submitArmWithItem} is where vanilla applies the arm transform, the swing and the item
 * model's {@code firstperson_*} display transform, and then submits the held item. A weapon is not
 * rendered from here: it is submitted earlier, in clean camera space, by
 * {@code FirstPersonWeaponPass} (see {@code GameRendererFirstPersonMixin}), because everything applied
 * inside this method - and the walking bob and view lag applied before it - belongs to the vanilla
 * hand and would sway a model-driven weapon relative to the crosshair.</p>
 *
 * <p>This injection therefore only cancels: for a {@link WeaponItem} stack, and only when
 * {@link WeaponRenderer#isFirstPersonRenderable} confirms that the weapon pass really can draw it.
 * The decision is stateless and resolves the same display, model and texture caches the renderer
 * resolves, so the two hooks cannot disagree. When the weapon cannot be drawn - a missing model, an
 * unreadable texture, a broken content pack - nothing is cancelled and vanilla keeps rendering the
 * item model, which leaves a visible placeholder instead of an empty hand.</p>
 *
 * <p>Every other path is untouched: the empty hand, maps, food, shields, tridents, ordinary items and
 * the scoping early-out all keep vanilla behaviour, and a hand that no longer holds a weapon releases
 * its visual weapon here so it can never resume animating the weapon it used to hold.</p>
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {

    /**
     * Cancels the vanilla arm/item submission for a weapon that the mod has drawn.
     *
     * @param playerState        the player render state of this frame
     * @param renderState        the first-person hand render state
     * @param partialTicks       the frame interpolation factor
     * @param xRot               the camera pitch
     * @param hand               the hand being submitted
     * @param attack             the attack swing progress
     * @param itemStack          the stack held in that hand
     * @param inverseArmHeight   vanilla's equip-height factor
     * @param poseStack          the pose vanilla would draw in; not used by the weapon
     * @param submitNodeCollector the collector vanilla would submit to; not used by the weapon
     * @param lightCoords        the packed light of the player
     * @param callbackInfo       the injection callback
     */
    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$suppressVanillaWeaponItem(PlayerRenderState playerState,
                                                       FirstPersonHandsAndItemsRenderState renderState, float partialTicks,
                                                       float xRot, InteractionHand hand, float attack,
                                                       ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
                                                       SubmitNodeCollector submitNodeCollector, int lightCoords,
                                                       CallbackInfo callbackInfo) {
        if (renderState.isScoping) {
            return;
        }
        // The decision is made from the hand's live stack (or the visual weapon during a handoff), never from the parameter:
        // vanilla's first-person render state keeps displaying the previous item for the few ticks its equip-lowering
        // animation runs, so the parameter lags behind the held item exactly while this mod plays its own transition. The
        // weapon pass has already rewritten the render state to the same stack, so both hooks agree on what this frame draws.
        ItemStack visual = FirstPersonVisualWeapons.renderStack(hand);
        if (!(visual.getItem() instanceof WeaponItem weaponItem)) {
            // The hand no longer holds a weapon: ownership of its visual weapon ends here, so it can never resume
            // animating the weapon it used to hold.
            FirstPersonVisualWeapons.release(hand);
            return;
        }

        // Already drawn in camera space: only skip vanilla when that draw really happened. A weapon whose resources are
        // broken falls through and is shown as its item model by the vanilla path.
        if (WeaponRenderer.isFirstPersonRenderable(weaponItem)) {
            callbackInfo.cancel();
        }
    }
}
