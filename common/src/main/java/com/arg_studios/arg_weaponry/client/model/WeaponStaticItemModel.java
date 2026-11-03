package com.arg_studios.arg_weaponry.client.model;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.display.DisplayTransformMatrix;
import com.arg_studios.arg_weaponry.client.display.StaticWeaponContexts;
import com.arg_studios.arg_weaponry.client.display.StaticWeaponPlacement;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.client.render.WeaponDisplayCache;
import com.arg_studios.arg_weaponry.client.render.WeaponModelCache;
import com.arg_studios.arg_weaponry.client.render.WeaponStaticRenderer;
import com.arg_studios.arg_weaponry.client.render.WeaponTextureCache;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.joml.Vector3f;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The item model of a weapon: a static 3D model in every context that is not first person or the GUI.
 *
 * <h2>Why an item model at all</h2>
 * <p>Weapon ids come from content packs, so Minecraft finds no item-model definition for them and would draw the missing-model
 * placeholder. {@code ModelManagerMixin} already routes those ids here; this class decides what the route produces.</p>
 *
 * <h2>Why this is the right seam for the third-person model</h2>
 * <p>{@link ItemModel#update} is the only place that receives all three things a world-space weapon needs: the
 * {@link ItemStack}, the {@link ItemDisplayContext} and the render state to fill. Every static context - another player's hand,
 * the ground, an item frame, a shelf, a container - obtains its item render state from this method, so one implementation covers
 * them all without patching any entity renderer, and the animated first-person pass stays completely separate: it never asks the
 * item-model system for anything.</p>
 *
 * <h2>What it fills in</h2>
 * <ul>
 *   <li>one layer whose geometry is drawn by {@link WeaponStaticRenderer} (the weapon's own Bedrock model and texture);</li>
 *   <li>an item transform that cancels vanilla's implicit centring of the {@code [0, 1]} model cube, so the anchor really lands on
 *   the item origin (see {@link #NO_CENTRING});</li>
 *   <li>the display transform authored for that context in {@code display_views}, as the layer's local transform, so vanilla
 *   applies it to the model <em>and</em> to the reported extents;</li>
 *   <li>the model's extents, so a dropped gun sits on the ground instead of sinking into it.</li>
 * </ul>
 * <p>Every failure path falls back to the model this instance was created with - the item model Minecraft baked for the
 * weapon's id (a pack's own {@code items/<id>.json}, or the slot icon this mod synthesises for the GUI), or the built-in
 * placeholder when nothing was baked - which is exactly what the weapon rendered as before: a missing display document, a
 * missing geometry, a missing texture, a context whose scale is zero (a pack hiding the weapon in that context) and the
 * first-person and GUI contexts themselves.</p>
 *
 * <h2>Runtime evidence</h2>
 * <p>Which of those two happened is reported once per weapon and display context, as {@code [ARG] Static weapon model: ...}
 * lines naming the display, geometry and texture that were resolved - or the reason the static model could not draw. A weapon
 * that never appears in the world can therefore be diagnosed from the log instead of from the screen.</p>
 */
public final class WeaponStaticItemModel implements ItemModel {

    /** Decisions already reported, keyed by weapon, context and outcome, so a per-frame call cannot flood the log. */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * An item transform that leaves the geometry where it is.
     *
     * <p>Vanilla item models are baked into the {@code [0, 1]} cube and are <em>centred</em> by the render state itself: with the
     * default {@link ItemTransform#NO_TRANSFORM} every layer is translated by {@code (-0.5, -0.5, -0.5)} before the display
     * transform, so a model's cube centre lands on the item origin. Weapon geometry is already expressed around the item origin -
     * the anchor pose puts the weapon's {@code default_view} bone exactly at {@code (0, 0, 0)} - so that implicit half-block shift
     * would move it half a block away from the hand, half a block into the wall behind an item frame and half a block under the
     * ground.</p>
     *
     * <p>This transform is the exact inverse of that centring: {@code T(0.5) * identity * identity * T(-0.5)}, i.e. nothing. It is
     * deliberately a real transform rather than {@code NO_TRANSFORM}, because the centring only happens on the
     * {@code NO_TRANSFORM} path. The per-context {@code display_views} transform is then applied as this layer's local transform,
     * which multiplies on the right and is therefore measured from the anchor - the same meaning a pack's numbers would have in a
     * vanilla {@code display} block, relative to the model's centre.</p>
     */
    private static final ItemTransform NO_CENTRING = new ItemTransform(new Vector3f(),
            new Vector3f(0.5F, 0.5F, 0.5F), new Vector3f(1.0F, 1.0F, 1.0F));

    private final ItemModel fallback;

    /**
     * @param fallback the flat icon model to use wherever the static path does not draw - the GUI, the first-person contexts
     *                 and every failure path - or {@code null} when no icon is available at all
     */
    public WeaponStaticItemModel(ItemModel fallback) {
        this.fallback = fallback;
    }

    @Override
    public void update(ItemStackRenderState output, ItemStack item, ItemModelResolver resolver,
                       ItemDisplayContext displayContext, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
        if (!renderStatic(output, item, displayContext)) {
            if (fallback != null) {
                fallback.update(output, item, resolver, displayContext, level, owner, seed);
            }
        }
    }

    /**
     * Fills the render state with the static weapon layer.
     *
     * @param output         the render state to fill
     * @param item           the stack being drawn
     * @param displayContext the context it is drawn in
     * @return {@code false} when this context or this weapon cannot be drawn statically, so the caller must fall back
     */
    private boolean renderStatic(ItemStackRenderState output, ItemStack item, ItemDisplayContext displayContext) {
        if (output == null || item == null || displayContext == null) {
            return false;
        }
        String contextName = displayContext.getSerializedName();
        if (!StaticWeaponContexts.rendersStatically(contextName)) {
            return false;
        }
        if (!(item.getItem() instanceof WeaponItem weapon)) {
            return false;
        }
        WeaponDisplay display = WeaponDisplayCache.instance().get(weapon.pack(), weapon.definition()).orElse(null);
        if (display == null || !display.isRenderable()) {
            logFallback(weapon, contextName, "its display configuration could not be resolved");
            return false;
        }
        WeaponModel model = WeaponModelCache.instance().get(weapon.pack(), display.model());
        Identifier texture = WeaponTextureCache.instance().get(weapon.pack(), display.texture());
        if (model == null || texture == null) {
            logFallback(weapon, contextName, model == null
                    ? "the geometry '" + display.model() + "' could not be parsed"
                    : "the texture '" + display.texture() + "' could not be uploaded");
            return false;
        }
        // A context the pack collapses to zero scale ("head": scale 0) means "do not show the weapon here". Falling back would
        // draw the placeholder instead, so this returns true with an empty layer: nothing is drawn at all.
        if (StaticWeaponPlacement.isHidden(display, contextName)) {
            logOnce("hidden|" + identity(weapon, contextName),
                    "[ARG] Static weapon model: weapon '{}' of pack '{}' is hidden in '{}'; its display_views transform for that "
                            + "context collapses to zero scale, so no layer is created and the icon is not drawn either",
                    nameOf(weapon), weapon.packId(), contextName);
            return true;
        }

        ItemStackRenderState.LayerRenderState layer = output.newLayer();
        layer.setupSpecialModel(WeaponStaticRenderer.INSTANCE, new WeaponStaticRenderer.Target(weapon, model, texture));
        // Cancel vanilla's implicit centring of the [0, 1] model cube, then apply the authored per-context transform about the
        // anchor the static renderer draws in. See NO_CENTRING.
        layer.setItemTransform(NO_CENTRING);
        layer.setLocalTransform(DisplayTransformMatrix.of(display.transform(contextName)));
        layer.setExtents(WeaponStaticRenderer.extents(model));
        logOnce("static|" + identity(weapon, contextName),
                "[ARG] Static weapon model: weapon '{}' of pack '{}' draws in '{}' from display '{}' with geometry '{}' and "
                        + "texture {}", nameOf(weapon), weapon.packId(), contextName, display.id(), display.model(), texture);
        return true;
    }

    /**
     * Reports once per weapon and context that the flat icon model is used instead of the 3D model, and why.
     *
     * <p>This is the diagnostic that makes a silent fallback visible: a weapon that never reaches the world is otherwise
     * indistinguishable from one that is drawn somewhere the player is not looking.</p>
     */
    private static void logFallback(WeaponItem weapon, String contextName, String reason) {
        logOnce("fallback|" + identity(weapon, contextName),
                "[ARG] Static weapon model: weapon '{}' of pack '{}' cannot draw in '{}' because {}; the flat icon model is used "
                        + "instead", nameOf(weapon), weapon.packId(), contextName, reason);
    }

    /** @return a stable key for the one-shot diagnostics. */
    private static String identity(WeaponItem weapon, String contextName) {
        return weapon.packId() + "|" + nameOf(weapon) + "|" + contextName;
    }

    /** @return the declared weapon name, or a placeholder when the definition carries none. */
    private static String nameOf(WeaponItem weapon) {
        return weapon.definition() == null || weapon.definition().name() == null ? "?" : weapon.definition().name();
    }

    /** Logs a message once per key, so a per-frame call cannot flood the log. */
    private static void logOnce(String key, String message, Object... arguments) {
        if (LOGGED.add(key)) {
            Constants.LOG.info(message, arguments);
        }
    }

    /** Forgets the reported decisions, so a resource reload reports them again. */
    public static void clearLog() {
        LOGGED.clear();
    }

    /** @return the model used when the static path cannot draw. */
    public ItemModel fallback() {
        return fallback;
    }
}
