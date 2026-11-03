package com.arg_studios.arg_weaponry.weapon.equip;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The one rule that keeps a weapon of this mod out of the offhand.
 *
 * <h2>Why a separate class</h2>
 * <p>Every decision and every effect of the restriction is here, and the four places that enforce it only ask this class:
 * the client key hook ({@code OffhandSwapMixin}) so {@code F} never sends a swap, the server packet gate
 * ({@code OffhandSwapPacketMixin}) so a client that sends one anyway is ignored, the menu slot itself
 * ({@code OffhandSlotMixin}) so a click in the survival or creative inventory cannot even place it there, and the server
 * tick ({@code ServerEquipAuthority}) so a stack that arrives through any remaining route - {@code /item replace}, another
 * mod - is removed again in the same tick.</p>
 *
 * <p>It is deliberately narrow: it only ever matches {@link WeaponItem}, so ordinary offhand items, shields, totems and
 * every weapon of another mod (Vic's Point Blank included) keep vanilla behaviour. It is also the only place that consults
 * {@link DrawHolsterConfig.Settings#blockGunsInOffhand()}, so a future dual-wield implementation can switch the rule off, or
 * replace this class, without touching the hooks.</p>
 */
public final class OffhandRestriction {

    private OffhandRestriction() {
    }

    /** @return whether the restriction is active; the single switch a dual-wield implementation would turn off. */
    public static boolean isEnabled() {
        return DrawHolsterConfig.get().blockGunsInOffhand();
    }

    /**
     * @param stack the stack to test, may be {@code null}
     * @return whether that stack is a weapon this restriction owns
     */
    public static boolean isRestricted(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof WeaponItem;
    }

    /**
     * @param mainHand the stack the player holds in the main hand, may be {@code null}
     * @return whether swapping hands would move a weapon into the offhand, i.e. whether the swap must be refused
     */
    public static boolean blocksSwap(ItemStack mainHand) {
        return isEnabled() && isRestricted(mainHand);
    }

    /**
     * @param container the container a menu slot belongs to
     * @param index     the slot index inside that container
     * @param stack     the stack a click would put there, may be {@code null}
     * @return whether that menu slot must refuse the stack because it is the offhand slot of a player inventory
     */
    public static boolean blocksSlot(Container container, int index, ItemStack stack) {
        return isEnabled()
                && container instanceof Inventory
                && index == Inventory.SLOT_OFFHAND
                && isRestricted(stack);
    }

    /**
     * Removes a weapon that reached the offhand by any route other than the swap key.
     *
     * <p>The stack is never destroyed: it moves to the selected slot when that slot is free, otherwise to the first free
     * inventory slot, and only when the inventory is completely full is it dropped in front of the player. The inventory is
     * marked changed so the client is corrected in the same tick, which is what makes {@code /item replace entity ...
     * weapon.offhand} end up with the weapon anywhere but the offhand.</p>
     *
     * @param player the player to enforce the rule for, may be {@code null}
     */
    public static void enforce(ServerPlayer player) {
        if (player == null || !isEnabled()) {
            return;
        }
        Inventory inventory = player.getInventory();
        ItemStack offhand = inventory.getItem(Inventory.SLOT_OFFHAND);
        if (!isRestricted(offhand)) {
            return;
        }
        ItemStack evicted = offhand.copy();
        inventory.setItem(Inventory.SLOT_OFFHAND, ItemStack.EMPTY);
        int target = inventory.getSelectedSlot();
        if (!inventory.getItem(target).isEmpty()) {
            target = inventory.getFreeSlot();
        }
        if (target >= 0 && target != Inventory.SLOT_OFFHAND) {
            inventory.setItem(target, evicted);
            Constants.LOG.info("[ARG] Moved '{}' out of the offhand into slot {}",
                    evicted.getItem().getDescriptionId(), target);
        } else {
            // Nowhere to put it: dropping it is the only way to keep the rule without deleting the stack.
            player.drop(evicted, false, Prediction.SERVER_ONLY);
            Constants.LOG.info("[ARG] Dropped '{}' because the offhand may not hold it and the inventory is full",
                    evicted.getItem().getDescriptionId());
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
        if (player.connection != null) {
            // broadcastChanges only reaches the client through the menu that is open, and a chest or a crafting table does
            // not contain the offhand slot. The explicit update makes the corrected hand reach the client whatever screen it
            // is looking at, so a command that used the offhand cannot leave a ghost weapon there.
            player.connection.send(inventory.createInventoryUpdatePacket(Inventory.SLOT_OFFHAND));
        }
    }
}
