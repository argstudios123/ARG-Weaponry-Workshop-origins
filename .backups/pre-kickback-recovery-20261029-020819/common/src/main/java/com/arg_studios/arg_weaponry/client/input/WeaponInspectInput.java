package com.arg_studios.arg_weaponry.client.input;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.animation.WeaponViewControllers;
import com.arg_studios.arg_weaponry.client.audio.WeaponActionSounds;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.network.WeaponInspectPayload;
import com.arg_studios.arg_weaponry.network.WeaponInspectResultPayload;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Client side of inspection: the I key asks the server, the answer starts the clip and the sound.
 *
 * <p>The client never starts an inspection on its own. It sends a hand and a sequence number, and only when the server
 * accepts does it hand the approved clip name and duration to the existing view controller - so a weapon without
 * {@code inspect_anims}, a weapon whose conditions do not match, a running reload or a repeated request all end with
 * nothing happening, which is exactly the documented behaviour.</p>
 */
public final class WeaponInspectInput {

    /** The highest sequence this client has sent, so a replay can never be produced locally. */
    private static int nextSequence;

    /** The hand whose inspection the server accepted, or {@code null} when none is running. */
    private static InteractionHand activeHand;

    /** Wall-clock end of the accepted inspection, used to clear the state without a second packet. */
    private static long activeUntilMillis;

    private WeaponInspectInput() {
    }

    /**
     * Requests inspection of the weapon in the main hand.
     *
     * @param player the local player, may be {@code null}
     */
    public static void requestInspect(LocalPlayer player) {
        if (player == null) {
            return;
        }
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return;
        }
        if (weapon.definition().inspectAnimations().isEmpty()) {
            // Documented: a weapon without inspect_anims does nothing at all. No packet is wasted on it.
            return;
        }
        Services.NETWORK.sendToServer(new WeaponInspectPayload(InteractionHand.MAIN_HAND, ++nextSequence));
    }

    /**
     * Applies the server's answer.
     *
     * @param payload the outcome
     */
    public static void onResult(WeaponInspectResultPayload payload) {
        if (payload.result() != WeaponActionResult.OK || payload.durationTicks() <= 0
                || payload.animation().isBlank()) {
            Constants.LOG.debug("[ARG] The inspection request {} for {} was refused: {}", payload.sequence(),
                    payload.hand(), payload.result());
            return;
        }
        // The sound is the weapon's own answer to an accepted inspection, so it plays even if the clip cannot be
        // started (a hand that was never rendered has no controller). Missing audio never blocks the clip and a
        // missing clip never blocks the audio.
        WeaponActionSounds.inspect(payload.hand(), payload.emptyMagazine());
        double seconds = payload.durationTicks() / 20.0;
        activeHand = payload.hand();
        activeUntilMillis = System.currentTimeMillis() + (long) (seconds * 1000.0);
        if (!WeaponViewControllers.instance().playInspectAnimation(payload.hand(), payload.animation(), seconds)) {
            Constants.LOG.debug("[ARG] The inspection clip '{}' could not be started for {}", payload.animation(),
                    payload.hand());
        }
    }

    /**
     * @param hand the hand to test
     * @return whether an accepted inspection of that hand is still running
     */
    public static boolean isInspecting(InteractionHand hand) {
        if (activeHand != hand) {
            return false;
        }
        if (System.currentTimeMillis() >= activeUntilMillis) {
            activeHand = null;
            return false;
        }
        return true;
    }

    /**
     * Ends the client's view of the running inspection, without sending anything.
     *
     * <p>Used when a shot, a reload or a weapon change has already made the inspection impossible: the server has ended
     * the session in the same instant, so the client only has to forget it.</p>
     */
    public static void cancelActive() {
        activeHand = null;
        activeUntilMillis = 0L;
    }

    /** Forgets the client-side request counter; called when the client leaves a world. */
    public static void reset() {
        nextSequence = 0;
        cancelActive();
    }
}
