package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterInput;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [DrawHolster hook] Intercepts the mouse wheel before vanilla applies the hotbar switch.
 *
 * <h2>Why this exact method</h2>
 * <p>In this Minecraft version {@code MouseHandler.onScroll(long, double, double)} performs the switch itself: it calls
 * {@code Inventory.setSelectedSlot(int)} inside its own body. Cancelling it at the head therefore prevents the slot change
 * completely - there is no second place the scroll is applied.</p>
 *
 * <p>The callback contains exactly one call into the new package, which owns every decision (feature toggles, weapon
 * data, the running holster, the pending target). Nothing else in vanilla's input handling is affected.</p>
 */
@Mixin(MouseHandler.class)
public abstract class EquipScrollMixin {

    /**
     * @param window   the window handle, unused
     * @param xOffset  the horizontal scroll delta, unused
     * @param yOffset  the vertical scroll delta
     * @param callback the injection callback
     */
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$interceptEquipScroll(long window, double xOffset, double yOffset, CallbackInfo callback) {
        if (DrawHolsterInput.onScroll(yOffset)) {
            callback.cancel();
        }
    }
}
