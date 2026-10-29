package com.arg_studios.arg_weaponry.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * The dedicated fire keybinding.
 *
 * <p>Default: <strong>left mouse button</strong>. It is a real {@link KeyMapping}, so the player can rebind it in the
 * Controls screen under the ARG Weaponry category, and it drives the existing 10Q trigger protocol rather than a second
 * firing path:</p>
 * <ul>
 *   <li>{@link #handleQueuedPresses()} drains one value per discrete press and starts the press (a single request for a
 *   semi-automatic weapon, a trigger session for an automatic or burst one);</li>
 *   <li>{@link #handleHeld()} feeds the trigger heartbeat while the button is physically down, which is what keeps
 *   automatic fire alive - a click-consumption-only implementation would stop firing after the first shot;</li>
 *   <li>{@link #handleReleased()} ends the session once, when the button is no longer down.</li>
 * </ul>
 *
 * <p>Vanilla's own attack key is still suppressed by {@code MinecraftAttackMixin} while a weapon is held, so a click is
 * never processed by both paths. When the player holds an ordinary item, this mapping still exists but the weapon input
 * ignores it, and vanilla behaves exactly as it always did.</p>
 */
public final class WeaponFireKey {

    /** Translation key of the binding, resolved from the mod's own language file. */
    public static final String TRANSLATION_KEY = "key.arg_weaponry.fire";

    /** The binding itself; the loaders register this instance. */
    public static final KeyMapping KEY = new KeyMapping(TRANSLATION_KEY, InputConstants.Type.MOUSE,
            InputConstants.MOUSE_BUTTON_LEFT, WeaponKeyCategory.CATEGORY);

    private WeaponFireKey() {
    }

    /**
     * Turns queued presses of the fire key into press edges.
     *
     * <p>Called once per client tick by both loaders' client entry points.</p>
     */
    public static void handleQueuedPresses() {
        Minecraft minecraft = Minecraft.getInstance();
        while (KEY.consumeClick()) {
            if (acceptsInput(minecraft)) {
                WeaponFireInput.onFireKeyPressed(minecraft.player);
            }
        }
    }

    /**
     * Feeds the held-trigger heartbeat while the fire key is physically down.
     *
     * <p>Called once per client tick. It sends nothing unless a session is open and a heartbeat is due, so a held button
     * is not a packet per tick.</p>
     */
    public static void handleHeld() {
        if (acceptsInput(Minecraft.getInstance()) && KEY.isDown()) {
            WeaponFireInput.onTriggerHeld();
        }
    }

    /**
     * Releases the trigger when the fire key is no longer down.
     *
     * <p>Idempotent, so calling it on every tick where the button is up is harmless.</p>
     */
    public static void handleReleased() {
        if (!KEY.isDown()) {
            WeaponFireInput.onTriggerReleased();
        }
    }

    /**
     * @param minecraft the client
     * @return whether weapon input may be read at all this tick: no screen, mouse grabbed, player present
     */
    static boolean acceptsInput(Minecraft minecraft) {
        return minecraft != null && minecraft.gui.screen() == null && minecraft.mouseHandler.isMouseGrabbed()
                && minecraft.player != null && minecraft.player.isAlive();
    }

    /**
     * @param minecraft the client
     * @return whether the fire key is currently held with input focused on the world
     */
    public static boolean isHeld(Minecraft minecraft) {
        return acceptsInput(minecraft) && KEY.isDown();
    }
}
