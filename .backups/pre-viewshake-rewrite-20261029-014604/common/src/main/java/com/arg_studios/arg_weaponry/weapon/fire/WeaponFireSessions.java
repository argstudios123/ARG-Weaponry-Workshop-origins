package com.arg_studios.arg_weaponry.weapon.fire;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.compat.WeaponCompatibility;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.network.WeaponFireResultPayload;
import com.arg_studios.arg_weaponry.network.WeaponFireSessionPayload;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.AmmoCapacity;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionRules;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult;
import com.arg_studios.arg_weaponry.weapon.reload.WeaponReloadRuntime;
import com.arg_studios.arg_weaponry.weapon.state.FireMode;
import com.arg_studios.arg_weaponry.weapon.state.WeaponState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server-owned trigger sessions for automatic and burst fire.
 *
 * <h2>Why a session exists at all</h2>
 * <p>Semi-automatic fire needs no server state: one packet is one press, and {@code WeaponFireService#fire} validates
 * and executes it. Automatic and burst fire cannot work that way. Sending one packet per shot would let the client
 * decide the rate, and the press-based input path cannot produce one packet per shot while a button is held at all. A
 * session therefore records "this player holds the trigger of this weapon" and the server schedules the shots itself,
 * at the weapon's own cadence.</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li><strong>Press</strong> - the client sends a trigger start carrying <em>one</em> sequence number for the whole
 *   press. {@link #start} validates it (weapon, resolved mode, ammunition, compatibility, reload lock), creates the
 *   session and fires the first shot immediately, exactly like a semi-automatic press.</li>
 *   <li><strong>Heartbeat</strong> - while the trigger stays held the client re-sends the <em>same</em> sequence every
 *   {@value #HEARTBEAT_TICKS} ticks. A repeat of the running sequence only refreshes the lease; it can never start a
 *   second session, so a held trigger cannot begin a second burst after the first one finished.</li>
 *   <li><strong>Release</strong> - the client sends the release for that sequence, or simply stops if the button is
 *   lost; the session is removed. A release is idempotent.</li>
 *   <li><strong>Lease</strong> - every session expires {@value #LEASE_TICKS} ticks after the last heartbeat. A lost
 *   release or a frozen client therefore stops firing within two seconds instead of firing forever.</li>
 * </ol>
 *
 * <h2>Scheduling</h2>
 * <p>{@link #serverTick} runs on the server thread once per tick and fires at most <em>one</em> shot per session per
 * tick. The interval between two shots is {@link WeaponFireRuntime#cooldownTicks(int)} - the same
 * {@code ceil(1200 / rpm)} gate the semi-automatic path uses - so every mode shares one cadence authority. If the
 * server falls behind, the next shot is scheduled from <em>now</em> rather than from the missed tick, so a lag spike
 * can never be repaid as a burst of catch-up shots.</p>
 *
 * <p>A burst is a fixed number of shots per press. {@code continuous_burst: false} ends the session when the last shot
 * of the burst has been fired; {@code true} starts the next burst after {@code time_between_bursts} (seconds, rounded
 * up to whole ticks) while the trigger stays held.</p>
 *
 * <h2>Validation</h2>
 * <p>Every tick the session re-resolves everything from the server: the held stack, the weapon identifier, the
 * inventory slot, the resolved firing mode, the compatibility verdict, the reload lock and the ammunition. Any change
 * ends the session, so switching weapons, dying, disconnecting, starting a reload or losing compatibility can never
 * leave a weapon firing.</p>
 */
public final class WeaponFireSessions {

    /** Ticks between two held-trigger heartbeats the client sends. */
    public static final int HEARTBEAT_TICKS = 10;

    /** Ticks a session stays alive without a heartbeat; two seconds at 20 ticks per second. */
    public static final int LEASE_TICKS = 40;

    /** Server ticks per second, the unit every duration here is expressed in. */
    private static final int TICKS_PER_SECOND = 20;

    private static final Map<FireSession.Key, FireSession> sessions = new ConcurrentHashMap<>();

    /**
     * The highest trigger sequence seen per player and hand.
     *
     * <p>This is the press-level replay protection: a sequence that does not increase is a repeat of a press that was
     * already handled (a heartbeat, a duplicated packet, or the tail of a burst that already ended), never a new
     * press.</p>
     */
    private static final Map<FireSession.Key, Integer> lastTriggerSequences = new ConcurrentHashMap<>();

    private WeaponFireSessions() {
    }

    /**
     * @param seconds a delay between two bursts
     * @return the delay in whole server ticks, at least one when the delay is positive; {@code 0} for no delay
     */
    public static int burstGapTicks(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(seconds * TICKS_PER_SECOND));
    }

    /**
     * Handles a trigger press, a heartbeat, or a repeated press packet.
     *
     * @param player   the requesting player, resolved by the server
     * @param hand     the hand named by the request
     * @param sequence the sequence number of this press; a repeat of the running press's number is a heartbeat
     * @return {@link WeaponActionResult#OK} when a session is running afterwards, otherwise the reason it is not
     */
    public static WeaponActionResult start(ServerPlayer player, InteractionHand hand, int sequence) {
        if (player == null || hand == null || !player.isAlive() || player.isSpectator()) {
            return reject(player, hand, sequence, WeaponActionResult.INVALID_CONTEXT);
        }
        UUID playerId = player.getUUID();
        FireSession.Key key = new FireSession.Key(playerId, hand);
        long now = player.level().getGameTime();

        FireSession active = sessions.get(key);
        int previousSequence = lastTriggerSequences.getOrDefault(key, 0);
        if (sequence <= previousSequence) {
            // Not a new press: refresh the running session's lease, or ignore the tail of a session that already ended.
            if (active != null && active.triggerSequence() == sequence) {
                sessions.put(key, active.withExpiry(now + LEASE_TICKS));
                return WeaponActionResult.OK;
            }
            // The press is over (burst complete, refused earlier, or never started): tell the client so it stops
            // heartbeating, and start no new session for it.
            return reject(player, hand, sequence, WeaponActionResult.STALE_REQUEST);
        }

        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return reject(player, hand, sequence, WeaponActionResult.NOT_A_WEAPON);
        }
        WeaponDefinition definition = weapon.definition();
        WeaponState state = weapon.state(stack);
        FireMode mode = FiringModes.resolve(definition.firingModes(), state.fireMode());
        if (mode == null || mode == FireMode.SINGLE) {
            // Automatic and burst are the only modes with a trigger session; a semi-automatic press is one request and
            // never reaches this method.
            return reject(player, hand, sequence, WeaponActionResult.FIRE_MODE_UNSUPPORTED);
        }
        if (WeaponReloadRuntime.isReloading(playerId, hand)) {
            return reject(player, hand, sequence, WeaponActionResult.RELOAD_IN_PROGRESS);
        }
        if (!WeaponCompatibility.isUsable(playerId)) {
            return reject(player, hand, sequence, WeaponActionResult.CONTENT_MISMATCH);
        }
        AmmoCapacity capacity = weapon.ammoCapacity();
        WeaponActionResult allowed = WeaponActionRules.validateFire(capacity, state, definition.firingModes());
        if (!allowed.isAllowed()) {
            return reject(player, hand, sequence, allowed);
        }

        // A genuinely new press: it replaces any session that was still running for this hand.
        lastTriggerSequences.put(key, sequence);
        sessions.remove(key);

        int rpm = definition.rpm() == null ? 0 : definition.rpm();
        FireSession session = new FireSession(playerId, hand, itemIdOf(stack), slotOf(player, hand), mode,
                definition.burstCount(), definition.continuousBurst(), WeaponFireRuntime.cooldownTicks(rpm),
                burstGapTicks(definition.timeBetweenBursts()), sequence,
                mode == FireMode.BURST ? definition.burstCount() : 0, 0, now, now, now + LEASE_TICKS);

        WeaponFireService.FireResult first = WeaponFireService.fireFromSession(player, hand, mode);
        if (first.fired()) {
            sendShot(player, hand, first);
            session = session.advanced(now, now + LEASE_TICKS, now + session.cooldownTicks(), session.nextBurstTick(),
                    Math.max(0, session.shotsRemaining() - 1), 1);
        } else if (first.result() != WeaponActionResult.CADENCE_BLOCKED) {
            // Nothing was fired and nothing can be fired later: do not keep a session around.
            sendSession(player, hand, sequence, first.result(), mode, false);
            return first.result();
        }

        if (session.mode() == FireMode.BURST && session.shotsRemaining() <= 0 && !session.continuousBurst()) {
            // A one-shot burst (burst_count == 1) is already complete.
            sendSession(player, hand, sequence, WeaponActionResult.OK, mode, false);
            return WeaponActionResult.OK;
        }
        sessions.put(key, session);
        sendSession(player, hand, sequence, WeaponActionResult.OK, mode, true);
        Constants.LOG.debug("[ARG] {} started a {} session with '{}' in {} (burst {}, gap {} tick(s), cooldown {} "
                        + "tick(s))", player.getName().getString(), mode, definition.name(), hand,
                definition.burstCount(), session.burstGapTicks(), session.cooldownTicks());
        return WeaponActionResult.OK;
    }

    /**
     * Ends the session of one hand.
     *
     * <p>Idempotent: a release that arrives after the burst already finished, or twice, changes nothing and still
     * answers {@link WeaponActionResult#OK}. The sequence is folded into the press record so a later packet of the same
     * press can never start a new session.</p>
     *
     * @param player   the player releasing the trigger, may be {@code null}
     * @param hand     the hand, may be {@code null}
     * @param sequence the press's sequence number
     * @return always {@link WeaponActionResult#OK}
     */
    public static WeaponActionResult release(ServerPlayer player, InteractionHand hand, int sequence) {
        if (player == null || hand == null) {
            return WeaponActionResult.INVALID_CONTEXT;
        }
        FireSession.Key key = new FireSession.Key(player.getUUID(), hand);
        FireSession session = sessions.remove(key);
        lastTriggerSequences.merge(key, sequence, Math::max);
        if (session != null) {
            Constants.LOG.debug("[ARG] {} released the trigger of '{}' in {} after {} shot(s)",
                    player.getName().getString(), session.itemId(), hand, session.shotsFired());
            sendSession(player, hand, session.triggerSequence(), WeaponActionResult.OK, session.mode(), false);
        }
        return WeaponActionResult.OK;
    }

    /**
     * Stops one hand's session from the server side, for example when a reload starts.
     *
     * @param playerId the player, may be {@code null}
     * @param hand     the hand, may be {@code null}
     * @return {@code true} when a session was running and has been stopped
     */
    public static boolean cancel(UUID playerId, InteractionHand hand) {
        if (playerId == null || hand == null) {
            return false;
        }
        return sessions.remove(new FireSession.Key(playerId, hand)) != null;
    }

    /**
     * Stops every session of one player.
     *
     * @param playerId the player, may be {@code null}
     */
    public static void cancelAll(UUID playerId) {
        if (playerId != null) {
            sessions.keySet().removeIf(key -> key.playerId().equals(playerId));
        }
    }

    /**
     * @param playerId the player, may be {@code null}
     * @param hand     the hand, may be {@code null}
     * @return whether that hand currently holds an accepted trigger
     */
    public static boolean isFiring(UUID playerId, InteractionHand hand) {
        return playerId != null && hand != null && sessions.containsKey(new FireSession.Key(playerId, hand));
    }

    /**
     * Advances every session by one server tick.
     *
     * @param server the running server, may be {@code null}
     */
    public static void serverTick(MinecraftServer server) {
        if (server == null || sessions.isEmpty()) {
            return;
        }
        for (Map.Entry<FireSession.Key, FireSession> entry : List.copyOf(sessions.entrySet())) {
            FireSession session = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(session.playerId());
            if (player == null) {
                sessions.remove(entry.getKey());
                continue;
            }
            long now = player.level().getGameTime();
            if (FireScheduling.expired(session, now)) {
                sessions.remove(entry.getKey());
                Constants.LOG.debug("[ARG] The firing session of {} expired (trigger lease)", session.playerId());
                sendSession(player, session.hand(), session.triggerSequence(), WeaponActionResult.STALE_REQUEST,
                        session.mode(), false);
                continue;
            }
            WeaponActionResult invalid = validate(player, session);
            if (invalid != null) {
                sessions.remove(entry.getKey());
                Constants.LOG.debug("[ARG] The firing session of {} ended: {}", session.playerId(), invalid);
                sendSession(player, session.hand(), session.triggerSequence(), invalid, session.mode(), false);
                continue;
            }
            FireScheduling.Action action = FireScheduling.action(session, now);
            if (action == FireScheduling.Action.WAIT) {
                continue;
            }
            if (action == FireScheduling.Action.FINISH) {
                // A finished burst that must not repeat: the session ends and a new burst needs a new press.
                sessions.remove(entry.getKey());
                sendSession(player, session.hand(), session.triggerSequence(), WeaponActionResult.OK, session.mode(),
                        false);
                continue;
            }
            if (action == FireScheduling.Action.START_NEW_BURST) {
                session = FireScheduling.startNextBurst(session, now);
            }
            fireDueShot(player, session);
        }
    }

    /**
     * Fires one scheduled shot of a session and stores the updated schedule.
     *
     * @param player  the player the session belongs to
     * @param session the session whose shot is due
     */
    private static void fireDueShot(ServerPlayer player, FireSession session) {
        FireSession.Key key = session.key();
        long now = player.level().getGameTime();
        WeaponFireService.FireResult shot = WeaponFireService.fireFromSession(player, session.hand(), session.mode());
        if (!shot.fired()) {
            if (!FireScheduling.endsSession(shot.result())) {
                // Not a failure: the cadence gate is still closed. Keep the session and retry on the next tick.
                return;
            }
            // A terminal reason (empty magazine, different weapon, lost compatibility, reload started, ...).
            sessions.remove(key);
            Constants.LOG.debug("[ARG] The firing session of {} ended on a refused shot: {}", session.playerId(),
                    shot.result());
            sendSession(player, session.hand(), session.triggerSequence(), shot.result(), session.mode(), false);
            return;
        }
        sendShot(player, session.hand(), shot);

        FireScheduling.Outcome outcome = FireScheduling.afterShot(session, now, LEASE_TICKS);
        if (outcome.finished()) {
            sessions.remove(key);
            sendSession(player, session.hand(), session.triggerSequence(), WeaponActionResult.OK, session.mode(),
                    false);
            return;
        }
        sessions.put(key, outcome.session());
    }

    /**
     * Re-resolves everything a running session depends on.
     *
     * @param player  the player
     * @param session the session
     * @return the reason the session must stop, or {@code null} when it may continue
     */
    private static WeaponActionResult validate(ServerPlayer player, FireSession session) {
        if (!player.isAlive() || player.isSpectator()) {
            return WeaponActionResult.INVALID_CONTEXT;
        }
        ItemStack stack = player.getItemInHand(session.hand());
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return WeaponActionResult.NOT_A_WEAPON;
        }
        if (!itemIdOf(stack).equals(session.itemId())) {
            return WeaponActionResult.NOT_A_WEAPON;
        }
        if (slotOf(player, session.hand()) != session.slot()) {
            return WeaponActionResult.NOT_A_WEAPON;
        }
        if (WeaponReloadRuntime.isReloading(player.getUUID(), session.hand())) {
            return WeaponActionResult.RELOAD_IN_PROGRESS;
        }
        if (!WeaponCompatibility.isUsable(player.getUUID())) {
            return WeaponActionResult.CONTENT_MISMATCH;
        }
        WeaponDefinition definition = weapon.definition();
        WeaponState state = weapon.state(stack);
        FireMode mode = FiringModes.resolve(definition.firingModes(), state.fireMode());
        if (mode != session.mode()) {
            // The player switched modes, or the content changed under the session: stop rather than keep firing a mode
            // the weapon no longer selects.
            return WeaponActionResult.FIRE_MODE_UNSUPPORTED;
        }
        return WeaponActionRules.validateFire(weapon.ammoCapacity(), state, definition.firingModes()).isAllowed()
                ? null : WeaponActionResult.MAGAZINE_EMPTY;
    }

    /**
     * Answers a refused trigger request so the client stops heartbeating a press that will never fire.
     *
     * @param player   the player, may be {@code null}
     * @param hand     the hand, may be {@code null}
     * @param sequence the press sequence
     * @param result   the reason
     * @return {@code result}, so callers can return it directly
     */
    private static WeaponActionResult reject(ServerPlayer player, InteractionHand hand, int sequence,
                                             WeaponActionResult result) {
        sendSession(player, hand, sequence, result, null, false);
        return result;
    }

    /**
     * Notifies the client that an accepted shot happened, so it can animate exactly that shot.
     *
     * @param player the shooter
     * @param hand   the hand
     * @param shotId the server-assigned, monotonically increasing shot id
     */
    private static void sendShot(ServerPlayer player, InteractionHand hand, WeaponFireService.FireResult shot) {
        if (player == null || hand == null || shot == null
                || !Services.NETWORK.canSendToPlayer(player, WeaponFireResultPayload.TYPE)) {
            return;
        }
        Services.NETWORK.sendToPlayer(player, new WeaponFireResultPayload(hand, shot.shotId(),
                shot.hit() != null, shot.headshot(), shot.killed()));
    }

    /**
     * Notifies the client about the state of a trigger session.
     *
     * @param player   the player
     * @param hand     the hand
     * @param sequence the press's sequence number
     * @param result   the outcome
     * @param mode     the session's mode, may be {@code null}
     * @param active   whether a session is running afterwards
     */
    private static void sendSession(ServerPlayer player, InteractionHand hand, int sequence,
                                    WeaponActionResult result, FireMode mode, boolean active) {
        if (player == null || hand == null || sequence < 1
                || !Services.NETWORK.canSendToPlayer(player, WeaponFireSessionPayload.TYPE)) {
            return;
        }
        Services.NETWORK.sendToPlayer(player, new WeaponFireSessionPayload(hand, sequence, result, mode, active));
    }

    /**
     * @param player the player
     * @param hand   the hand
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

    /** Forgets one player's sessions and press record; called when the player disconnects. */
    public static void clear(UUID playerId) {
        if (playerId != null) {
            cancelAll(playerId);
            lastTriggerSequences.keySet().removeIf(key -> key.playerId().equals(playerId));
        }
    }

    /** Forgets every session; called when the server starts and stops. */
    public static void clearAll() {
        sessions.clear();
        lastTriggerSequences.clear();
    }

    /** @return the number of running sessions, for diagnostics and tests */
    public static int size() {
        return sessions.size();
    }

    /** @return a snapshot of the running sessions, for diagnostics and tests */
    public static List<FireSession> snapshot() {
        return new ArrayList<>(sessions.values());
    }
}
