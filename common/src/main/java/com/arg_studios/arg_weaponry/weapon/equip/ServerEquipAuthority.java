package com.arg_studios.arg_weaponry.weapon.equip;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The authoritative half of the draw and holster lifecycle.
 *
 * <h2>What the server decides, and what it never trusts</h2>
 * <p>The server never delays a slot change - vanilla's carried-item packet does that, and it arrives when the client applies
 * the switch. What the server does is</p>
 * <ol>
 *   <li>track the held main-hand stack every tick, so a draw can start for a weapon that just became held (hotbar selection,
 *   join, respawn, main-hand swap) and so a forced change is recognised as a hard interrupt;</li>
 *   <li>run the two windows on tick timers: {@link EquipState#DRAWING} ends when the authored window does,
 *   {@link EquipState#HOLSTERING} ends when the switch it waits for arrives, or when the safety deadline fires;</li>
 *   <li>answer {@link DrawHolsterManager#isEquipBusy(Player)}, which firing, reloading, inspection, fire-mode switching and
 *   aiming consult;</li>
 *   <li>set the persistent first-draw flag when a first-draw clip starts, and broadcast the draw/holster sounds to the other
 *   players.</li>
 * </ol>
 *
 * <h2>What a request may and may not do</h2>
 * <p>The only client input is {@link EquipActionPayload}: "a switch is starting" or "I changed my mind". The held stack, the
 * definition, the magazine state, the clip, its duration and the sound are all resolved from the server's own copy, and the
 * target slot is used for exactly one comparison - recognising the delayed switch as the expected completion. A request that
 * arrives out of order, faster than {@link EquipRequestGuard#COOLDOWN_TICKS} or for a weapon without a holster clip is
 * ignored.</p>
 *
 * <p>This side keeps no animation state of its own: it never selects a clip the client might not have and never plays
 * anything locally. It only decides which gameplay actions are refused, for how long, and which sound the other players
 * hear.</p>
 */
final class ServerEquipAuthority {

    private static final ServerEquipAuthority INSTANCE = new ServerEquipAuthority();

    private final Map<UUID, EquipPhase> phases = new ConcurrentHashMap<>();
    private final Map<UUID, HeldWeapon> held = new ConcurrentHashMap<>();
    private final Map<UUID, EquipRequestGuard> requests = new ConcurrentHashMap<>();

    private ServerEquipAuthority() {
    }

    /** @return the shared server authority. */
    static ServerEquipAuthority instance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ queries

    /**
     * @param player the player to test, may be {@code null}
     * @return the authoritative phase, never {@code null}
     */
    EquipPhase phase(Player player) {
        if (player == null) {
            return EquipPhase.IDLE;
        }
        EquipPhase phase = phases.get(player.getUUID());
        return phase == null ? EquipPhase.IDLE : phase;
    }

    /**
     * @param player the player to test, may be {@code null}
     * @return whether the authoritative window is running, i.e. whether gameplay actions must be refused
     */
    boolean isBusy(Player player) {
        return phase(player).isBusy();
    }

    // ------------------------------------------------------------------ tick

    /**
     * Advances every player's authoritative equip state. Runs on the server thread once per tick.
     *
     * @param server the running server, may be {@code null}
     */
    void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            online.add(id);
            if (!player.isAlive() || player.isSpectator()) {
                // Death or spectator mode ends every window: nothing may keep a weapon blocked afterwards.
                phases.remove(id);
                held.remove(id);
                continue;
            }
            tickPlayer(player);
        }
        phases.keySet().retainAll(online);
        held.keySet().retainAll(online);
        requests.keySet().retainAll(online);
    }

    /**
     * Advances one player.
     *
     * @param player the player
     */
    private void tickPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        // The offhand policy runs before anything else reads the held items, so a weapon that arrived through a command, a
        // container click or another mod never survives a tick in that hand.
        OffhandRestriction.enforce(player);
        ItemStack stack = player.getMainHandItem();
        int slot = player.getInventory().getSelectedSlot();
        String itemId = HeldWeapon.itemIdOf(stack);

        EquipPhase phase = phases.getOrDefault(id, EquipPhase.IDLE);
        if (phase.isBusy()) {
            EquipPhase.Observation observation = phase.classify(slot, itemId);
            if (observation == EquipPhase.Observation.HARD_INTERRUPT
                    || observation == EquipPhase.Observation.EXPECTED_COMPLETION) {
                // Either the switch this holster waited for arrived - a normal completion - or the held weapon changed
                // without a request, which skips the rest of the window. Both end in the same place: the weapon now held
                // draws.
                phases.remove(id);
                HeldWeapon previous = held.put(id, HeldWeapon.of(stack, slot));
                startDraw(player, stack, previous);
                return;
            }
            EquipPhase advanced = phase.advance();
            boolean drawFinished = advanced.state() == EquipState.DRAWING && advanced.isWindowElapsed();
            // A holster is never finished by its own clock: it waits for the switch, and only the safety deadline ends it
            // without one.
            boolean holsterTimedOut = advanced.state() == EquipState.HOLSTERING && advanced.isTimedOut();
            if (drawFinished || holsterTimedOut) {
                phases.remove(id);
                if (holsterTimedOut) {
                    Constants.LOG.debug("[ARG] The holster window of {} timed out before the slot change arrived; the "
                            + "equip state was cleared", id);
                }
            } else {
                phases.put(id, advanced);
            }
            return;
        }

        // Idle: did another weapon become the held item?
        HeldWeapon previous = held.put(id, HeldWeapon.of(stack, slot));
        if (previous == null || previous.differsFrom(slot, itemId)) {
            startDraw(player, stack, previous);
        }
    }

    /**
     * Starts the draw window of a weapon that just became held, consumes its first draw when one applies, and broadcasts the
     * draw sound to everyone else.
     *
     * <p>The window exists only when the configuration selects a draw clip; a weapon whose entries all have a condition that
     * does not hold is not blocked, which is the same "nothing to play, nothing to wait for" rule the client uses. The sound
     * is independent of that: the draw happened whether or not there is a clip to show.</p>
     *
     * <p>The change is owned when the feature handles the weapon that became held <em>or</em> the one that stopped being held.
     * The sound is broadcast for an owned change even when the incoming weapon declares no clips of its own, because
     * {@code WeaponHeldLifecycle} stays silent for every change this feature owns - without this the hand-off from a handled
     * weapon to an ordinary one would be silent for the other players but audible for the player themselves.</p>
     *
     * @param player   the player who now holds the weapon
     * @param stack    the held stack
     * @param previous the observation of the weapon that stopped being held, may be {@code null}
     */
    private void startDraw(ServerPlayer player, ItemStack stack, HeldWeapon previous) {
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return;
        }
        WeaponDefinition definition = weapon.definition();
        boolean handled = DrawHolsterManager.handles(definition);
        if (!handled && !DrawHolsterManager.ownsLifecycle(previous == null ? null : previous.definition(), definition)) {
            // No clip on either side of the change: no window, no flag and no sound, exactly as before this feature existed.
            return;
        }
        int ammoCount = weapon.animationAmmoCount(stack);
        boolean firstDraw = FirstDrawFlag.needsFirstDraw(FirstDrawFlag.isDrawn(stack), false);
        if (handled) {
            EquipAnimationResolver.Selection selection =
                    EquipAnimationResolver.selectDraw(definition, ammoCount, firstDraw);
            if (selection.firstDrawEntry() || (firstDraw && !EquipAnimationResolver.hasFirstDrawEntry(definition))) {
                // The stack's first draw has been consumed: either the first-draw clip is starting, or the weapon declares no
                // such clip, in which case the flag only exists to keep the draw_first sound from repeating.
                FirstDrawFlag.markDrawn(stack);
            }
            if (selection.isPresent()) {
                EquipPhase phase = EquipPhase.drawing(player.getInventory().getSelectedSlot(),
                        HeldWeapon.itemIdOf(stack), selection.clip(),
                        EquipTiming.secondsToTicks(selection.durationSeconds()));
                if (phase.isBusy()) {
                    phases.put(player.getUUID(), phase);
                }
            }
        }
        EquipSoundBroadcast.draw(player, definition, firstDraw, ammoCount <= 0);
    }

    // ------------------------------------------------------------------ requests

    /**
     * One holster request from the client, validated entirely against the server's own state.
     *
     * <p>Three outcomes are possible:</p>
     * <ul>
     *   <li>the player is already holstering this weapon - the target is replaced, so a scroll that keeps moving does not
     *   restart the clip or the sound;</li>
     *   <li>the player interrupted a draw - the draw window is replaced by the holster window, because the client has already
     *   ended its draw clip and is now playing the holster; ignoring it (the old behaviour) left the other players without a
     *   holster sound and let the longer draw window outlive the switch;</li>
     *   <li>the player was idle - a new holster window starts.</li>
     * </ul>
     *
     * @param player     the requesting player
     * @param targetSlot the slot the client is switching to
     * @param sequence   the request's sequence number
     */
    void requestHolster(ServerPlayer player, int targetSlot, int sequence) {
        if (player == null || !player.isAlive() || player.isSpectator()) {
            return;
        }
        DrawHolsterConfig.Settings config = DrawHolsterConfig.get();
        if (!config.enableDrawHolster() || !config.holsterSwitchDelay()) {
            // Delayed switching is off: the client would not have sent this, but a stale or hostile payload must still change
            // nothing.
            return;
        }
        UUID id = player.getUUID();
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return;
        }
        WeaponDefinition definition = weapon.definition();
        if (!EquipAnimationResolver.hasHolsterClip(definition) || !DrawHolsterManager.handles(definition)) {
            return;
        }
        int currentSlot = player.getInventory().getSelectedSlot();
        if (targetSlot < 0 || targetSlot >= Inventory.getSelectionSize() || targetSlot == currentSlot) {
            return;
        }
        EquipPhase running = phases.getOrDefault(id, EquipPhase.IDLE);
        if (running.state() == EquipState.HOLSTERING && running.itemId() != null
                && running.itemId().equals(HeldWeapon.itemIdOf(stack))) {
            // The same weapon is still being put away: only the destination moved.
            if (running.pendingSlot() != targetSlot && requestAllowed(id, player.level().getGameTime(), sequence)) {
                phases.put(id, running.withPendingSlot(targetSlot));
            }
            return;
        }
        if (!requestAllowed(id, player.level().getGameTime(), sequence)) {
            return;
        }
        int ammoCount = weapon.animationAmmoCount(stack);
        EquipAnimationResolver.Selection selection = EquipAnimationResolver.selectHolster(definition, ammoCount);
        if (!selection.isPresent()) {
            return;
        }
        phases.put(id, EquipPhase.holstering(currentSlot, HeldWeapon.itemIdOf(stack), selection.clip(),
                EquipTiming.secondsToTicks(selection.durationSeconds()), targetSlot));
        EquipSoundBroadcast.holster(player, definition, ammoCount <= 0);
    }

    /**
     * Abandons a delayed switch the client cancelled, so the server stops blocking as soon as the client does.
     *
     * @param player   the requesting player
     * @param sequence the request's sequence number
     */
    void cancelHolster(ServerPlayer player, int sequence) {
        if (player == null) {
            return;
        }
        UUID id = player.getUUID();
        EquipPhase phase = phases.get(id);
        if (phase == null || phase.state() != EquipState.HOLSTERING) {
            return;
        }
        // A cancel cannot start anything, so only the replay check applies: a player who changes their mind inside the
        // request cooldown must still be able to stop the window they just opened.
        EquipRequestGuard previous = requests.get(id);
        if (previous != null && sequence <= previous.sequence()) {
            return;
        }
        requests.put(id, new EquipRequestGuard(player.level().getGameTime(), sequence));
        phases.remove(id);
    }

    /**
     * Accepts one request only when {@link EquipRequestGuard} allows it - a strictly newer sequence and at least
     * {@link EquipRequestGuard#COOLDOWN_TICKS} ticks after the last accepted one.
     *
     * @param id       the player
     * @param now      the current game time
     * @param sequence the request's sequence number
     * @return whether this request may start or move a window
     */
    private boolean requestAllowed(UUID id, long now, int sequence) {
        EquipRequestGuard previous = requests.get(id);
        if (!EquipRequestGuard.accepts(previous, now, sequence)) {
            return false;
        }
        requests.put(id, new EquipRequestGuard(now, sequence));
        return true;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Forgets one player's authoritative state and the held baseline the server tracks.
     *
     * @param playerId the player, may be {@code null}
     */
    void clear(UUID playerId) {
        if (playerId == null) {
            return;
        }
        phases.remove(playerId);
        held.remove(playerId);
        requests.remove(playerId);
    }

    /** Forgets every player's state; used when the server starts or stops. */
    void clearAll() {
        phases.clear();
        held.clear();
        requests.clear();
    }
}
