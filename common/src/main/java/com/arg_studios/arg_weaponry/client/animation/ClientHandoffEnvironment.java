package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterConfig;
import com.arg_studios.arg_weaponry.weapon.equip.EquipActionPayload;
import com.arg_studios.arg_weaponry.weapon.equip.EquipPhase;
import com.arg_studios.arg_weaponry.weapon.handoff.HandoffEnvironment;
import com.arg_studios.arg_weaponry.weapon.handoff.WeaponIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.ItemStack;

/**
 * The client implementation of {@link HandoffEnvironment}: the local player, the inventory, and the two notifications the
 * server needs.
 *
 * <p>The inventory stays client-authoritative, exactly as it is in vanilla: this class applies the deferred
 * {@code setSelectedSlot} once, at the boundary, and then asks the game mode to send the carried-item update, which is the
 * same call the reference implementations make. No server state is manipulated to fake a visual transition, and the server
 * is only told that a transition started or was abandoned so it can block weapon actions for the window and let other
 * players hear the holster.</p>
 */
public final class ClientHandoffEnvironment implements HandoffEnvironment {

    /** Request numbers must be strictly increasing per connection; the server rejects replays. */
    private int requestSequence;

    @Override
    public boolean canTransition() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        return player != null && minecraft.level != null && player.isAlive() && !player.isSpectator()
                && DrawHolsterConfig.get().enableDrawHolster();
    }

    @Override
    public int hotbarSize() {
        return net.minecraft.world.entity.player.Inventory.getSelectionSize();
    }

    @Override
    public int selectedSlot() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? -1 : player.getInventory().getSelectedSlot();
    }

    @Override
    public WeaponIdentity identityAt(int slot) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || slot < 0 || slot >= player.getInventory().getContainerSize()) {
            return WeaponIdentity.NONE;
        }
        ItemStack stack = player.getInventory().getItem(slot);
        if (!(stack.getItem() instanceof WeaponItem item)) {
            return WeaponIdentity.NONE;
        }
        return new WeaponIdentity(String.valueOf(BuiltInRegistries.ITEM.getKey(item)), slot);
    }

    @Override
    public WeaponIdentity selectedIdentity() {
        return identityAt(selectedSlot());
    }

    @Override
    public void selectSlot(int slot) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || slot < 0 || slot >= hotbarSize()) {
            return;
        }
        if (player.getInventory().getSelectedSlot() != slot) {
            player.getInventory().setSelectedSlot(slot);
        }
        if (player.connection != null) {
            // Vanilla only tells the server about the new carried slot on the next interaction (that is what
            // MultiPlayerGameMode#ensureHasSentCarriedItem does, and it is private). Sending it here means the server knows
            // the player switched at the handoff boundary instead of at the next click.
            player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    @Override
    public void notifySwitchStarted(int targetSlot) {
        if (targetSlot < 0) {
            return;
        }
        Services.NETWORK.sendToServer(new EquipActionPayload(EquipActionPayload.ACTION_START_HOLSTER, targetSlot,
                ++requestSequence));
    }

    @Override
    public void notifySwitchCancelled() {
        Services.NETWORK.sendToServer(new EquipActionPayload(EquipActionPayload.ACTION_CANCEL_HOLSTER,
                EquipPhase.NO_SLOT, ++requestSequence));
    }

    @Override
    public double nowSeconds() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    @Override
    public boolean actionOwnsPlayback() {
        VisualWeapon weapon = VisualWeapons.instance().active(net.minecraft.world.InteractionHand.MAIN_HAND);
        if (weapon == null) {
            return false;
        }
        WeaponViewController animator = weapon.animator();
        return animator.isReloadPlaying() || animator.isFirePlaying() || animator.isInspectPlaying();
    }

    /** Resets the request sequence; used when the client leaves a world, because a new connection starts it over. */
    void resetSequence() {
        requestSequence = 0;
        Constants.LOG.debug("[ARG] Handoff request sequence reset for a new session");
    }
}
