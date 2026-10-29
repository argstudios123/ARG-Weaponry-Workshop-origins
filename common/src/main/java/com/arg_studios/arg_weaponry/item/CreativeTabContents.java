package com.arg_studios.arg_weaponry.item;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared contents of ARG's creative tabs.
 *
 * <p>Both loaders fill their tabs from this one accessor, so a weapon and the ammunition it uses can never be
 * populated on one loader and forgotten on the other. Each pack's tab lists that pack's weapons first (in the
 * existing item-id order) and then its ammunition items, which is deterministic across loaders and launches.</p>
 *
 * <p>Items, not stacks: an {@link net.minecraft.world.item.ItemStack} needs bound item components, which do
 * not exist yet when a tab is created. Each loader builds its stacks inside its own content callback.</p>
 *
 * <p>Nothing here grants a survival acquisition path. Ammunition appears in the creative tab because that is
 * what a creative tab is; no recipe or loot table is added, so ammunition that is only meant for creative use
 * stays creative-only.</p>
 */
public final class CreativeTabContents {

    private CreativeTabContents() {
    }

    /**
     * @return one entry per registered tab, mapping the tab id to the items it should contain, in
     *         deterministic order
     */
    public static Map<Identifier, List<Item>> items() {
        Map<Identifier, List<Item>> weaponItems = WeaponRegistration.tabItems();
        Map<Identifier, List<Item>> contents = new LinkedHashMap<>();
        for (WeaponRegistration.RegisteredTab tab : WeaponRegistration.registeredTabs()) {
            List<Item> items = new ArrayList<>(weaponItems.getOrDefault(tab.tabId(), List.of()));
            items.addAll(AmmunitionRegistration.itemsOf(tab.pack().id()));
            contents.put(tab.tabId(), List.copyOf(items));
        }
        return Collections.unmodifiableMap(contents);
    }
}
