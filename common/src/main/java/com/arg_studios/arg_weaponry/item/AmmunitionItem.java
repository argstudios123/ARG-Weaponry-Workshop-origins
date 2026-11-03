package com.arg_studios.arg_weaponry.item;

import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The Minecraft item that represents one ammunition definition.
 *
 * <p>An ammunition item carries no per-stack state: unlike a weapon it has no magazine, no fire mode and no
 * data component, so the item itself only remembers which pack and definition produced it. Two stacks of
 * {@code arg_pack:dmr_ammo} are therefore interchangeable, which is what lets
 * {@code ReserveAmmunition} count them by item id alone.</p>
 *
 * <h2>Stack size</h2>
 * <p>Every ammunition definition uses {@link #DEFAULT_STACK_SIZE}, a plain vanilla ammunition-sized stack.
 * The schema deliberately has no stack-size field: one sensible default covers every definition, and adding a
 * per-definition property without a demonstrated need would only create content packs that disagree.</p>
 *
 * <h2>Name</h2>
 * <p>The item shows the translation key {@code item.<namespace>.<ammo_id>}, which the content pack translates in its own
 * {@code assets/<namespace>/lang/en_us.json}. When the pack does not translate it, {@link ItemNames} falls back to the
 * declared {@code ammo_name}, humanised - a raw key is never shown.</p>
 */
public class AmmunitionItem extends Item {

    /** Stack size used by every ammunition item. */
    public static final int DEFAULT_STACK_SIZE = 64;

    private final ContentPack pack;
    private final AmmunitionDefinition definition;

    public AmmunitionItem(ContentPack pack, AmmunitionDefinition definition, Item.Properties properties) {
        super(properties);
        this.pack = pack;
        this.definition = definition;
    }

    /** @return the content pack that supplied this ammunition. */
    public ContentPack pack() {
        return pack;
    }

    /** @return the identity of the content pack that supplied this ammunition. */
    public String packId() {
        return pack.id();
    }

    /** @return the parsed ammunition definition behind this item. */
    public AmmunitionDefinition definition() {
        return definition;
    }

    /** @return the logical {@code ammo_name} this item represents. */
    public String ammoName() {
        return definition.ammoName();
    }

    @Override
    public Component getName(ItemStack itemStack) {
        // A translated name when the content pack provides one, otherwise the declared logical name - never a raw key.
        return ItemNames.of(getDescriptionId(), definition.ammoName());
    }
}
