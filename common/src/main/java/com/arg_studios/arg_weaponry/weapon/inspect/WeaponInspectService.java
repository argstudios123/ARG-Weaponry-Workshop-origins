package com.arg_studios.arg_weaponry.weapon.inspect;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.compat.WeaponCompatibility;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.network.WeaponInspectResultPayload;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult;
import com.arg_studios.arg_weaponry.weapon.reload.WeaponReloadRuntime;
import com.arg_studios.arg_weaponry.weapon.state.WeaponState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;

/**
 * The authoritative inspection action.
 *
 * <h2>Why inspection is a server action</h2>
 * <p>Inspection plays a weapon animation and a sound, and both must follow the same rules every other weapon action
 * follows: it must not start while a reload owns the weapon, it must not start twice from a held key, and it must end
 * when the weapon, the player or the world changes. Making the client the owner of that state would mean the server could
 * not refuse a request, so the session lives here, exactly like a reload session, and the client is told the clip name and
 * the duration through {@link WeaponInspectResultPayload}.</p>
 *
 * <h2>Interaction with the other actions</h2>
 * <ul>
 *   <li>A reload and an inspection are mutually exclusive: a reload request cancels the inspection, and an inspection
 *   request during a reload is refused with {@link WeaponActionResult#RELOAD_IN_PROGRESS}.</li>
 *   <li>Firing cancels the inspection (the shot is the more important action), and the accepted shot result makes the
 *   client drop the clip.</li>
 *   <li>Repeating the request while it runs is refused with {@link WeaponActionResult#INSPECT_IN_PROGRESS}, so a held key
 *   cannot restart the clip.</li>
 *   <li>A weapon with no matching {@code inspect_anims} entry is refused with
 *   {@link WeaponActionResult#INSPECTION_UNAVAILABLE} and nothing is animated or played.</li>
 * </ul>
 */
public final class WeaponInspectService {

    /** Server ticks per second, used to convert the authored duration in seconds. */
    private static final int TICKS_PER_SECOND = 20;

    private WeaponInspectService() {
    }

    /**
     * @param seconds a duration in seconds
     * @return the duration in whole server ticks, at least one
     */
    public static int durationTicks(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            return 1;
        }
        return Math.max(1, (int) Math.ceil(seconds * TICKS_PER_SECOND));
    }

    /**
     * Handles one inspection request.
     *
     * @param player   the requesting player, resolved by the server
     * @param hand     the hand named by the request
     * @param sequence the request's sequence number
     * @return the decision; the client is told through {@link WeaponInspectResultPayload} either way
     */
    public static WeaponActionResult request(ServerPlayer player, InteractionHand hand, int sequence) {
        if (player == null || hand == null || !player.isAlive() || player.isSpectator()) {
            return reply(player, hand, sequence, WeaponActionResult.INVALID_CONTEXT, null, 0, false, false);
        }
        // [DrawHolster hook] Inspecting is refused while the weapon is being drawn or holstered.
        if (com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.isEquipBusy(player)) {
            return reply(player, hand, sequence, WeaponActionResult.EQUIPPING, null, 0, false, false);
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return reply(player, hand, sequence, WeaponActionResult.NOT_A_WEAPON, null, 0, false, false);
        }
        WeaponDefinition definition = weapon.definition();
        if (definition.inspectAnimations().isEmpty()) {
            // No inspect_anims at all: inspection is unsupported, and nothing is substituted for it.
            return reply(player, hand, sequence, WeaponActionResult.UNSUPPORTED, null, 0, false, false);
        }
        if (WeaponReloadRuntime.isReloading(player.getUUID(), hand)) {
            return reply(player, hand, sequence, WeaponActionResult.RELOAD_IN_PROGRESS, null, 0, false, false);
        }
        if (!WeaponCompatibility.isUsable(player.getUUID())) {
            return reply(player, hand, sequence, WeaponActionResult.CONTENT_MISMATCH, null, 0, false, false);
        }
        WeaponState state = weapon.state(stack);
        if (state == null) {
            return reply(player, hand, sequence, WeaponActionResult.INVALID_WEAPON_STATE, null, 0, false, false);
        }

        int ammo = state.ammo();
        InspectionSelection.Result selection = InspectionSelection.select(definition, ammo);
        if (!selection.isPresent()) {
            if (!selection.unsupportedConditions().isEmpty()) {
                Constants.LOG.debug("[ARG] Weapon '{}' declares inspection conditions this build cannot evaluate: {}",
                        definition.name(), selection.unsupportedConditions());
            }
            return reply(player, hand, sequence, WeaponActionResult.INSPECTION_UNAVAILABLE, null, 0, false, false);
        }
        WeaponDefinition.AnimationSelection entry = selection.orElseNull();
        boolean emptyMagazine = ammo <= 0;
        int durationTicks = durationTicks(entry.duration());
        long now = player.level().getGameTime();
        InspectSession session = new InspectSession(player.getUUID(), hand, itemIdOf(stack), slotOf(player, hand),
                sequence, entry.name(), durationTicks, emptyMagazine, now, now + durationTicks);

        WeaponInspectRuntime.StartResult start = WeaponInspectRuntime.start(session);
        if (start == WeaponInspectRuntime.StartResult.ALREADY_INSPECTING) {
            return reply(player, hand, sequence, WeaponActionResult.INSPECT_IN_PROGRESS, null, 0, false, false);
        }
        if (start == WeaponInspectRuntime.StartResult.STALE_REQUEST) {
            return reply(player, hand, sequence, WeaponActionResult.STALE_REQUEST, null, 0, false, false);
        }

        boolean aimingBlocked = definition.aiming() != null && definition.aiming().noAimingOnInspect();
        Constants.LOG.debug("[ARG] {} is inspecting '{}' in {} with '{}' for {} tick(s) (empty magazine: {}, aiming "
                        + "blocked: {})", player.getName().getString(), definition.name(), hand, entry.name(),
                durationTicks, emptyMagazine, aimingBlocked);
        return reply(player, hand, sequence, WeaponActionResult.OK, entry.name(), durationTicks, emptyMagazine,
                aimingBlocked);
    }

    /**
     * Cancels the inspection of one hand, if any.
     *
     * @param playerId the player, may be {@code null}
     * @param hand     the hand, may be {@code null}
     * @param reason   the reason, for diagnostics
     * @return {@code true} when a session was running and has been stopped
     */
    public static boolean cancel(UUID playerId, InteractionHand hand, WeaponActionResult reason) {
        InspectSession removed = WeaponInspectRuntime.remove(playerId, hand);
        if (removed == null) {
            return false;
        }
        Constants.LOG.debug("[ARG] The inspection of '{}' in {} ended early: {}", removed.animation(), hand, reason);
        return true;
    }

    /**
     * Advances every running inspection by one server tick.
     *
     * <p>A session ends when its duration has elapsed, and is cancelled immediately when the player dies or leaves, the
     * held item or its slot changes, or a reload starts. Every one of those checks is re-resolved from the server, so a
     * delayed completion can never touch a different weapon.</p>
     *
     * @param server the running server, may be {@code null}
     */
    public static void serverTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        Map<InspectSession.Key, InspectSession> snapshot = WeaponInspectRuntime.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        for (Map.Entry<InspectSession.Key, InspectSession> entry : snapshot.entrySet()) {
            InspectSession session = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(session.playerId());
            if (player == null) {
                WeaponInspectRuntime.remove(session.playerId(), session.hand());
                continue;
            }
            if (!player.isAlive() || player.isSpectator()) {
                cancel(session.playerId(), session.hand(), WeaponActionResult.INVALID_CONTEXT);
                continue;
            }
            ItemStack stack = player.getItemInHand(session.hand());
            if (!(stack.getItem() instanceof WeaponItem) || !itemIdOf(stack).equals(session.itemId())
                    || slotOf(player, session.hand()) != session.slot()) {
                cancel(session.playerId(), session.hand(), WeaponActionResult.NOT_A_WEAPON);
                continue;
            }
            if (WeaponReloadRuntime.isReloading(session.playerId(), session.hand())) {
                cancel(session.playerId(), session.hand(), WeaponActionResult.RELOAD_IN_PROGRESS);
                continue;
            }
            if (session.isFinished(player.level().getGameTime())) {
                // Completion is silent on the wire: the client plays the clip at the rate that makes it end exactly
                // when this session does, so it has already returned to idle. Sending an "OK with a clip name" here
                // would restart the animation instead of finishing it.
                WeaponInspectRuntime.remove(session.playerId(), session.hand());
                Constants.LOG.debug("[ARG] The inspection of '{}' in {} completed", session.animation(),
                        session.hand());
            }
        }
    }

    /**
     * @param player the player, may be {@code null}
     * @param hand   the hand holding the weapon
     * @return the inventory slot that hand refers to
     */
    private static int slotOf(ServerPlayer player, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? player.getInventory().getSelectedSlot() : Inventory.SLOT_OFFHAND;
    }

    /**
     * @param stack a weapon stack
     * @return the registered identifier of its item
     */
    private static String itemIdOf(ItemStack stack) {
        return String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /**
     * Sends the outcome to the client when its connection accepts the payload.
     *
     * @param player         the player, may be {@code null}
     * @param hand           the hand, may be {@code null}
     * @param sequence       the request sequence
     * @param result         the decision
     * @param animation      the clip name, or {@code null}
     * @param durationTicks  the duration in ticks, {@code 0} when none applies
     * @param emptyMagazine  whether the magazine was empty
     * @param aimingBlocked  whether the definition forbids aiming while inspecting
     * @return {@code result}, so callers can return it directly
     */
    private static WeaponActionResult reply(ServerPlayer player, InteractionHand hand, int sequence,
                                            WeaponActionResult result, String animation, int durationTicks,
                                            boolean emptyMagazine, boolean aimingBlocked) {
        if (player != null && hand != null && sequence >= 1
                && Services.NETWORK.canSendToPlayer(player, WeaponInspectResultPayload.TYPE)) {
            Services.NETWORK.sendToPlayer(player, new WeaponInspectResultPayload(hand, sequence, result,
                    animation == null ? "" : animation, durationTicks, emptyMagazine, aimingBlocked));
        }
        return result;
    }
}
