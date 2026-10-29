package com.arg_studios.arg_weaponry.weapon.fire;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.network.WeaponFireModeResultPayload;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult;
import com.arg_studios.arg_weaponry.weapon.state.FireMode;
import com.arg_studios.arg_weaponry.weapon.state.WeaponComponents;
import com.arg_studios.arg_weaponry.weapon.state.WeaponState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the firing-mode selection key (B).
 *
 * <h2>The client asks, the server decides</h2>
 * <p>The request carries only a hand and a sequence number; there is no mode identifier anywhere on the wire. The
 * server resolves the weapon's supported modes from its own copy of the definition, walks to the next one in the
 * authored order ({@link FiringModes#next}) and writes that into the stack's {@code weapon_state} component, which
 * Minecraft then synchronises to the client by itself. A client therefore cannot select a mode the weapon does not
 * declare, and it cannot skip the compatibility rules by asking for a different one.</p>
 *
 * <h2>Replay protection and side effects</h2>
 * <p>A request must carry a strictly increasing sequence number per player and hand, exactly like the firing and
 * reloading requests; a repeat is answered with {@link WeaponActionResult#STALE_REQUEST} and changes nothing. A
 * successful change also stops an automatic or burst session of that hand, because that session was validated for the
 * old mode and must not keep firing it.</p>
 */
public final class WeaponFireModeService {

    private static final Map<Key, Integer> lastSequences = new ConcurrentHashMap<>();

    private WeaponFireModeService() {
    }

    /**
     * Cycles the firing mode of the weapon in one hand.
     *
     * @param player   the requesting player, resolved by the server
     * @param hand     the hand named by the request
     * @param sequence the request's sequence number
     * @return what happened; the client is told through {@link WeaponFireModeResultPayload} whenever its connection
     *         can receive one
     */
    public static WeaponActionResult cycle(ServerPlayer player, InteractionHand hand, int sequence) {
        if (player == null || hand == null || !player.isAlive() || player.isSpectator()) {
            return reply(player, hand, sequence, WeaponActionResult.INVALID_CONTEXT, null);
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return reply(player, hand, sequence, WeaponActionResult.NOT_A_WEAPON, null);
        }
        WeaponDefinition definition = weapon.definition();
        WeaponState state = weapon.state(stack);
        List<FireMode> supported = definition.firingModes();
        if (supported.isEmpty()) {
            return reply(player, hand, sequence, WeaponActionResult.UNSUPPORTED, null);
        }
        Key key = new Key(player.getUUID(), hand);
        int previous = lastSequences.getOrDefault(key, 0);
        if (sequence <= previous) {
            // A replay, a duplicate, or a client that restarted its counter without reconnecting.
            return reply(player, hand, sequence, WeaponActionResult.STALE_REQUEST,
                    FiringModes.resolve(supported, state.fireMode()));
        }
        lastSequences.put(key, sequence);

        FireMode current = FiringModes.resolve(supported, state.fireMode());
        FireMode next = FiringModes.next(supported, current);
        if (next == null || next == current) {
            // One mode, or nothing to choose: the stack is not touched at all.
            return reply(player, hand, sequence, WeaponActionResult.OK, current);
        }
        stack.set(WeaponComponents.WEAPON_STATE, state.withFireMode(next));
        // The running session was validated for the old mode; end it so it can never keep firing the previous mode.
        WeaponFireSessions.cancel(player.getUUID(), hand);
        Constants.LOG.debug("[ARG] {} switched '{}' in {} from {} to {}", player.getName().getString(),
                definition.name(), hand, current, next);
        return reply(player, hand, sequence, WeaponActionResult.OK, next);
    }

    /**
     * Sends the outcome to the client when its connection accepts the payload.
     *
     * @param player   the player
     * @param hand     the hand
     * @param sequence the request sequence
     * @param result   the decision
     * @param mode     the mode in force afterwards, may be {@code null}
     * @return {@code result}, so callers can return it directly
     */
    private static WeaponActionResult reply(ServerPlayer player, InteractionHand hand, int sequence,
                                            WeaponActionResult result, FireMode mode) {
        if (player != null && hand != null && sequence >= 1
                && Services.NETWORK.canSendToPlayer(player, WeaponFireModeResultPayload.TYPE)) {
            Services.NETWORK.sendToPlayer(player, new WeaponFireModeResultPayload(hand, sequence, result, mode));
        }
        return result;
    }

    /** Forgets one player's replay record; called when the player disconnects. */
    public static void clear(UUID playerId) {
        if (playerId != null) {
            lastSequences.keySet().removeIf(key -> key.playerId().equals(playerId));
        }
    }

    /** Forgets every replay record; called when the server starts and stops. */
    public static void clearAll() {
        lastSequences.clear();
    }

    /** The replay-protection key: one record per player and hand. */
    private record Key(UUID playerId, InteractionHand hand) {
    }
}
