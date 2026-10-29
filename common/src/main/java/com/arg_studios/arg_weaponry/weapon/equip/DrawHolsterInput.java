package com.arg_studios.arg_weaponry.weapon.equip;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;

/**
 * The two raw-input entry points of the delayed hotbar switch, and nothing else.
 *
 * <p>Each mixin contains one delegating call into this class (see {@code EquipScrollMixin} and {@code EquipHotbarKeyMixin});
 * all of the decision lives here and in {@link DrawHolsterManager}. The mixins only say "the player scrolled" or "the
 * player pressed a key"; this class turns that into the slot it means and asks the manager whether the switch must wait for
 * the holster.</p>
 *
 * <h2>Verified interception points for this Minecraft version</h2>
 * <ul>
 *   <li><strong>scroll</strong> - {@code MouseHandler.onScroll(long, double, double)} applies the slot itself
 *   ({@code Inventory.setSelectedSlot}), so cancelling the method at its head really prevents the switch;</li>
 *   <li><strong>hotbar keys</strong> - {@code KeyboardHandler.keyPress(long, int, KeyEvent)} is where vanilla calls
 *   {@code KeyMapping.click}, which is what {@code Minecraft.handleKeybinds()} later consumes before calling
 *   {@code Inventory.setSelectedSlot}. Cancelling {@code keyPress} therefore stops the click from ever being recorded, and
 *   the slot never changes. Cancelling {@code handleKeybinds} instead would swallow every other binding for that tick,
 *   which is why it is not used.</li>
 * </ul>
 * <p>A hotbar slot bound to a mouse button, or a slot changed by another mod or a command, does not pass through these two
 * methods: such a change is not delayed, and the manager treats it as a hard interrupt - exactly the documented rule.</p>
 *
 * <h2>When nothing is intercepted</h2>
 * <p>No screen open and a non-spectator player are checked first, then the global toggles, then the weapon itself. When any
 * of them says "not this weapon", both methods return {@code false} and the mixins let vanilla do exactly what it did
 * before this feature existed.</p>
 */
public final class DrawHolsterInput {

    private DrawHolsterInput() {
    }

    /**
     * Handles one mouse-wheel notch.
     *
     * @param yOffset the scroll delta vanilla reports; positive scrolls towards the previous slot
     * @return whether the scroll was consumed and vanilla must not switch the slot
     */
    public static boolean onScroll(double yOffset) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!canInteract(minecraft)) {
            return false;
        }
        int direction = yOffset > 0.0 ? -1 : yOffset < 0.0 ? 1 : 0;
        if (direction == 0) {
            return false;
        }
        LocalPlayer player = minecraft.player;
        // A scroll during a holster walks from the pending target, so spamming reaches the slot the player expects.
        int base = DrawHolsterManager.scrollBaseSlot(player);
        int target = Math.floorMod(base + direction, Inventory.getSelectionSize());
        return DrawHolsterManager.requestClientHolster(player, target);
    }

    /**
     * Handles one key press of the hotbar bindings.
     *
     * @param event the key event vanilla received
     * @return whether the press was consumed and vanilla must not record the hotbar click
     */
    public static boolean onKeyPress(KeyEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!canInteract(minecraft)) {
            return false;
        }
        int target = hotbarSlot(minecraft, event);
        if (target < 0) {
            return false;
        }
        return DrawHolsterManager.requestClientHolster(minecraft.player, target);
    }

    /**
     * @param minecraft the client
     * @return whether weapon equip input may be read at all: a live player in a world, no screen open, not a spectator, and
     *         the feature enabled
     */
    private static boolean canInteract(Minecraft minecraft) {
        return minecraft != null && minecraft.player != null && minecraft.level != null
                && minecraft.gui.screen() == null
                && !minecraft.player.isSpectator()
                && DrawHolsterConfig.get().enableDrawHolster();
    }

    /**
     * @param minecraft the client
     * @param event     the key event
     * @return the hotbar slot the pressed key selects, or {@code -1} when it is not a hotbar binding
     */
    private static int hotbarSlot(Minecraft minecraft, KeyEvent event) {
        KeyMapping[] slots = minecraft.options.keyHotbarSlots;
        int limit = Math.min(slots.length, Inventory.getSelectionSize());
        for (int index = 0; index < limit; index++) {
            if (slots[index].matches(event)) {
                return index;
            }
        }
        return -1;
    }
}
