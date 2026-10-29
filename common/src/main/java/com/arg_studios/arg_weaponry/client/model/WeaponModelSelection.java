package com.arg_studios.arg_weaponry.client.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure decision logic for the interim inventory representation of dynamically registered weapons.
 *
 * <p>Kept free of Minecraft types so it can be tested without a running game.</p>
 */
public final class WeaponModelSelection {

    private WeaponModelSelection() {
    }

    /**
     * @param weaponIds    the item ids of the registered weapons, in any order
     * @param idsWithModel the item ids Minecraft baked a model for
     * @return the weapon ids that still need the fallback model, sorted and de-duplicated; empty when
     *         every weapon already has a model of its own
     */
    public static List<String> idsWithoutModel(Collection<String> weaponIds, Collection<String> idsWithModel) {
        if (weaponIds == null || weaponIds.isEmpty()) {
            return List.of();
        }
        Set<String> existing = idsWithModel == null ? Set.of() : new LinkedHashSet<>(idsWithModel);
        List<String> missing = new ArrayList<>();
        for (String id : new LinkedHashSet<>(weaponIds)) {
            if (!existing.contains(id)) {
                missing.add(id);
            }
        }
        missing.sort(String::compareTo);
        return List.copyOf(missing);
    }
}
