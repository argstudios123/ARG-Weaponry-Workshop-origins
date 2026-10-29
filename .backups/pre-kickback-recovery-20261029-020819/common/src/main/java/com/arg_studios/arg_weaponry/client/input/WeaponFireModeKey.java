package com.arg_studios.arg_weaponry.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

/**
 * The firing-mode selection keybinding.
 *
 * <p>Default key: <strong>B</strong>. Uses Minecraft's own keybinding system rather than a raw keyboard hook, so
 * rebinding, conflict detection and the "is a screen open?" rules stay vanilla's business. Both loaders register this
 * instance from their client initialisers (Fabric {@code KeyMappingHelper}, NeoForge {@code RegisterKeyMappingsEvent})
 * and drain its click queue once per client tick.</p>
 *
 * <p>Press, not hold: {@code consumeClick()} yields one value per discrete press, so holding B cannot cycle modes every
 * tick. When no hand holds a custom weapon the press does nothing at all, which keeps the key inert for ordinary items
 * and in menus.</p>
 */
public final class WeaponFireModeKey {

    /** The default key: B. */
    public static final int DEFAULT_KEY = InputConstants.KEY_B;

    /** Translation key of the binding, resolved from the mod's own language file. */
    public static final String TRANSLATION_KEY = "key.arg_weaponry.fire_mode";

    /** The binding itself; the loaders register this instance. */
    public static final KeyMapping KEY =
            new KeyMapping(TRANSLATION_KEY, DEFAULT_KEY, WeaponKeyCategory.CATEGORY);

    private WeaponFireModeKey() {
    }

    /**
     * Turns queued presses of the mode key into firing-mode requests.
     *
     * <p>Called once per client tick by both loaders' client entry points, next to the reload key drain.</p>
     */
    public static void handleQueuedPresses() {
        while (KEY.consumeClick()) {
            WeaponFireModeInput.requestCycle(net.minecraft.client.Minecraft.getInstance().player);
        }
    }
}
