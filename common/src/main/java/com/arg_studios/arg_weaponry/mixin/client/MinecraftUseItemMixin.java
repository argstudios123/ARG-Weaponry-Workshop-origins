package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.client.input.WeaponFireInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses vanilla's right-click use/place action while an ARG weapon is held.
 *
 * <p>Milestone 10S gives aiming its own binding on the right mouse button, so the same click must not also use an item or
 * place a block. {@code Minecraft#startUseItem} is the single place both the click queue and the held-button path go
 * through ({@code handleKeybinds} calls it from {@code keyUse.consumeClick()} and again while {@code keyUse.isDown()}), so
 * cancelling it there covers every variant without touching {@code MultiPlayerGameMode}, item use or block placement
 * anywhere else.</p>
 *
 * <p>Nothing changes for a player who is not holding a weapon: the injection returns immediately, and vanilla's use,
 * placement and interaction behaviour is exactly as it was.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftUseItemMixin {

    /**
     * Cancels one use/place attempt while a weapon is held in the main hand.
     *
     * @param callback the injection callback
     */
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$suppressWeaponUse(CallbackInfo callback) {
        Minecraft minecraft = (Minecraft) (Object) this;
        LocalPlayer player = minecraft.player;
        if (player != null && WeaponFireInput.holdsWeapon(player, InteractionHand.MAIN_HAND)) {
            callback.cancel();
        }
    }
}
