package com.arg_studios.arg_weaponry.weapon;

import com.arg_studios.arg_weaponry.pack.ContentPack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A pure-data description of everything that should be registered, produced before any Minecraft
 * registry is touched.
 *
 * <p>Planning is separate from registration so the mapping can be tested without a running game, and
 * so both loaders can apply the same plan at their own safe registration point.</p>
 *
 * @param weapons              the weapon items to register, in deterministic order
 * @param tabs                 one entry per content pack, in discovery order
 * @param skippedWithoutIdentity how many parsed definitions have no usable {@code weapon_id}
 */
public record WeaponRegistrationPlan(List<PlannedWeapon> weapons, List<PackTab> tabs, int skippedWithoutIdentity) {

    public WeaponRegistrationPlan {
        weapons = List.copyOf(weapons);
        tabs = List.copyOf(tabs);
    }

    public static WeaponRegistrationPlan empty() {
        return new WeaponRegistrationPlan(List.of(), List.of(), 0);
    }

    /**
     * One weapon item to register.
     *
     * @param pack       the owning content pack
     * @param definition the parsed definition
     * @param itemId     the Minecraft item id, taken verbatim from the authoritative {@code weapon_id}
     */
    public record PlannedWeapon(ContentPack pack, WeaponDefinition definition, ResourceId itemId) {
    }

    /**
     * One creative tab, with exactly the planned weapons of that pack.
     *
     * @param pack  the owning content pack
     * @param tabId the creative tab id, namespaced under this mod
     * @param weapons the weapons that belong in this tab
     */
    public record PackTab(ContentPack pack, ResourceId tabId, List<PlannedWeapon> weapons) {

        public PackTab {
            weapons = List.copyOf(weapons);
        }
    }

    /** @return the planned weapons of one pack. */
    public List<PlannedWeapon> weaponsOf(String packId) {
        return weapons.stream().filter(weapon -> weapon.pack().id().equals(packId)).toList();
    }

    /**
     * Appends additional planned weapons (development-only weapons) while keeping every invariant of
     * the plan: the weapon list stays sorted by item id, and each pack tab keeps exactly the planned
     * weapons of its own pack, in the same order.
     *
     * @param additional the weapons to append
     * @return this plan when {@code additional} is empty, otherwise a new plan
     */
    public WeaponRegistrationPlan withAdditionalWeapons(List<PlannedWeapon> additional) {
        if (additional == null || additional.isEmpty()) {
            return this;
        }

        List<PlannedWeapon> merged = new ArrayList<>(weapons);
        merged.addAll(additional);
        merged.sort(Comparator.comparing(weapon -> weapon.itemId().asString()));

        List<PackTab> mergedTabs = new ArrayList<>(tabs.size());
        for (PackTab tab : tabs) {
            List<PlannedWeapon> tabWeapons = new ArrayList<>(tab.weapons());
            for (PlannedWeapon weapon : additional) {
                if (weapon.pack().id().equals(tab.pack().id())) {
                    tabWeapons.add(weapon);
                }
            }
            tabWeapons.sort(Comparator.comparing(weapon -> weapon.itemId().asString()));
            mergedTabs.add(new PackTab(tab.pack(), tab.tabId(), tabWeapons));
        }

        return new WeaponRegistrationPlan(merged, mergedTabs, skippedWithoutIdentity);
    }
}
