package com.arg_studios.arg_weaponry.client.recoil;

import java.util.Random;

import com.arg_studios.arg_weaponry.client.diagnostics.TransformDiagnostics;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The client's recoil: three layers with three owners, driven by one accepted shot.
 *
 * <h2>The layers</h2>
 * <ol>
 *   <li><strong>weapon kickback</strong> ({@link WeaponKickbackController}) - a displacement of the weapon model
 *   backwards along its own longitudinal axis, applied to the first-person pose stack only, and only when the weapon
 *   explicitly configures {@code kickback_recoil_amplitude}</li>
 *   <li><strong>player/view recoil</strong> ({@link PlayerRecoilController}) - a time-shaped response added to the
 *   player's own angles <em>as a delta</em>, so mouse movement stays live and nothing is restored from a captured
 *   value.</li>
 *   <li><strong>screen shake</strong> ({@link ViewShakeController}) - a bounded, camera-only yaw/roll flutter that is
 *   sampled from elapsed time and lasts one {@link ViewShakeResponse#DEFAULT_DURATION_SECONDS}-second response per shot.</li>
 * </ol>
 * <p>This is the separation Point Blank uses - a gun-recoil controller, a player-recoil controller and a view-shake
 * controller with separate state - and it is what keeps the three effects from overwriting each other. The layers are
 * composed, never merged: the camera hook composes the shake after the authored camera animation, and the render pass
 * composes the kickback after the camera-bone compensation and before the ADS placement.</p>
 *
 * <h2>Exactly once per accepted shot</h2>
 * <p>{@link #onAcceptedShot(InteractionHand)} is called from the single place that already knows the server accepted a
 * shot - the result handler of the 10Q trigger protocol. A rejected request never reaches it, a client prediction never
 * reaches it and a render pass never reaches it, so a shot produces one impulse and only one, for semi-automatic,
 * automatic and burst fire alike, with finite or unlimited ammunition.</p>
 *
 * <h2>Server authority</h2>
 * <p>The view layer changes the player's own pitch and yaw through deltas applied to the live angles. Those rotations
 * are what the client already reports to the server in its movement packets, and the server's hitscan reads them from
 * the player entity, so the next shot really does travel along the raised aim direction without any new protocol, and
 * without the client ever supplying a raycast result, a damage value or a recoil value.</p>
 *
 * <h2>Cleanup</h2>
 * <p>Switching weapon, changing perspective, dying or leaving the world clears all three layers, so a stale kickback,
 * shake or pending view offset can never be applied to the next weapon.</p>
 */
public final class WeaponRecoil {

    private static final WeaponKickbackController KICKBACK = new WeaponKickbackController();
    private static final PlayerRecoilController VIEW = new PlayerRecoilController();
    private static final ViewShakeController SHAKE = new ViewShakeController();

    /**
     * The client's shot randomness: the sideways direction of the view recoil and the direction and magnitude of the shake.
     *
     * <p>Advanced once per accepted shot, never per frame, so a shot's response is fixed the moment it is created and can be
     * sampled any number of times without the effect drifting.</p>
     */
    private static final Random RANDOM = new Random();

    private static String ownerItemId;

    private WeaponRecoil() {
    }

    /**
     * Applies the recoil of one accepted shot.
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

        // Layer A: the weapon's own kickback. Only registered when the weapon explicitly configures it, so a weapon that
        // omits `kickback_recoil_amplitude` (or sets it to 0) is exactly as if this layer did not exist.
        KICKBACK.shot(profile);

        // Layer C: the camera-only shake. One response per shot, drawn from the configured amplitude and speed exactly the
        // way Point Blank's controller draws it, so a new shot replaces the running flutter instead of accumulating on it.
        SHAKE.onShot(ViewShakeResponse.forShot(profile.shakeDegrees(), profile.shakeSpeed(), RANDOM));

        // Layer B: the player/view response. The direction of the horizontal part is chosen here, once per shot, so the
        // response is a deterministic function of elapsed time from this moment on.
        VIEW.onShot(new ViewRecoilResponse(profile.verticalDegrees(), horizontalDirection(profile)));
    }

    /**
     * Picks this shot's horizontal offset.
     *
     * <p>Both directions configured: the side is random, so sustained fire can walk either way. Only one configured:
     * that side is always used, so a one-sided configuration always recoils instead of sometimes doing nothing.
     * Neither: no sideways impulse.</p>
     *
     * @param profile the weapon's profile
     * @return the yaw offset in degrees, positive to the right and negative to the left
     */
    private static double horizontalDirection(RecoilProfile profile) {
        double left = profile.horizontalLeftDegrees();
        double right = profile.horizontalRightDegrees();
        if (left <= 0.0 && right <= 0.0) {
            return 0.0;
        }
        if (left <= 0.0) {
            return right;
        }
        if (right <= 0.0) {
            return -left;
        }
        return RANDOM.nextBoolean() ? right : -left;
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
        SHAKE.tick(deltaSeconds);

        float pitchBefore = player.getXRot();
        float yawBefore = player.getYRot();
        PlayerRecoilController.Delta delta = VIEW.tick(deltaSeconds);
        if (!delta.isZero()) {
            applyViewDelta(player, delta);
        }
        TransformDiagnostics.recoil(pitchBefore, player.getXRot(), yawBefore, player.getYRot(), delta.pitchDegrees(),
                delta.yawDegrees(), KICKBACK.backwardBlocks(), SHAKE.amplitude());
    }

    /**
     * Adds one tick's view delta to the player's live angles.
     *
     * <p>This is a delta on the <em>current</em> value, never a captured absolute angle, which is what keeps mouse
     * movement effective during recoil: whatever the player did with the mouse since the last tick is already in
     * {@code getXRot()} / {@code getYRot()} and is preserved. The pitch is clamped to vanilla's own limits so no
     * configuration can push the camera past straight up or down into a state the mouse cannot recover from.</p>
     *
     * @param player the local player
     * @param delta  the delta to apply
     */
    private static void applyViewDelta(LocalPlayer player, PlayerRecoilController.Delta delta) {
        float pitch = player.getXRot() - (float) delta.pitchDegrees();
        float yaw = player.getYRot() + (float) delta.yawDegrees();
        player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
        player.setYRot(Mth.wrapDegrees(yaw));
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

    /**
     * @param partialTick the frame's partial tick
     * @return the camera-only shake of this frame, in degrees
     */
    public static ViewShakeController.ShakeAngles shake(float partialTick) {
        return SHAKE.sample(partialTick);
    }

    /** @return whether any layer is still moving. */
    public static boolean isActive() {
        return KICKBACK.isActive() || VIEW.isActive() || SHAKE.isActive();
    }

    /** @return the view offset currently applied to the player's aim, in degrees of pitch. */
    public static double appliedViewPitch() {
        return VIEW.appliedPitch();
    }

    /** @return the view offset currently applied to the player's aim, in degrees of yaw. */
    public static double appliedViewYaw() {
        return VIEW.appliedYaw();
    }

    /** @return how many view responses are in flight; for tests. */
    public static int activeViewResponses() {
        return VIEW.activeCount();
    }

    /** Clears every layer and forgets the owner; used on death, disconnect, perspective change and resource reload. */
    public static void reset() {
        KICKBACK.reset();
        VIEW.reset();
        SHAKE.reset();
        ownerItemId = null;
    }
}
