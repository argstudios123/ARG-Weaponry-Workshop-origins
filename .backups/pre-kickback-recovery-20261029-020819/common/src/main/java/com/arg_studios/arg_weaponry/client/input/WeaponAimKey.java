package com.arg_studios.arg_weaponry.client.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

/**
 * The dedicated aim keybinding.
 *
 * <p>Default: <strong>right mouse button</strong>. Aiming is hold-to-aim, so this binding is read as a state
 * ({@link #isDown()}) rather than as a click queue; it is a separate {@link KeyMapping} and a separate state field from
 * the fire key, so rebinding one cannot affect the other.</p>
 *
 * <p>Vanilla's use/place action is suppressed while a weapon is held ({@code MinecraftUseItemMixin}), so the right button
 * cannot both aim and place a block.</p>
 */
public final class WeaponAimKey {

    /** Translation key of the binding, resolved from the mod's own language file. */
    public static final String TRANSLATION_KEY = "key.arg_weaponry.aim";

    /** The binding itself; the loaders register this instance. */
    public static final KeyMapping KEY = new KeyMapping(TRANSLATION_KEY, InputConstants.Type.MOUSE,
            InputConstants.MOUSE_BUTTON_RIGHT, WeaponKeyCategory.CATEGORY);

    private WeaponAimKey() {
    }

    /**
     * @return whether the aim key is physically down, regardless of focus
     */
    public static boolean isDown() {
        return KEY.isDown();
    }
}
