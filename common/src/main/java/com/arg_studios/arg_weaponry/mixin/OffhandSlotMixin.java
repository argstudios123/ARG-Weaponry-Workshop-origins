package com.arg_studios.arg_weaponry.mixin;

import com.arg_studios.arg_weaponry.weapon.equip.OffhandRestriction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The menu half of the offhand restriction: a click that would place a weapon in the offhand is refused by the slot itself.
 *
 * <p>{@code AbstractContainerMenu} asks {@link Slot#mayPlace(ItemStack)} before every click that puts a stack into a slot -
 * pickup, place, swap and quick-move alike - so returning {@code false} here refuses the click instead of letting it happen
 * and repairing the inventory afterwards. That matters twice: the client applies the same click locally before the server
 * answers, so the weapon would otherwise be visible in the offhand for a round trip, and creative mode re-wraps the player's
 * inventory slots in {@code CreativeModeInventoryScreen.SlotWrapper}, whose own {@code mayPlace} delegates to this one, so the
 * survival and creative screens are covered by the same override.</p>
 *
 * <p>Nothing is lost by refusing: vanilla leaves the carried stack where it is and only the click is dropped, which is why
 * this is preferred over silently ignoring a write in {@code Inventory#setItem} - that would desync the menu and could delete
 * the stack a caller believed it had placed.</p>
 */
@Mixin(Slot.class)
public abstract class OffhandSlotMixin {

    /**
     * @param stack    the stack a click would put into this slot
     * @param callback the injection callback
     */
    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$refuseWeaponInOffhand(ItemStack stack, CallbackInfoReturnable<Boolean> callback) {
        Slot self = (Slot) (Object) this;
        // getContainerSlot() is the container index. The public 'index' field is a menu-level bookkeeping value that the
        // constructor never sets - the creative screen assigns it to its own wrappers - so reading it made this guard
        // silently never match, which is why a gun could still be clicked into the offhand.
        if (OffhandRestriction.blocksSlot(self.container, self.getContainerSlot(), stack)) {
            callback.setReturnValue(false);
        }
    }
}
