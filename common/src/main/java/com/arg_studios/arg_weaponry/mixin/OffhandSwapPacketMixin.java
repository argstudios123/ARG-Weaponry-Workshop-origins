package com.arg_studios.arg_weaponry.mixin;

import com.arg_studios.arg_weaponry.weapon.equip.OffhandRestriction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The authoritative half of the offhand restriction: a swap request that would move a weapon into the offhand is ignored.
 *
 * <p>{@code ServerGamePacketListenerImpl#handlePlayerAction} is where vanilla performs the swap for
 * {@code SWAP_ITEM_WITH_OFFHAND} (it exchanges the two hands with {@code ServerPlayer#setItemInHand}), so cancelling the call
 * for that action is the only place that can refuse it for a client that bypasses the key hook. Every other action - block
 * breaking, item use, dropping - and every swap of an ordinary item passes through untouched.</p>
 *
 * <p>This mixin belongs to the common (both sides) set; on a client the class exists but the method is never called, so it
 * costs nothing there.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class OffhandSwapPacketMixin {

    /**
     * @param packet   the action packet vanilla received
     * @param callback the injection callback
     */
    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$refuseOffhandSwap(ServerboundPlayerActionPacket packet, CallbackInfo callback) {
        if (packet.getAction() != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
            return;
        }
        ServerGamePacketListenerImpl handler = (ServerGamePacketListenerImpl) (Object) this;
        if (OffhandRestriction.blocksSwap(handler.player.getMainHandItem())) {
            callback.cancel();
        }
    }
}
