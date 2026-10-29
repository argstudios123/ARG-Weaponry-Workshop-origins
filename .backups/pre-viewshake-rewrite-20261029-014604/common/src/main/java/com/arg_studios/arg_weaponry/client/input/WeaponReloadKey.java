package com.arg_studios.arg_weaponry.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * The reload keybinding.
 *
 * <p>Uses the established keybinding system rather than a raw keyboard hook: the mapping is created once here
 * and registered by each loader's client initialiser (Fabric {@code KeyMappingHelper}, NeoForge
 * {@code RegisterKeyMappingsEvent}). Vanilla then owns rebinding, conflict detection and the "is a screen
 * open?" rules.</p>
 *
 * <h2>Press, not hold</h2>
 * <p>{@link #handleQueuedPresses()} drains vanilla's click queue with {@code consumeClick()}, which yields one
 * value per discrete press. Holding the key does not queue repeats, so it cannot start a second reload; the
 * client and the server each also refuse a duplicate independently.</p>
 *
 * <p>The default key is <strong>R</strong>, and the binding lives the ARG Weaponry category, together with the mod's other controls.</p>
 */
public final class WeaponReloadKey {

    /** The default key: R. */
    public static final int DEFAULT_KEY = InputConstants.KEY_R;

    /** Translation key of the binding, resolved from the mod's own language file. */
    public static final String TRANSLATION_KEY = "key.arg_weaponry.reload";

    /** The binding itself; the loaders register this instance. */
    public static final KeyMapping KEY =
            new KeyMapping(TRANSLATION_KEY, DEFAULT_KEY, WeaponKeyCategory.CATEGORY);

    private WeaponReloadKey() {
    }

    /**
     * Turns queued presses of the reload key into reload requests.
     *
     * <p>Called once per client tick by both loaders' client entry points. When no hand holds a reloadable
     * weapon, {@code WeaponReloadInput} sends nothing, so the key stays inert instead of interfering with
     * unrelated input.</p>
     */
    public static void handleQueuedPresses() {
        while (KEY.consumeClick()) {
            WeaponReloadInput.requestReload(Minecraft.getInstance().player);
        }
    }
}
