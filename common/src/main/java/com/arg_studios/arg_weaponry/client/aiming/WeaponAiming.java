package com.arg_studios.arg_weaponry.client.aiming;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.client.input.WeaponAimKey;
import com.arg_studios.arg_weaponry.client.input.WeaponInspectInput;
import com.arg_studios.arg_weaponry.client.input.WeaponReloadInput;
import com.arg_studios.arg_weaponry.client.render.WeaponDisplayCache;
import com.arg_studios.arg_weaponry.client.render.WeaponModelCache;
import com.arg_studios.arg_weaponry.config.ArgConfig;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.aiming.AdsReloadZoomControl;
import com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


public final class WeaponAiming {

    /** Seconds a full hip-to-aim transition takes at speed {@code 1.0}; the curve shapes the motion inside this duration. */
    public static final float TRANSITION_SECONDS = 0.18F;

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private static final AimInput INPUT = new AimInput();

    private static float aimProgress;
    private static float previousAimProgress;
    private static float zoomProgress;
    private static float previousZoomProgress;
    private static float zoom = 1.0F;
    private static ZoomCurve animationCurve = ZoomCurve.LINEAR;
    private static ZoomCurve zoomCurve = ZoomCurve.LINEAR;
    private static double animationSpeed = WeaponDefinition.Aiming.DEFAULT_SPEED;
    private static double zoomSpeed = WeaponDefinition.Aiming.DEFAULT_SPEED;
    private static boolean holdingAim;
    private static boolean aimRequested;
    private static String ownerItemId;
    private static AimBlockReason reason = AimBlockReason.NO_WEAPON;
    private static WeaponDefinition.AdsReloadZoom reloadZoomConfig = WeaponDefinition.AdsReloadZoom.DISABLED;
    private static final AdsReloadZoomControl RELOAD_ZOOM = new AdsReloadZoomControl();

    private WeaponAiming() {
    }

    /**
     * Advances the aim transition by one client tick.
     *
     * @param minecraft    the client
     * @param deltaSeconds the time elapsed since the previous tick, in seconds
     */
    public static void clientTick(Minecraft minecraft, double deltaSeconds) {
        previousAimProgress = aimProgress;
        previousZoomProgress = zoomProgress;
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            reset();
            return;
        }
        WeaponItem item = heldWeapon(player);
        WeaponDefinition.Aiming aiming = item == null || item.definition() == null ? null : item.definition().aiming();
        WeaponModel model = item == null ? null : modelFor(item);
        String itemId = item == null ? null : String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(item));
        if (itemId == null || !itemId.equals(ownerItemId)) {
            // A different weapon is held: the previous weapon's zoom, speeds and curves must not survive the switch. A
            // latched toggle request is dropped as well, by the hard refusal the new weapon produces on this same tick.
            ownerItemId = itemId;
            RELOAD_ZOOM.reset();
        }
        applyConfiguration(aiming);

        reason = evaluate(player, minecraft, item, aiming, model);
        boolean wanted = INPUT.update(WeaponAimKey.isDown(), ArgConfig.toggleAiming(), reason);
        // Aiming and inspection are mutually exclusive, and the exclusion is a cancellation, not a suspension: an
        // inspection clears the aim state (so the sight alignment blends out like any other aim release) and the aim
        // input is suppressed until the button is released, so the aim can never come back on its own when the
        // inspection ends. PB keeps aiming in its own state machine and refuses aim requests while an action owns the
        // weapon, which is the same rule expressed once instead of per action.
        holdingAim = wanted && !reason.blocks();
        // The raw intent, not the accepted aim: a reload that refuses normal aiming must still be able to run its own
        // reload zoom, and the sprint guard must still cover a held aim during that refusal.
        aimRequested = wanted && item != null && aiming != null && aiming.enabled();
        boolean reloading = WeaponReloadInput.isReloading(InteractionHand.MAIN_HAND);
        double reloadElapsed = Double.isFinite(deltaSeconds) && deltaSeconds > 0.0
                ? Math.min(deltaSeconds, 0.25) : 0.05;
        RELOAD_ZOOM.update(reloadZoomConfig.enabled(), reloading, aimRequested, reloadElapsed);

        // Progress is time based, not frame based: a stalled client catches up and a fast client does not speed up.
        double elapsed = Double.isFinite(deltaSeconds) && deltaSeconds > 0.0 ? Math.min(deltaSeconds, 0.25) : 0.05;
        aimProgress = advance(aimProgress, holdingAim, elapsed * animationSpeed);
        zoomProgress = advance(zoomProgress, holdingAim, elapsed * zoomSpeed);

        // Stop an already running sprint cleanly. The sprint guards in the client mixins keep vanilla from starting a new
        // one on the next tick while the reason still applies, and this call is driven by exactly the same rule, so a
        // latched aim request, an accepted aim and a running reload all behave identically.
        enforceSprintRestriction(player);
    }

    /**
     * Applies a weapon's aiming configuration, or the neutral defaults when the weapon declares none.
     *
     * @param aiming the weapon's {@code aiming} block, may be {@code null}
     */
    private static void applyConfiguration(WeaponDefinition.Aiming aiming) {
        if (aiming == null) {
            zoom = 1.0F;
            animationCurve = ZoomCurve.LINEAR;
            zoomCurve = ZoomCurve.LINEAR;
            animationSpeed = WeaponDefinition.Aiming.DEFAULT_SPEED;
            zoomSpeed = WeaponDefinition.Aiming.DEFAULT_SPEED;
            reloadZoomConfig = WeaponDefinition.AdsReloadZoom.DISABLED;
            return;
        }
        zoom = (float) aiming.zoom();
        animationCurve = aiming.animationCurve();
        zoomCurve = aiming.zoomCurve();
        animationSpeed = aiming.animationSpeed();
        zoomSpeed = aiming.zoomSpeed();
        reloadZoomConfig = aiming.adsReloadZoom();
    }

    /**
     * @param progress the current progress
     * @param wanted   whether the aim state is wanted this tick
     * @param seconds  the time to advance, already multiplied by the transition's speed
     * @return the new progress, clamped into {@code [0, 1]}
     */
    private static float advance(float progress, boolean wanted, double seconds) {
        double step = Double.isFinite(seconds) ? Math.max(0.0, seconds) / TRANSITION_SECONDS : 0.0;
        double value = wanted ? progress + step : progress - step;
        return (float) Math.max(0.0, Math.min(1.0, value));
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
     * Applies the weapon's ADS constraint to a sampled pose.
     *
     * <p>The constraint is driven by the <em>actual</em> eased ADS progress, so a partial aim applies a partial correction
     * and releasing ADS restores the animation smoothly instead of snapping. The function is pure and returns the same
     * pose object when the weapon has no constraint or no {@code ads_offset} bone, which keeps every existing weapon
     * untouched.</p>
     *
     * @param pose        the sampled pose, may be {@code null}
     * @param model       the weapon's model, may be {@code null}
     * @param definition  the weapon definition, may be {@code null}
     * @param partialTick the frame's partial tick, used to ease the progress exactly like the placement
     * @return the corrected pose, or {@code pose} when nothing applies
     */
    public static WeaponPose constrainPose(WeaponPose pose, WeaponModel model, WeaponDefinition definition,
                                           float partialTick) {
        if (pose == null || definition == null || definition.aiming() == null) {
            return pose;
        }
        return com.arg_studios.arg_weaponry.weapon.aiming.AdsConstraint.apply(pose, model,
                definition.aiming().adsConstraint(), easedProgress(partialTick));
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
     * @return the eased progress of the aiming <em>animation</em> transition, {@code 0} for hip fire and {@code 1} for full
     *         aim; this is what the placement blend and the sway reduction consume
     */
    public static float easedProgress(float partialTick) {
        return (float) animationCurve.evaluate(interpolate(previousAimProgress, aimProgress, partialTick));
    }

    /**
     * @param partialTick the fraction of a client tick elapsed
     * @return the eased progress of the zoom transition, {@code 0} for hip fire and {@code 1} for full aim
     */
    public static float zoomEasedProgress(float partialTick) {
        return (float) zoomCurve.evaluate(interpolate(previousZoomProgress, zoomProgress, partialTick));
    }

    private static float interpolate(float previous, float current, float partialTick) {
        float t = Float.isFinite(partialTick) ? Math.max(0.0F, Math.min(1.0F, partialTick)) : 0.0F;
        return previous + (current - previous) * t;
    }

    /**
     * @param partialTick the fraction of a client tick elapsed
     * @return the field-of-view multiplier to apply this frame, {@code 1.0} when not aiming
     */
    public static float fovMultiplier(float partialTick) {
        float eased = zoomEasedProgress(partialTick);
        float target = Float.isFinite(zoom) ? zoom : 1.0F;
        float aiming = 1.0F + (target - 1.0F) * eased;
        // The reload effect is a separate multiplier: it works while normal aiming is refused by no_aiming_on_reload, and
        // is exactly 1.0 whenever there is no active reload zoom.
        return aiming * RELOAD_ZOOM.multiplier(partialTick, reloadZoomConfig);
    }

    /**
     * @param partialTick the frame's partial tick
     * @return the reload-specific zoom multiplier alone, for diagnostics and tests; {@code 1.0} when inactive
     */
    public static float reloadZoomMultiplier(float partialTick) {
        return RELOAD_ZOOM.multiplier(partialTick, reloadZoomConfig);
    }

    /**
     * @return whether the player is currently fully or partially aimed; this includes the tick an aim was requested on,
     *         so a shot fired immediately after the click is already treated as an aimed shot
     */
    public static boolean isAiming() {
        return holdingAim || aimProgress > 0.0F || zoomProgress > 0.0F;
    }

    /**
     * @return whether aiming is wanted and allowed this tick. This is the state the weapon animation layers consume: it
     *         changes on the tick the player aims or stops aiming, not when the visual transition happens to finish.
     */
    public static boolean isHoldingAim() {
        return holdingAim;
    }

    /** @return the reason aiming is currently refused, or {@link AimBlockReason#NONE}. */
    public static AimBlockReason reason() {
        return reason;
    }

    /**
     * Whether a sprint must not be started (and, when already running, must be stopped) right now.
     *
     * <p>This is the single rule the sprint guard uses. It reads the <em>intent</em> - a latched toggle request or an
     * accepted aim - and not the progress of the transition, which has two consequences:</p>
     * <ul>
     *   <li>it is already true on the tick the aim is requested, which is what closes the one-tick window in which
     *   vanilla's own input handling could otherwise start a sprint before the aim controller stops it;</li>
     *   <li>it becomes false on the tick the aim is released, so sprinting is available again immediately instead of after
     *   the blend-out.</li>
     * </ul>
     * <p>The rule itself lives in {@link AimInput#blocksSprint(boolean, boolean)} so it can be unit tested.</p>
     *
     * @return whether sprinting is refused while aiming
     */
    public static boolean blocksSprint() {
        return AimInput.blocksSprint(holdingAim, INPUT.latched(), aimRequested,
                WeaponReloadInput.isReloading(InteractionHand.MAIN_HAND));
    }

    /**
     * @return whether the player is asking to aim this tick, even if aiming is temporarily refused by a reload, an
     *         inspection or a draw. This is the intent the reload zoom and the sprint guards use; {@link #isAiming()}
     *         remains the accepted, visually active state.
     */
    public static boolean isAimRequested() {
        return aimRequested;
    }

    /**
     * Stops a running sprint because the player is now aiming or reloading.
     *
     * <p>Called from the client tick: the aim state is authoritative on the client, so the ordinary vanilla stop command is
     * all that is needed and both sides agree. A sprint that is already running is ended cleanly; every <em>new</em> sprint
     * is refused by {@link #blocksSprint()} - both in {@code LocalPlayer#canStartSprinting} and in
     * {@code Entity#setSprinting}, which is the setter every other caller goes through.</p>
     *
     * @param player the local player, may be {@code null}
     */
    public static void enforceSprintRestriction(LocalPlayer player) {
        if (player != null && blocksSprint() && player.isSprinting()) {
            player.setSprinting(false);
        }
    }

    private static void reportOnce(WeaponItem item, String message, Object... arguments) {
        String key = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
        if (REPORTED.add(key)) {
            Constants.LOG.warn(message, arguments);
        }
    }

    /** Clears the transition instantly; used when leaving the world or losing the weapon reference. */
    public static void reset() {
        aimProgress = 0.0F;
        previousAimProgress = 0.0F;
        zoomProgress = 0.0F;
        previousZoomProgress = 0.0F;
        zoom = 1.0F;
        animationCurve = ZoomCurve.LINEAR;
        zoomCurve = ZoomCurve.LINEAR;
        animationSpeed = WeaponDefinition.Aiming.DEFAULT_SPEED;
        zoomSpeed = WeaponDefinition.Aiming.DEFAULT_SPEED;
        holdingAim = false;
        aimRequested = false;
        ownerItemId = null;
        reason = AimBlockReason.NO_WEAPON;
        reloadZoomConfig = WeaponDefinition.AdsReloadZoom.DISABLED;
        RELOAD_ZOOM.reset();
        INPUT.reset();
        REPORTED.clear();
    }
}
