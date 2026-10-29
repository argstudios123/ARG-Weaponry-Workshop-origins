package com.arg_studios.arg_weaponry.client.input;

import com.arg_studios.arg_weaponry.Constants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * The ARG Weaponry keybinding category.
 *
 * <p>Every control this mod adds lives here, instead of in vanilla's Miscellaneous page, so the Controls screen shows one
 * "ARG Weaponry" group with the mod's own label. The category is created through {@link KeyMapping.Category#register},
 * which is the supported 26.3 mechanism and also fixes the ordering of the pages: a registered category is appended after
 * the built-in ones.</p>
 *
 * <p>The label is {@code key.category.arg_weaponry.weapons}, derived by vanilla from the category id; both loaders register
 * the same {@link #CATEGORY} instance, so the Fabric and NeoForge screens cannot drift apart.</p>
 */
public final class WeaponKeyCategory {

    /** The category id, and therefore the language key prefix {@code key.category.arg_weaponry.weapons}. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapons");

    /**
     * The shared category instance.
     *
     * <p>Registering twice throws, so this field is the single registration point and every binding below refers to it.
     * It is initialised when this class is first touched, which happens during client initialisation in both loaders.</p>
     */
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(ID);

    private WeaponKeyCategory() {
    }
}
