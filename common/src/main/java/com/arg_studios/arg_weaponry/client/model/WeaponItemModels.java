package com.arg_studios.arg_weaponry.client.model;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.item.WeaponRegistration;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Supplies the item model of every dynamically registered weapon.
 *
 * <p>Production weapons take their item id verbatim from a content pack's {@code weapon_id}, so their namespace belongs to the
 * pack. Minecraft resolves {@code assets/<namespace>/items/<path>.json} from the item's own namespace, and a content pack does
 * not ship one: it describes its weapons with display documents instead. Without help those ids would therefore render as the
 * missing-model placeholder.</p>
 *
 * <p>Instead of asking creators to ship files in our namespace, the client routes those ids to
 * {@link WeaponStaticItemModel}: in every world context - another player's hands, the ground, an item frame, a shelf, a
 * container - it draws the weapon's own Bedrock geometry with the display transform its pack authored, and it keeps the flat
 * icon for the GUI and for any weapon whose display configuration cannot be resolved. The animated first-person renderer is
 * completely separate: it never goes through the item-model system.</p>
 *
 * <h2>Why a baked model is not a reason to keep the baked model</h2>
 * <p>An id that Minecraft baked an item model for is <em>not</em> an id the pack owns. {@code ContentPackClientPack} mounts a
 * content pack as a client resource pack and synthesises {@code assets/<ns>/items/<weapon>.json} plus a flat
 * {@code minecraft:item/generated} model for every weapon that declares a {@code slot} texture, so the shipped weapons do have a
 * baked model - a 2D HUD icon, not a world model. Returning early for those ids (as this class did) left the whole static
 * pipeline unused and made every weapon invisible in the world; {@link WeaponModelSelection} now decides that the static model is
 * always installed and only the <em>icon</em> comes from the baked model.</p>
 */
public final class WeaponItemModels {

    /**
     * The vanilla item whose baked model is reused as the icon of a weapon that has no item model of its own. Changing this
     * constant changes the icon of every such weapon.
     */
    public static final Identifier PLACEHOLDER_ITEM = Identifier.fromNamespaceAndPath("minecraft", "crossbow");

    private static final Set<String> LOGGED = new LinkedHashSet<>();

    /**
     * One static-model wrapper per icon model instance.
     *
     * <p>{@code ItemModelResolver} asks for the item model of every item it draws on every frame, so wrapping the icon per call
     * would allocate per item per frame. Keying by the icon instance is also what makes a resource reload produce a fresh
     * wrapper: the reload replaces the baked icon.</p>
     */
    private static final Map<ItemModel, ItemModel> WRAPPERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The wrapper used when a weapon has no icon model at all; it draws the static model and nothing else. */
    private static final ItemModel NO_ICON_WRAPPER = new WeaponStaticItemModel(null);

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
     * Chooses the item model Minecraft must use for one id.
     *
     * @param models the baked item models, keyed by item id
     * @param id     the item id being resolved
     * @return the static weapon model, or {@code null} when this id is not a registered weapon and must keep the model
     *         Minecraft baked for it
     */
    public static ItemModel staticModelFor(Map<Identifier, ItemModel> models, Identifier id) {
        if (models == null || id == null) {
            return null;
        }
        // A baked entry can exist and still be unusable - a pack document that failed to bake resolves to null - so the icon is
        // chosen from the models themselves rather than from "the id is present in the map".
        ItemModel own = models.get(id);
        ItemModel placeholder = models.get(PLACEHOLDER_ITEM);
        WeaponModelSelection.Selection selection =
                WeaponModelSelection.select(isWeaponId(id), own != null, placeholder != null);
        if (!selection.installsStaticModel()) {
            return null;
        }

        Identifier iconId = switch (selection.icon()) {
            case OWN_MODEL -> id;
            case PLACEHOLDER -> PLACEHOLDER_ITEM;
            case NONE -> null;
        };
        ItemModel icon = switch (selection.icon()) {
            case OWN_MODEL -> own;
            case PLACEHOLDER -> placeholder;
            case NONE -> null;
        };
        if (selection.icon() == WeaponModelSelection.Icon.NONE && LOGGED.add("no-icon")) {
            Constants.LOG.warn("[ARG] Neither a usable icon model for a weapon id nor the placeholder item model '{}' is "
                    + "available; the weapons draw their static model only where that icon would have been used", PLACEHOLDER_ITEM);
        }
        logSelection(id, iconId);
        logSummary(models);
        return wrapperFor(icon);
    }

    /**
     * @param icon the baked model the static model falls back to, may be {@code null}
     * @return the shared wrapper for that icon, never {@code null}
     */
    private static ItemModel wrapperFor(ItemModel icon) {
        if (icon == null) {
            return NO_ICON_WRAPPER;
        }
        return WRAPPERS.computeIfAbsent(icon, WeaponStaticItemModel::new);
    }

    /**
     * Reports once per weapon which model the world contexts draw and which icon the flat contexts keep, so the log states the
     * decision instead of leaving it to be inferred from what is or is not on screen.
     */
    private static void logSelection(Identifier id, Identifier iconId) {
        if (!LOGGED.add("select:" + id)) {
            return;
        }
        Constants.LOG.info("[ARG] Weapon item '{}' draws its static 3D model in world contexts (hands, ground, item frames, "
                + "shelves, containers); its flat icon model is {}", id, iconId == null ? "(none)" : "'" + iconId + "'");
    }

    /** Logs once how many weapon ids are covered and how many of them rely on the built-in placeholder icon. */
    private static void logSummary(Map<Identifier, ItemModel> models) {
        if (!LOGGED.add("summary")) {
            return;
        }
        List<String> weaponIds = new ArrayList<>();
        for (Identifier id : WeaponRegistration.registeredWeaponIds()) {
            weaponIds.add(id.toString());
        }
        Set<String> baked = new LinkedHashSet<>();
        for (Identifier id : models.keySet()) {
            baked.add(id.toString());
        }
        List<String> placeholderUsers = WeaponModelSelection.idsWithoutModel(weaponIds, baked);
        Constants.LOG.info("[ARG] {} weapon item(s) draw the static world model; {} of them have no model of their own and use "
                + "the placeholder icon '{}': {}", weaponIds.size(), placeholderUsers.size(), PLACEHOLDER_ITEM, placeholderUsers);
    }

    /** Forgets the logged decisions, so a resource reload reports them again. */
    public static void clearLog() {
        LOGGED.clear();
    }
}

