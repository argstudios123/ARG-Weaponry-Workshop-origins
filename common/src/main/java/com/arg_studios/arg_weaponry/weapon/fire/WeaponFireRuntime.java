package com.arg_studios.arg_weaponry.weapon.fire;

import net.minecraft.world.InteractionHand;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side firing cadence and anti-replay bookkeeping.
 *
 * <p>One small entry per player and hand, holding the item the entry belongs to, the earliest server tick at
 * which the next shot may be accepted, and the last sequence number that was accepted. Nothing here is
 * persisted, nothing references a stack or a definition, and no global registry of {@code ItemStack}s exists:
 * the map is keyed by the player's UUID and the hand, and it is emptied when a player disconnects or the server
 * stops.</p>
 *
 * <h2>Tick quantization</h2>
 * <p>The gate is expressed in <strong>server ticks</strong>, measured with
 * {@code ServerLevel#getGameTime()}, because that is the only clock the server ticks its simulation with. One
 * minute is 1200 ticks, so a weapon's cooldown is {@code ceil(1200 / rpm)} ticks:</p>
 * <ul>
 *   <li>300 RPM → 4 ticks → exactly 200 ms at 20 ticks per second;</li>
 *   <li>a weapon whose ideal interval is not a whole number of ticks is rounded <em>up</em>, so it fires
 *       slightly slower than nominal, never faster.</li>
 * </ul>
 * <p>A client request can only arrive between two ticks, so the effective resolution is one tick (50 ms). This
 * system therefore makes no claim of millisecond-accurate cadence: it guarantees that no more than
 * {@code floor(1200 / cooldownTicks)} requests can be accepted per minute.</p>
 *
 * <h2>Switching weapons</h2>
 * <p>An entry remembers which item it belongs to. The moment a different weapon appears in the same hand the
 * stored cooldown and sequence are ignored and replaced, so switching weapons never carries a cooldown into an
 * unrelated weapon and never inherits another weapon's sequence counter.</p>
 */
public final class WeaponFireRuntime {

    /** Never wait less than one tick, so two accepted shots can never land in the same tick. */
    private static final int MIN_COOLDOWN_TICKS = 1;

    /** Ticks in one minute, the unit {@code rpm} is expressed in. */
    private static final int TICKS_PER_MINUTE = 1200;

    /** Upper bound on the cooldown, so a nonsense cadence cannot lock a weapon out for hours. */
    private static final int MAX_COOLDOWN_TICKS = 2400;

    private static final Map<Key, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Monotonic per player/hand shot ids handed to the client so it can animate exactly the accepted shots.
     *
     * <p>Milestone 10Q introduced this because server-owned sessions (automatic and burst fire) schedule shots
     * themselves: their results cannot echo a client request sequence that does not exist. One id space for every
     * accepted shot - however it was triggered - keeps the client's duplicate suppression a single, simple
     * "is this newer?" test.</p>
     */
    private static final Map<Key, Integer> lastShotIds = new ConcurrentHashMap<>();

    private WeaponFireRuntime() {
    }

    /**
     * @param playerId the player's UUID
     * @param hand     the hand the weapon is held in
     */
    public record Key(UUID playerId, InteractionHand hand) {
    }

    private record Entry(String itemId, long nextAllowedTick, int lastSequence) {
    }

    /**
     * @param rpm the weapon's configured rounds per minute
     * @return the cooldown in whole server ticks, at least one and at most {@value #MAX_COOLDOWN_TICKS}
     */
    public static int cooldownTicks(int rpm) {
        if (rpm <= 0) {
            return MIN_COOLDOWN_TICKS;
        }
        int ticks = (int) Math.ceil((double) TICKS_PER_MINUTE / rpm);
        return Math.max(MIN_COOLDOWN_TICKS, Math.min(MAX_COOLDOWN_TICKS, ticks));
    }

    /**
     * Asks whether one shot may be accepted now, and reserves it if so.
     *
     * <p>Accepting a request records the next allowed tick and the sequence number. Nothing is reserved when
     * the request is rejected, so a rejected request neither consumes ammunition nor extends the cooldown.</p>
     *
     * @param playerId the player's UUID
     * @param hand     the hand holding the weapon
     * @param itemId   the registered identifier of the held weapon
     * @param rpm      the weapon's configured cadence
     * @param sequence the request's sequence number, which must increase for each accepted request
     * @param gameTime the current server game time in ticks
     * @return {@code true} when the shot may proceed
     */
    public static boolean tryAccept(UUID playerId, InteractionHand hand, String itemId, int rpm, int sequence,
                                    long gameTime) {
        if (playerId == null || hand == null || itemId == null) {
            return false;
        }
        Key key = new Key(playerId, hand);
        Entry previous = entries.get(key);
        if (previous != null && previous.itemId().equals(itemId)) {
            if (sequence <= previous.lastSequence()) {
                // A replay, a duplicate delivery, or a client that restarted its counter without reconnecting.
                return false;
            }
            if (gameTime < previous.nextAllowedTick()) {
                return false;
            }
        }
        entries.put(key, new Entry(itemId, gameTime + cooldownTicks(rpm), sequence));
        return true;
    }

    /**
     * Reserves one shot for a server-owned firing session.
     *
     * <p>Identical to {@link #tryAccept} except that it carries no client sequence: the shot was not requested by a
     * packet, so there is nothing to replay-protect. The cadence is the same gate and the same entry, so a session
     * and a client request for the same hand can never exceed the weapon's rate together.</p>
     *
     * @param playerId the player's UUID
     * @param hand     the hand holding the weapon
     * @param itemId   the registered identifier of the held weapon
     * @param rpm      the weapon's configured cadence
     * @param gameTime the current server game time in ticks
     * @return {@code true} when the shot may proceed
     */
    public static boolean tryAcceptScheduled(UUID playerId, InteractionHand hand, String itemId, int rpm,
                                             long gameTime) {
        if (playerId == null || hand == null || itemId == null) {
            return false;
        }
        Key key = new Key(playerId, hand);
        Entry previous = entries.get(key);
        if (previous != null && previous.itemId().equals(itemId) && gameTime < previous.nextAllowedTick()) {
            return false;
        }
        int sequence = previous != null && previous.itemId().equals(itemId) ? previous.lastSequence() : 0;
        entries.put(key, new Entry(itemId, gameTime + cooldownTicks(rpm), sequence));
        return true;
    }

    /**
     * Reserves the next shot id for one accepted shot.
     *
     * @param playerId the player's UUID
     * @param hand     the hand the shot came from
     * @return a strictly increasing, positive id
     */
    public static int nextShotId(UUID playerId, InteractionHand hand) {
        if (playerId == null || hand == null) {
            return 1;
        }
        Key key = new Key(playerId, hand);
        return lastShotIds.merge(key, 1, (previous, increment) -> previous >= Integer.MAX_VALUE
                ? 1 : previous + increment);
    }

    /**
     * Forgets one player's entries; called when the player disconnects.
     *
     * @param playerId the player's UUID, may be {@code null}
     */
    public static void clear(UUID playerId) {
        if (playerId != null) {
            entries.keySet().removeIf(key -> key.playerId().equals(playerId));
            lastShotIds.keySet().removeIf(key -> key.playerId().equals(playerId));
        }
    }

    /** Forgets every entry; called when the server starts and stops. */
    public static void clearAll() {
        entries.clear();
        lastShotIds.clear();
    }

    /** @return the number of tracked player/hand pairs, for diagnostics */
    public static int size() {
        return entries.size();
    }
}
