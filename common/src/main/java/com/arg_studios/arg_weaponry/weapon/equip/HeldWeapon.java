package com.arg_studios.arg_weaponry.weapon.equip;

import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;


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
