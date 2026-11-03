package com.arg_studios.arg_weaponry.client.recoil;

import java.util.Random;

import com.arg_studios.arg_weaponry.client.aiming.WeaponAiming;
import com.arg_studios.arg_weaponry.client.diagnostics.TransformDiagnostics;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The client's recoil: two layers with two owners, driven by one accepted shot.
 *
 * <h2>The layers</h2>
 * <ol>
 *   <li><strong>weapon kickback</strong> ({@link WeaponKickbackController}) - a displacement of the weapon model
 *   backwards along its own longitudinal axis, applied to the first-person pose stack only, and only when the weapon
 *   explicitly configures {@code kickback_recoil_amplitude}. It keeps its own curve, its own recovery and its own
 *   sampling, and neither of the camera layers below can influence it.</li>
 *   <li><strong>player/view recoil</strong> ({@link PlayerRecoilController}) - an accumulated aim offset that each
 *   accepted shot raises and that normalises back to exactly zero over the weapon's recovery interval
 *   ({@link PlayerRecoilController#RECOVERY_SECONDS} divided by its {@code recovery_speed}), applied to the player's own
 *   angles <em>as a delta</em>, so mouse movement stays live and nothing is ever restored from a captured value. Its
 *   {@code smoothing} shapes how gradually each tick's part of the offset is handed over, and its
 *   {@code aim_multiplier} / {@code crouch_multiplier} scale the per-shot impulse for the stance the player fired
 *   from.</li>
 * </ol>
 * <p>The layers are composed, never merged: the camera hook applies the authored camera-bone animation to the live
 * camera, and the render pass applies the kickback after the camera-bone compensation and before the ADS placement. The
 * recoil layer does not touch the weapon model at all, and the kickback layer does not touch the aim.</p>
 *
 * <h2>No screen shake</h2>
 * <p>The per-shot screen-shake layer is gone - not disabled, removed. There is no shake state, no shake sampler, no
 * camera-roll oscillator and no fallback that could resurrect one: firing changes the player's aim, and that is the whole
 * camera response. A content pack that still carries the retired {@code screen_shake_Recoil_value} /
 * {@code screen_shake_recoil_speed} properties keeps loading, and those values are ignored.</p>
 *
 * <h2>Exactly once per accepted shot</h2>
 * <p>{@link #onAcceptedShot(InteractionHand)} is called from the single place that already knows the server accepted a
 * shot - the result handler of the 10Q trigger protocol, behind its "is this shot newer than the last one I handled?"
 * test ({@link com.arg_studios.arg_weaponry.client.input.AcceptedShotIds}). A rejected request never reaches it, a client
 * prediction never reaches it and a render pass never reaches it, so a shot produces one impulse and only one, for
 * semi-automatic, automatic and burst fire alike, with finite or unlimited ammunition.</p>
 *
 * <h2>Server authority</h2>
 * <p>The view layer changes the player's own pitch and yaw through deltas applied to the live angles. Those rotations
 * are what the client already reports to the server in its movement packets, and the server's hitscan reads them from
 * the player entity, so the next shot really does travel along the raised aim direction without any new protocol, and
 * without the client ever supplying a raycast result, a damage value or a recoil value.</p>
 *
 * <h2>Cleanup</h2>
 * <p>Switching weapon, changing perspective, dying or leaving the world clears both layers, so a stale kickback or a
 * pending view offset can never be applied to the next weapon.</p>
 */
public final class WeaponRecoil {

    private static final WeaponKickbackController KICKBACK = new WeaponKickbackController();
    private static final PlayerRecoilController VIEW = new PlayerRecoilController();

    /**
     * The client's shot randomness, used for one thing only: which side of an asymmetric horizontal recoil this shot
     * takes.
     *
     * <p>Advanced once per accepted shot, never per frame, so a shot's response is fixed the moment it is created.</p>
     */
    private static final Random RANDOM = new Random();

    private static String ownerItemId;

    private WeaponRecoil() {
    }

    /**
     * Applies the recoil of one accepted shot.
     *
     * <p>The camera impulse is scaled by the stance the player is actually in when the shot is accepted:
     * {@code aim_multiplier} while aiming (the client's aim transition is active, i.e. {@link WeaponAiming#isAiming()})
     * and {@code crouch_multiplier} while crouching ({@link LocalPlayer#isCrouching()}). The two multiply when both
     * hold. The weapon-model kickback is scaled only by {@code kickback_aiming_modifier} while aiming, through
     * {@link RecoilProfile#kickbackBlocks(boolean)}, and never by either camera multiplier.</p>
     *
     * @param hand the hand that fired
     */
    public static void onAcceptedShot(InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem item)) {
            return;
        }
        RecoilProfile profile = RecoilProfile.of(item.definition());
        if (profile.isNeutral()) {
            return;
        }
        ownerItemId = String.valueOf(BuiltInRegistries.ITEM.getKey(item));

        // The stance the shot was accepted in is read once and drives both layers. The camera impulse uses aim/crouch
        // multipliers; the weapon-model kickback uses only `kickback_aiming_modifier`, so the two never multiply into
        // each other and a weapon can tune one without changing the other.
        boolean aiming = WeaponAiming.isAiming();
        boolean crouching = player.isCrouching();

        // Layer A: the weapon's own kickback. Only registered when the weapon explicitly configures it, so a weapon that
        // omits `kickback_recoil_amplitude` (or sets it to 0) is exactly as if this layer did not exist. The profile
        // applies `kickback_aiming_modifier` once, inside `kickbackBlocks(aiming)`.
        KICKBACK.shot(profile, aiming);

        // Layer B: the player's aim. The direction of the horizontal part is chosen here, once per shot, so the impulse
        // is a fixed value from this moment on; aim_multiplier / crouch_multiplier scale that impulse now, from the stance
        // the player is actually in, and nothing else about the shot is affected.
        VIEW.onShot(profile.viewRecoilImpulse(RANDOM, aiming, crouching));
    }

    /**
     * Advances every layer by one client tick and applies this tick's view delta to the player.
     *
     * @param minecraft    the client
     * @param deltaSeconds the time elapsed since the previous tick, in seconds
     */
    public static void clientTick(Minecraft minecraft, double deltaSeconds) {
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        if (player == null) {
            reset();
            return;
        }
        WeaponItem item = player.getMainHandItem().getItem() instanceof WeaponItem weapon ? weapon : null;
        String itemId = item == null ? null : String.valueOf(BuiltInRegistries.ITEM.getKey(item));
        if (ownerItemId != null && !ownerItemId.equals(itemId)) {
            // The weapon that recoiled is no longer held: none of its layers may follow the new one.
            reset();
            ownerItemId = itemId;
            return;
        }
        KICKBACK.tick(deltaSeconds);

        float pitchBefore = player.getXRot();
        float yawBefore = player.getYRot();
        PlayerRecoilController.Delta delta = VIEW.tick(deltaSeconds);
        if (!delta.isZero()) {
            applyViewDelta(player, delta);
        }
        TransformDiagnostics.recoil(pitchBefore, player.getXRot(), yawBefore, player.getYRot(), delta.pitchDegrees(),
                delta.yawDegrees(), KICKBACK.backwardBlocks());
    }

    /**
     * Adds one tick's view delta to the player's live angles.
     *
     * <p>{@link ViewAngles#plus(PlayerRecoilController.Delta)} is the whole implementation: it reads the angles the
     * player has <em>right now</em> and returns them with the delta added, clamped to vanilla's pitch range and wrapped
     * like {@code Mth.wrapDegrees} wraps the yaw. Whatever the player did with the mouse since the last tick is already
     * in {@code getXRot()} / {@code getYRot()} and is therefore preserved; no absolute angle is ever stored or
     * restored.</p>
     *
     * @param player the local player
     * @param delta  the delta to apply
     */
    private static void applyViewDelta(LocalPlayer player, PlayerRecoilController.Delta delta) {
        ViewAngles next = new ViewAngles(player.getXRot(), player.getYRot()).plus(delta);
        player.setXRot(next.pitchDegrees());
        player.setYRot(next.yawDegrees());
    }

    /**
     * @param partialTick the frame's partial tick
     * @return the weapon kickback to draw this frame; {@link WeaponKickbackController.Kickback#NONE} when none is active
     */
    public static WeaponKickbackController.Kickback kickback(float partialTick) {
        return KICKBACK.sample(partialTick);
    }

    /**
     * @param partialTick the frame's partial tick
     * @return the backward displacement to draw this frame, in blocks; a convenience form for diagnostics
     */
    public static float kickbackBlocks(float partialTick) {
        return KICKBACK.sample(partialTick).backwardBlocks();
    }

    /** @return whether either layer is still moving. */
    public static boolean isActive() {
        return KICKBACK.isActive() || VIEW.isActive();
    }

    /** @return the view offset currently applied to the player's aim, in degrees of pitch. */
    public static double appliedViewPitch() {
        return VIEW.appliedPitch();
    }

    /** @return the view offset currently applied to the player's aim, in degrees of yaw. */
    public static double appliedViewYaw() {
        return VIEW.appliedYaw();
    }

    /** Clears both layers and forgets the owner; used on death, disconnect, perspective change and resource reload. */
    public static void reset() {
        KICKBACK.reset();
        VIEW.reset();
        ownerItemId = null;
    }
}
