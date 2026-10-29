package com.arg_studios.arg_weaponry.client.input;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.network.WeaponFireModePayload;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult;
import com.arg_studios.arg_weaponry.weapon.fire.FiringModes;
import com.arg_studios.arg_weaponry.weapon.state.FireMode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Client side of firing-mode selection: the B key asks the server for the next mode.
 *
 * <p>The request names a hand and a sequence number and nothing else. The server walks its own copy of the weapon's
 * {@code firing_mode} array, writes the next mode into the stack's {@code weapon_state} component (which vanilla then
 * synchronises) and answers with the mode that is now in force; this class only reports that answer to the player. A
 * client can therefore never select a mode its weapon does not declare.</p>
 *
 * <p>The key does nothing when no hand holds a custom weapon, and nothing is sent for a weapon with a single supported
 * mode - the stack would not change, so the request would be pointless.</p>
 */
public final class WeaponFireModeInput {

    private static int nextSequence;

    private WeaponFireModeInput() {
    }

    /**
     * @param player the local player, may be {@code null}
     * @return the hand a mode change should apply to, or {@code null} when neither hand can change its mode
     */
    public static InteractionHand cycleHand(LocalPlayer player) {
        if (player == null) {
            return null;
        }
        for (InteractionHand hand : new InteractionHand[]{InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND}) {
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof WeaponItem weapon)) {
                continue;
            }
            if (!FiringModes.hasChoice(weapon.definition())) {
                continue;
            }
            return hand;
        }
        return null;
    }

    /**
     * Requests the next firing mode of the best-matching hand.
     *
     * @param player the local player
     */
    public static void requestCycle(LocalPlayer player) {
        InteractionHand hand = cycleHand(player);
        if (hand == null) {
            return;
        }
        Services.NETWORK.sendToServer(new WeaponFireModePayload(hand, ++nextSequence));
    }

    /**
     * Client side: the server answered a mode request.
     *
     * <p>Shows the resulting mode in the action bar, which is the project's first piece of weapon HUD feedback and the
     * only place a player can see which mode B selected. The authoritative value is the stack's component; this method
     * only reports it.</p>
     *
     * @param hand     the hand the request referred to
     * @param sequence the request's sequence number
     * @param result   the server's decision
     * @param mode     the mode in force afterwards, may be {@code null}
     */
    public static void onResult(InteractionHand hand, int sequence, WeaponActionResult result, FireMode mode) {
        if (result != WeaponActionResult.OK || mode == null) {
            Constants.LOG.debug("[ARG] The firing-mode request {} for {} was refused: {}", sequence, hand, result);
            return;
        }
        LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Component message = Component.translatable("arg_weaponry.fire_mode.changed",
                Component.translatable("arg_weaponry.fire_mode." + mode.serializedName()));
        player.sendOverlayMessage(message.copy().withStyle(ChatFormatting.GRAY));
    }

    /** Forgets the client-side request counter; called when the client leaves a world. */
    public static void reset() {
        nextSequence = 0;
    }
}
