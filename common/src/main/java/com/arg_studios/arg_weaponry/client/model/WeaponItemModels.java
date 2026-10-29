package com.arg_studios.arg_weaponry.client.model;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.item.WeaponRegistration;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Supplies the interim inventory representation of every dynamically registered weapon.
 *
 * <p>Production weapons take their item id verbatim from a content pack's {@code weapon_id}, so their
 * namespace belongs to the pack. Minecraft resolves {@code assets/<namespace>/items/<path>.json} from
 * the item's own namespace, and content packs are deliberately not mounted as resource packs, so
 * those ids have no item-model definition and would render as the missing-model placeholder.</p>
 *
 * <p>Instead of asking creators to ship files in our namespace, the client falls back to a built-in
 * model for weapon items that have no model of their own: {@link #PLACEHOLDER_ITEM}. That is a plain
 * vanilla item model, so no new inventory renderer, texture or per-weapon asset is involved, and it is
 * completely independent of the first-person Bedrock geometry renderer, which never goes through the
 * item-model system.</p>
 *
 * <p>A model a pack does provide under its own namespace always wins, because the fallback is only
 * consulted when Minecraft baked no model for the id.</p>
 */
public final class WeaponItemModels {

    /**
     * The vanilla item whose baked model is reused as the interim icon. Changing this constant changes
     * the icon of every weapon that has no model of its own.
     */
    public static final Identifier PLACEHOLDER_ITEM = Identifier.fromNamespaceAndPath("minecraft", "crossbow");

    private static final Set<String> LOGGED = new LinkedHashSet<>();

    private WeaponItemModels() {
    }

    /** @return the item ids of every registered weapon. */
    public static List<Identifier> weaponIds() {
        return List.copyOf(WeaponRegistration.registeredWeaponIds());
    }

    /** @return {@code true} when the id belongs to a registered weapon item. */
    public static boolean isWeaponId(Identifier id) {
        return id != null && WeaponRegistration.registeredWeaponIds().contains(id);
    }

    /**
     * @param models the baked item models, keyed by item id
     * @param id     the item id being resolved
     * @return the fallback model, or {@code null} when this id is not a weapon or already has a model
     */
    public static ItemModel fallbackFor(Map<Identifier, ItemModel> models, Identifier id) {
        if (models == null || !isWeaponId(id) || models.containsKey(id)) {
            return null;
        }
        ItemModel placeholder = models.get(PLACEHOLDER_ITEM);
        if (placeholder == null) {
            if (LOGGED.add("missing-placeholder")) {
                Constants.LOG.warn("[ARG] The fallback item model '{}' is not available; weapon items without "
                        + "their own model will render as the missing-model placeholder", PLACEHOLDER_ITEM);
            }
            return null;
        }
        logAssignments(models);
        return placeholder;
    }

    /** Logs once which weapon ids are covered by the fallback, for diagnostics and runtime evidence. */
    private static void logAssignments(Map<Identifier, ItemModel> models) {
        List<String> weaponIds = new ArrayList<>();
        for (Identifier id : WeaponRegistration.registeredWeaponIds()) {
            weaponIds.add(id.toString());
        }
        Set<String> baked = new LinkedHashSet<>();
        for (Identifier id : models.keySet()) {
            baked.add(id.toString());
        }
        List<String> covered = WeaponModelSelection.idsWithoutModel(weaponIds, baked);
        if (LOGGED.add("assignments")) {
            Constants.LOG.info("[ARG] {} weapon item(s) use the fallback inventory model '{}': {}",
                    covered.size(), PLACEHOLDER_ITEM, covered);
        }
    }
}

