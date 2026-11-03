package com.arg_studios.arg_weaponry.client.input;

import com.arg_studios.arg_weaponry.Constants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * The ARG Weaponry keybinding category.
 *
 * <p>Every control this mod adds lives here, instead of in vanilla's Miscellaneous page, so the Controls screen shows one
 * "ARG Weaponry" group with the mod's own label.</p>
 *
 * <p>The category value is created directly from its id. The old {@code KeyMapping.Category.register(Identifier)} call both
 * created the value and added it to vanilla's internal sort list; NeoForge deprecates that method in favour of registering
 * the value through {@code RegisterKeyMappingsEvent#registerCategory}, and Fabric needs no registration call at all because
 * its key-binding API appends the mapping (whose category is this value) to the options. Creating the value here keeps the
 * shared code free of either loader's registration mechanism, and each loader registers it through its own supported
 * API.</p>
 *
 * <p>The label is {@code key.category.arg_weaponry.weapons}, derived by vanilla from the category id; both loaders use the
 * same {@link #CATEGORY} instance, so the Fabric and NeoForge screens cannot drift apart.</p>
 */
public final class WeaponKeyCategory {

    /** The category id, and therefore the language key prefix {@code key.category.arg_weaponry.weapons}. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapons");

    /**
     * The shared category value.
     *
     * <p>It is initialised when this class is first touched, which happens during client initialisation in both loaders.
     * NeoForge registers this exact instance in {@code RegisterKeyMappingsEvent}.</p>
     */
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(ID);

    private WeaponKeyCategory() {
    }
}
