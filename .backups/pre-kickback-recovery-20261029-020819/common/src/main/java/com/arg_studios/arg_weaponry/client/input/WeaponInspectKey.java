package com.arg_studios.arg_weaponry.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * The weapon inspection keybinding.
 *
 * <p>Default key: <strong>I</strong>. Registered through the established keybinding system by both loaders' client
 * initialisers, next to the reload (R) and firing-mode (B) bindings and the development Animation Tester shortcut
 * (Ctrl + Alt + J), none of which are touched.</p>
 *
 * <p>Press, not hold: {@code consumeClick()} yields one value per discrete press, so holding I cannot restart the clip. The
 * server refuses a second inspection anyway, which is what makes repeated or replayed requests harmless.</p>
 */
public final class WeaponInspectKey {

    /** The default key: I. */
    public static final int DEFAULT_KEY = InputConstants.KEY_I;

    /** Translation key of the binding, resolved from the mod's own language file. */
    public static final String TRANSLATION_KEY = "key.arg_weaponry.inspect";

    /** The binding itself; the loaders register this instance. */
    public static final KeyMapping KEY =
            new KeyMapping(TRANSLATION_KEY, DEFAULT_KEY, WeaponKeyCategory.CATEGORY);

    private WeaponInspectKey() {
    }

    /**
     * Turns queued presses of the inspect key into inspection requests.
     *
     * <p>Called once per client tick by both loaders' client entry points. When the main hand holds no custom weapon, or the
     * weapon declares no applicable inspection animation, nothing is sent.</p>
     */
    public static void handleQueuedPresses() {
        while (KEY.consumeClick()) {
            WeaponInspectInput.requestInspect(Minecraft.getInstance().player);
        }
    }
}
