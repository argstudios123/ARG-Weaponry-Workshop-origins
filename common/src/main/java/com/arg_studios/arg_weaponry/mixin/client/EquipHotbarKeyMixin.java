package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterInput;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * [DrawHolster hook] Intercepts the hotbar number keys before vanilla records the binding click.
 *
 * <h2>Where hotbar keys are actually resolved in this Minecraft version</h2>
 * <p>Verified against the 26.3 mappings: {@code KeyboardHandler.keyPress(long, int, KeyEvent)} is where vanilla matches the
 * pressed key against the {@code KeyMapping}s and calls {@code KeyMapping.click(...)}. The slot itself is applied later, on
 * the client tick, by {@code Minecraft.handleKeybinds()}, which consumes that click and calls
 * {@code Inventory.setSelectedSlot(int)}.</p>
 * <p>Cancelling {@code keyPress} therefore stops the click from ever being recorded, so {@code handleKeybinds} finds nothing
 * to apply and the slot never changes. Cancelling {@code handleKeybinds} instead would swallow every other binding for that
 * tick (inventory, drop, swap off-hand, ...), which is why it is not used.</p>
 *
 * <p>The callback contains exactly one call into the new package; the decision - is this a hotbar key, is the feature on,
 * does the held weapon declare a holster clip, is a window already running - lives there.</p>
 */
@Mixin(KeyboardHandler.class)
public abstract class EquipHotbarKeyMixin {

    /**
     * @param window   the window handle, unused
     * @param key      the raw key code, unused (the {@code KeyEvent} carries it)
     * @param event    the key event vanilla received
     * @param callback the injection callback
     */
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$interceptEquipHotbarKey(long window, int key, KeyEvent event, CallbackInfo callback) {
        if (DrawHolsterInput.onKeyPress(event)) {
            callback.cancel();
        }
    }
}
