package com.arg_studios.arg_weaponry.client.model;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.display.AmmunitionDisplay;
import com.arg_studios.arg_weaponry.client.render.AmmunitionDisplayCache;
import com.arg_studios.arg_weaponry.item.AmmunitionRegistration;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses the inventory/hotbar/held item model of a dynamically registered ammunition item.
 *
 * <p>Ammunition ids come from content packs ({@code arg_pack:dmr_ammo}), so Minecraft has no
 * {@code assets/arg_pack/items/dmr_ammo.json} for them and would show the missing-model placeholder. Content
 * packs are deliberately not mounted as vanilla resource packs, so the model is chosen here instead, at the
 * same single point that already supplies the interim weapon model: {@code ModelManagerMixin}.</p>
 *
 * <h2>What is chosen</h2>
 * <ol>
 *   <li>if a model really was baked for the id — for example a normally mounted resource pack provides one —
 *       nothing is returned and Minecraft's own model wins;</li>
 *   <li>an ammunition display that declares {@code item_model} reuses that item's baked model. This is the 2D
 *       mode, and it is also the 2D icon of a 3D definition that declares both;</li>
 *   <li>otherwise the documented fallback item model is used ({@link WeaponItemModels#PLACEHOLDER_ITEM}). This
 *       covers ammunition with no display, a display that could not be read, and a 3D display that does not
 *       declare {@code item_model}.</li>
 * </ol>
 *
 * <p>Every decision is logged once per item, so the log states exactly which ammunition uses which model
 * instead of leaving the choice implicit.</p>
 *
 * <h2>Not implemented here</h2>
 * <p>Drawing Bedrock geometry as an item is not part of this milestone: it would be a second item-model
 * implementation inside the completed weapon renderer. A 3D ammunition display is therefore parsed, validated
 * and cached — its data is ready — but its appearance in the inventory is the declared {@code item_model} or the
 * fallback icon.</p>
 */
public final class AmmunitionItemModels {

    /**
     * The documented fallback icon for ammunition that declares no {@code item_model}: vanilla's arrow, the
     * closest vanilla analogue of a round carried in an inventory. It is a fixed, documented constant rather
     * than a per-item guess, and it is never a weapon's model or texture.
     */
    public static final Identifier FALLBACK_ITEM_MODEL =
            Identifier.fromNamespaceAndPath("minecraft", "arrow");

    /** Keys already logged, so a per-frame lookup never repeats a message. */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private AmmunitionItemModels() {
    }

    /**
     * Resolves the item model of one ammunition id.
     *
     * @param models the baked item models, keyed by item id
     * @param id     the item id being resolved
     * @return the model to use, or {@code null} when this id is not ammunition or already has its own model
     */
    public static ItemModel modelFor(Map<Identifier, ItemModel> models, Identifier id) {
        if (models == null || id == null || models.containsKey(id)) {
            return null;
        }
        AmmunitionRegistration.RegisteredAmmunition ammunition = AmmunitionRegistration.byItemId(id);
        if (ammunition == null) {
            return null;
        }

        AmmunitionDisplay display = AmmunitionDisplayCache.instance()
                .get(ammunition.pack(), ammunition.definition())
                .orElse(null);

        ItemModel declared = declaredModel(models, display);
        if (declared != null) {
            logOnce("declared:" + id, "Ammunition item '{}' uses the item model '{}' declared by its display "
                    + "definition '{}'", id, display.itemModel(), display.id());
            return declared;
        }

        if (display == null) {
            logOnce("nodisplay:" + id, "Ammunition item '{}' has no usable display definition, so it uses the "
                    + "documented fallback item model '{}'", id, FALLBACK_ITEM_MODEL);
        } else if (display.itemModel() != null) {
            logOnce("missingmodel:" + id, "Ammunition item '{}' declares the item model '{}', but Minecraft baked "
                    + "no model for it; the documented fallback item model '{}' is used", id, display.itemModel(),
                    FALLBACK_ITEM_MODEL);
        } else if (display.isModelMode()) {
            logOnce("model3d:" + id, "Ammunition item '{}' uses the 3D display definition '{}'. Drawing Bedrock "
                    + "geometry as an item is not implemented in this milestone, so the documented fallback item "
                    + "model '{}' is used; declare \"item_model\" in that display to choose a 2D icon", id,
                    display.id(), FALLBACK_ITEM_MODEL);
        } else {
            logOnce("fallback:" + id, "Ammunition item '{}' declares no 'item_model', so it uses the documented "
                    + "fallback item model '{}'", id, FALLBACK_ITEM_MODEL);
        }
        return fallbackModel(models);
    }

    /**
     * @param models the baked item models
     * @return the documented ammunition fallback model, or the generic mod fallback, or {@code null}
     */
    private static ItemModel fallbackModel(Map<Identifier, ItemModel> models) {
        ItemModel fallback = models.get(FALLBACK_ITEM_MODEL);
        if (fallback != null) {
            return fallback;
        }
        ItemModel generic = models.get(WeaponItemModels.PLACEHOLDER_ITEM);
        if (generic == null) {
            logOnce("missing-fallback", "Neither the ammunition fallback item model '{}' nor the generic fallback "
                    + "'{}' is available; ammunition without a model of its own renders as the missing-model "
                    + "placeholder", FALLBACK_ITEM_MODEL, WeaponItemModels.PLACEHOLDER_ITEM);
        }
        return generic;
    }

    private static ItemModel declaredModel(Map<Identifier, ItemModel> models, AmmunitionDisplay display) {
        if (display == null || display.itemModel() == null) {
            return null;
        }
        Identifier modelId = Identifier.tryParse(display.itemModel().asString());
        return modelId == null ? null : models.get(modelId);
    }

    /** Forgets the logged decisions, so a resource reload reports them again. */
    public static void clearLog() {
        LOGGED.clear();
    }

    private static void logOnce(String key, String message, Object... arguments) {
        if (LOGGED.add(key)) {
            Constants.LOG.info("[ARG] " + message, arguments);
        }
    }
}
