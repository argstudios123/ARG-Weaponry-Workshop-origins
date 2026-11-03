package com.arg_studios.arg_weaponry.item;

import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;

/**
 * Resolves the display name of a registered item from its translation key, with a readable fallback.
 *
 * <p>Every item this mod registers gets its name from {@code item.<namespace>.<path>}, which a content pack translates in
 * its own language file. When that translation is missing - a pack without a language file, or a language the pack did not
 * translate - the raw key would be shown, which is the "untranslated item" report. The fallback is the name the creator
 * already wrote in the definition, humanised by {@link ItemDisplayName}, so the item is always readable and never shows a
 * key.</p>
 */
public final class ItemNames {

    private ItemNames() {
    }

    /**
     * @param descriptionId the item's translation key, may be {@code null}
     * @param declaredName  the name the definition declared, used when the key is untranslated
     * @return the translated name when it exists, otherwise a readable literal name
     */
    public static Component of(String descriptionId, String declaredName) {
        if (descriptionId != null && !descriptionId.isBlank() && Language.getInstance().has(descriptionId)) {
            return Component.translatable(descriptionId);
        }
        String humanised = ItemDisplayName.humanize(declaredName);
        if (humanised.isBlank()) {
            return Component.literal(descriptionId == null ? "" : descriptionId);
        }
        return Component.literal(humanised);
    }
}
