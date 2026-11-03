package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.weapon.equip.OffhandRestriction;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(Minecraft.class)
public abstract class OffhandSwapMixin {

    /** Removes the queued offhand-swap clicks of this tick when the swap would move a weapon into the offhand. */
    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void argWeaponry$blockOffhandSwap(CallbackInfo callback) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.player == null
                || !OffhandRestriction.blocksSwap(minecraft.player.getMainHandItem())) {
            return;
        }
        while (minecraft.options.keySwapOffhand.consumeClick()) {
            // Drained on purpose: the click must not reach vanilla, which would send the swap request.
        }
    }
}
