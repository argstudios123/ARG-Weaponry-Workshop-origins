package com.arg_studios.arg_weaponry.weapon.equip;

import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * What one side last saw in the player's main hand.
 *
 * <p>It exists so a transition can be recognised from the difference between two ticks without re-reading a stack that is
 * already gone: the slot, the registered item id, the definition and the magazine state at that moment. The definition and
 * the magazine state are what let the subsystem reproduce the legacy held-item lifecycle's holster and draw sounds for a
 * change it owns.</p>
 *
 * <p>The slot is part of the observation but <em>not</em> part of the weapon's identity: the equip view controller is bound
 * per slot (see {@code WeaponViewController.Owner}), so a slot change restarts playback and the transition must follow it,
 * but the id of the stack is what says whether the player is still holding the same weapon.</p>
 *
 * @param slot          the main-hand slot the stack occupied
 * @param itemId        the registered id of the item, or {@code null} when the hand was empty
 * @param definition    the weapon definition, or {@code null} when the stack is not a weapon
 * @param emptyMagazine whether the magazine was empty, using the same animation count every selector uses
 */
record HeldWeapon(int slot, String itemId, WeaponDefinition definition, boolean emptyMagazine) {

    /**
     * @param stack the main-hand stack, may be {@code null}
     * @param slot  the main-hand slot
     * @return the observation of that stack
     */
    static HeldWeapon of(ItemStack stack, int slot) {
        WeaponItem item = stack != null && !stack.isEmpty() && stack.getItem() instanceof WeaponItem weapon
                ? weapon : null;
        return new HeldWeapon(slot, itemIdOf(stack), item == null ? null : item.definition(),
                item != null && item.animationAmmoCount(stack) <= 0);
    }

    /**
     * @param itemIdOfStack the registered id of the item held now, or {@code null}
     * @return whether the observation and that id name the same item
     */
    boolean sameItem(String itemIdOfStack) {
        return Objects.equals(itemId, itemIdOfStack);
    }

    /**
     * @param slotNow the slot held now
     * @param idNow   the registered id of that stack, or {@code null}
     * @return whether the held weapon changed, i.e. a different item, a different slot, or both
     */
    boolean differsFrom(int slotNow, String idNow) {
        return slot != slotNow || !sameItem(idNow);
    }

    /**
     * @param stack the stack, may be {@code null}
     * @return its registered item id, or {@code null} when the stack is empty
     */
    static String itemIdOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
}
