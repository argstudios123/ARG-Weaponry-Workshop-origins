package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.client.input.WeaponFireInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(Minecraft.class)
public abstract class MinecraftAttackMixin {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$requestWeaponFire(CallbackInfoReturnable<Boolean> callback) {
        Minecraft minecraft = (Minecraft) (Object) this;
        LocalPlayer player = minecraft.player;
        if (player == null || !WeaponFireInput.holdsWeapon(player, InteractionHand.MAIN_HAND)) {
            return;
        }
        // Firing itself is driven by the dedicated fire key (WeaponFireKey), so this injection only suppresses vanilla's
        // attack. Processing the same click here as well would fire two shots per press.
        callback.setReturnValue(false);
    }


    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$suppressHeldAttack(boolean down, CallbackInfo callback) {
        if (!down) {
            // Vanilla uses this branch to stop an in-progress break and to reset the miss timer; leave it alone. The
            // trigger is released by the fire key's own tick handler.
            return;
        }
        Minecraft minecraft = (Minecraft) (Object) this;
        LocalPlayer player = minecraft.player;
        if (player != null && WeaponFireInput.holdsWeapon(player, InteractionHand.MAIN_HAND)) {
            callback.cancel();
        }
    }
}
