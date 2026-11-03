package com.arg_studios.arg_weaponry.client.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure decision logic for the item model of dynamically registered weapons.
 *
 * <p>Kept free of Minecraft types so it can be tested without a running game - which matters here, because the decision this
 * class makes is the one that failed silently at runtime: a weapon whose id <em>did</em> have a baked item model was left alone,
 * so the static 3D model was never installed and every non-first-person context kept drawing the flat icon.</p>
 *
 * <h2>Why a baked model no longer means "leave it alone"</h2>
 * <p>{@code ContentPackClientPack} mounts a content pack as a client resource pack and synthesises two documents for every
 * weapon whose display declares a {@code slot} texture: {@code assets/<ns>/items/<weapon>.json} and a flat
 * {@code minecraft:item/generated} model pointing at that icon. Minecraft therefore <em>does</em> bake an item model for a
 * weapon id - not the pack's own 3D representation, but this mod's own HUD icon. Treating "a model exists for this id" as "the
 * pack owns this item" is what hid every weapon in the world.</p>
 *
 * <p>The rule is therefore: a registered weapon id is always taken over by {@link WeaponStaticItemModel}, and the model baked
 * for that id becomes the <em>icon</em> the static model falls back to (the GUI, the first-person contexts, and any weapon whose
 * display cannot be resolved). A pack that ships its own {@code items/<id>.json} keeps that document as its icon, exactly as
 * before - the only thing that changes is that world contexts draw the weapon's own geometry.</p>
 */
public final class WeaponModelSelection {

    /**
     * Where the icon of a static weapon - the flat model drawn in the GUI, in the first-person contexts and whenever the 3D
     * model cannot be drawn - comes from.
     */
    public enum Icon {

        /** The model Minecraft baked for the weapon's own id: a pack document, or the slot icon this mod synthesises. */
        OWN_MODEL,

        /** The documented placeholder model, used when nothing was baked for the weapon's id. */
        PLACEHOLDER,

        /** No icon at all: nothing was baked for the id and the placeholder is unavailable. */
        NONE
    }

    /**
     * The answer to "what should Minecraft's item model be for this id".
     *
     * @param installsStaticModel whether the static 3D weapon model replaces the baked model
     * @param icon                the icon model the static model falls back to, never {@code null}
     */
    public record Selection(boolean installsStaticModel, Icon icon) {

        /** The decision for an id that is not a registered weapon: nothing is taken over. */
        public static final Selection NOT_A_WEAPON = new Selection(false, Icon.NONE);

        public Selection {
            if (icon == null) {
                icon = Icon.NONE;
            }
        }

        /** @return {@code true} when the static model has an icon to fall back to. */
        public boolean hasIcon() {
            return icon != Icon.NONE;
        }
    }

    private WeaponModelSelection() {
    }

    /**
     * Decides the item model of one id.
     *
     * @param registeredWeapon     whether the id belongs to a registered weapon item
     * @param bakedModelForId      whether Minecraft baked an item model for the id itself
     * @param placeholderAvailable whether the documented placeholder model was baked
     * @return the decision; {@link Selection#NOT_A_WEAPON} for an id that is not a weapon
     */
    public static Selection select(boolean registeredWeapon, boolean bakedModelForId, boolean placeholderAvailable) {
        if (!registeredWeapon) {
            return Selection.NOT_A_WEAPON;
        }
        // A weapon is always drawn by the static model in world contexts. Which model was baked for its id only decides what
        // the icon is, never whether the static model is installed - that is the distinction the missing weapons came from.
        Icon icon = bakedModelForId ? Icon.OWN_MODEL : placeholderAvailable ? Icon.PLACEHOLDER : Icon.NONE;
        return new Selection(true, icon);
    }

    /**
     * @param weaponIds    the item ids of the registered weapons, in any order
     * @param idsWithModel the item ids Minecraft baked a model for
     * @return the weapon ids that have no model of their own and therefore rely on the placeholder icon, sorted and
     *         de-duplicated; empty when every weapon has a model of its own
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
