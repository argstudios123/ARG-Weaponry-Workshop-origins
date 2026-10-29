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
 * <p>The item shows its logical {@code ammo_name} as a literal name. Content-pack language files are not
 * loaded by this framework yet, so a translation key would be shown verbatim; the logical name is the one
 * string a creator already wrote and can therefore recognise.</p>
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
        return Component.literal(definition.ammoName());
    }
}
