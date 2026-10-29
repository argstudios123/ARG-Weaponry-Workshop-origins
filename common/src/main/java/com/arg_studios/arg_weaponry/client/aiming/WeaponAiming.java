package com.arg_studios.arg_weaponry.client.aiming;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.client.input.WeaponAimKey;
import com.arg_studios.arg_weaponry.client.input.WeaponInspectInput;
import com.arg_studios.arg_weaponry.client.input.WeaponReloadInput;
import com.arg_studios.arg_weaponry.client.render.WeaponDisplayCache;
import com.arg_studios.arg_weaponry.client.render.WeaponModelCache;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's aim-down-sights state: whether the player is aiming, how far the transition has progressed, and the
 * resulting field-of-view multiplier.
 *
 * <h2>Timeline</h2>
 * <p>Progress is advanced on the client tick, like every other weapon timeline, so a perspective change, an open screen or
 * a paused renderer cannot freeze the transition. Rendering only samples it, with a partial-tick interpolation, which is
 * what makes the transition look smooth at any frame rate.</p>
 *
 * <h2>What decides that aiming may start</h2>
 * <ul>
 *   <li>a weapon in the main hand whose {@code aiming.enabled} is true,</li>
 *   <li>a resolvable model that actually contains the authored {@code aiming_view} bone - without it there is no sight
 *   alignment, so full ADS is refused rather than invented (the diagnostic names the weapon once),</li>
 *   <li>no running inspection when {@code no_aiming_on_inspect} is set,</li>
 *   <li>no running reload when {@code no_aiming_on_reload} is set,</li>
 *   <li>the dedicated aim key held, with world input focus.</li>
 * </ul>
 * <p>When any of those stops being true the transition blends back out; it never snaps, and it never leaves a residual
 * placement behind, because the render path simply blends towards zero.</p>
 *
 * <h2>Zoom</h2>
 * <p>The configured multiplier is interpolated against {@code 1.0} by the eased progress, so {@code 0.5} at half progress is
 * {@code 0.75} of the base field of view. The value is sanitised at parse time ({@code AimZoom}), so no division by zero or
 * inverted field of view can reach this class.</p>
 */
public final class WeaponAiming {

    /** Seconds a full hip-to-aim transition takes; the curve shapes the motion inside this duration. */
    public static final float TRANSITION_SECONDS = 0.18F;

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static float progress;
    private static float previousProgress;
    private static float zoom = 1.0F;
    private static ZoomCurve curve = ZoomCurve.LINEAR;
    private static String ownerItemId;
    private static AimBlockReason reason = AimBlockReason.NO_WEAPON;

    private WeaponAiming() {
    }

    /**
     * Advances the aim transition by one client tick.
     *
     * @param minecraft    the client
     * @param deltaSeconds the time elapsed since the previous tick, in seconds
     */
    public static void clientTick(Minecraft minecraft, double deltaSeconds) {
        previousProgress = progress;
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            reset();
            return;
        }
        WeaponItem item = heldWeapon(player);
        WeaponDefinition.Aiming aiming = item == null ? null : item.definition().aiming();
        WeaponModel model = item == null ? null : modelFor(item);
        String itemId = item == null ? null : String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(item));
        if (itemId == null || !itemId.equals(ownerItemId)) {
            // A different weapon is held: the previous weapon's zoom and curve must not survive the switch.
            if (itemId != null) {
                zoom = aiming == null ? 1.0F : (float) aiming.zoom();
                curve = aiming == null ? ZoomCurve.LINEAR : aiming.zoomCurve();
            }
            ownerItemId = itemId;
        }
        if (aiming != null) {
            zoom = (float) aiming.zoom();
            curve = aiming.zoomCurve();
        }

        reason = evaluate(player, minecraft, item, aiming, model);
        // Progress is time based, not frame based: a stalled client catches up and a fast client does not speed up.
        double elapsed = Double.isFinite(deltaSeconds) && deltaSeconds > 0.0 ? Math.min(deltaSeconds, 0.25) : 0.05;
        float step = (float) (elapsed / TRANSITION_SECONDS);
        boolean wanted = !reason.blocks() && WeaponAimKey.isDown();
        if (wanted) {
            progress = Math.min(1.0F, progress + step);
        } else {
            progress = Math.max(0.0F, progress - step);
        }
    }

    /**
     * Reads the facts the pure rule needs from the running client and applies it.
     *
     * @param player    the local player
     * @param minecraft the client
     * @param item      the held weapon, may be {@code null}
     * @param aiming    the weapon's aiming block, may be {@code null}
     * @param model     the weapon's model, may be {@code null}
     * @return the reason aiming is currently impossible, or {@link AimBlockReason#NONE}
     */
    private static AimBlockReason evaluate(LocalPlayer player, Minecraft minecraft, WeaponItem item,
                                           WeaponDefinition.Aiming aiming, WeaponModel model) {
        boolean weaponHeld = item != null;
        boolean focused = minecraft.gui.screen() == null && minecraft.mouseHandler.isMouseGrabbed();
        boolean hasBone = hasAimingBone(model);
        AimBlockReason reason = AimAllowance.evaluate(player.isAlive(), weaponHeld, focused, aiming, model != null,
                hasBone,
                WeaponInspectInput.isInspecting(InteractionHand.MAIN_HAND),
                WeaponReloadInput.isReloading(InteractionHand.MAIN_HAND),
                // [DrawHolster hook] The draw/holster window blocks aiming like a reload or an inspection does.
                com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.isEquipBusy(player));
        if (reason == AimBlockReason.NO_AIMING_BONE && item != null) {
            reportOnce(item, "[ARG] The weapon '{}' enables aiming but its geometry has no '{}' bone; aim-down-sights is "
                            + "refused for it instead of applying an invented sight offset",
                    item.definition().name(), ModelBoneNames.AIMING_VIEW);
        }
        if (reason == AimBlockReason.NO_MODEL && item != null) {
            reportOnce(item, "[ARG] The weapon '{}' enables aiming but its model could not be resolved; aim-down-sights "
                    + "stays off", item.definition().name());
        }
        return reason;
    }

    /**
     * @param model the model to inspect
     * @return whether the model declares an {@code aiming_view} bone
     */
    public static boolean hasAimingBone(WeaponModel model) {
        if (model == null) {
            return false;
        }
        for (ModelBone bone : model.bonesInOrder()) {
            if (ModelBoneNames.AIMING_VIEW.equals(bone.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param item the weapon
     * @return its model, or {@code null} when it cannot be resolved
     */
    public static WeaponModel modelFor(WeaponItem item) {
        if (item == null) {
            return null;
        }
        return WeaponModelCache.instance().get(item.pack(), WeaponDisplayCache.instance()
                .get(item.pack(), item.definition()).map(WeaponDisplay::model).orElse(null));
    }

    /**
     * @param player the local player
     * @return the weapon in the main hand, or {@code null}
     */
    private static WeaponItem heldWeapon(LocalPlayer player) {
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        return stack.getItem() instanceof WeaponItem weapon ? weapon : null;
    }

    /**
     * @param partialTick the fraction of a client tick elapsed
     * @return the eased progress of the aim transition, {@code 0} for hip fire and {@code 1} for full aim
     */
    public static float easedProgress(float partialTick) {
        float interpolated = interpolate(partialTick);
        return (float) curve.evaluate(interpolated);
    }

    /**
     * @param partialTick the fraction of a client tick elapsed
     * @return the linear progress of the aim transition, interpolated between the last two ticks
     */
    public static float linearProgress(float partialTick) {
        return interpolate(partialTick);
    }

    private static float interpolate(float partialTick) {
        float t = Float.isFinite(partialTick) ? Math.max(0.0F, Math.min(1.0F, partialTick)) : 0.0F;
        return previousProgress + (progress - previousProgress) * t;
    }

    /**
     * @param partialTick the fraction of a client tick elapsed
     * @return the field-of-view multiplier to apply this frame, {@code 1.0} when not aiming
     */
    public static float fovMultiplier(float partialTick) {
        float eased = easedProgress(partialTick);
        float target = Float.isFinite(zoom) ? zoom : 1.0F;
        return 1.0F + (target - 1.0F) * eased;
    }

    /** @return whether the player is currently fully or partially aimed. */
    public static boolean isAiming() {
        return progress > 0.0F;
    }

    /** @return the reason aiming is currently refused, or {@link AimBlockReason#NONE}. */
    public static AimBlockReason reason() {
        return reason;
    }

    private static void reportOnce(WeaponItem item, String message, Object... arguments) {
        String key = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
        if (REPORTED.add(key)) {
            Constants.LOG.warn(message, arguments);
        }
    }

    /** Clears the transition instantly; used when leaving the world or losing the weapon reference. */
    public static void reset() {
        progress = 0.0F;
        previousProgress = 0.0F;
        zoom = 1.0F;
        curve = ZoomCurve.LINEAR;
        ownerItemId = null;
        reason = AimBlockReason.NO_WEAPON;
        REPORTED.clear();
    }
}
