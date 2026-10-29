package com.arg_studios.arg_weaponry.weapon.reload;

import net.minecraft.world.InteractionHand;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server-side registry of active reload sessions.
 *
 * <p>One entry per player and hand, exactly like {@code WeaponFireRuntime}: the map is keyed by the player's
 * UUID and the hand, it holds only sessions, it is emptied when a player disconnects or the server stops, and
 * nothing in it is persisted. It deliberately does not reference a {@code ServerPlayer}, a level or an
 * {@code ItemStack}, so an entry can never keep a stale object alive.</p>
 *
 * <h2>Duplicates and replays</h2>
 * <p>{@link #start(ReloadSession)} is the only way in and it refuses two things: a second session for the same
 * player and hand while one is active, and a request whose sequence number does not increase for that hand.
 * The sequence record survives a finished reload on purpose — that is what makes a duplicated request for an
 * already-finished reload a no-op instead of a second reload.</p>
 *
 * <h2>Threading</h2>
 * <p>Every operation is safe to call from any thread, but the gameplay work around it (inventory, components,
 * payloads) belongs on the server thread, which is where the service calls it from.</p>
 */
public final class WeaponReloadRuntime {

    private static final Map<Key, ReloadSession> sessions = new ConcurrentHashMap<>();
    private static final Map<Key, Integer> lastSequences = new ConcurrentHashMap<>();

    private WeaponReloadRuntime() {
    }

    /**
     * @param playerId the player's UUID
     * @param hand     the hand holding the weapon
     */
    public record Key(UUID playerId, InteractionHand hand) {
    }

    /** What {@link #start(ReloadSession)} decided. */
    public enum StartResult {

        /** The session is now active. */
        STARTED,

        /** A reload is already active for this player and hand. */
        ALREADY_RELOADING,

        /** The request's sequence number does not increase, so it is a replay or a duplicate. */
        STALE_REQUEST
    }

    /**
     * Registers a new session if it is neither a duplicate nor a replay.
     *
     * @param session the session to start
     * @return what happened
     */
    public static StartResult start(ReloadSession session) {
        if (session == null) {
            throw new IllegalArgumentException("A reload session is required");
        }
        Key key = new Key(session.playerId(), session.hand());
        synchronized (sessions) {
            if (sessions.containsKey(key)) {
                return StartResult.ALREADY_RELOADING;
            }
            Integer previous = lastSequences.get(key);
            if (previous != null && session.sequence() <= previous) {
                return StartResult.STALE_REQUEST;
            }
            sessions.put(key, session);
            lastSequences.put(key, session.sequence());
            return StartResult.STARTED;
        }
    }

    /**
     * @param playerId the player's UUID, may be {@code null}
     * @param hand     the hand, may be {@code null}
     * @return the active session for that player and hand, or {@code null}
     */
    public static ReloadSession session(UUID playerId, InteractionHand hand) {
        return playerId == null || hand == null ? null : sessions.get(new Key(playerId, hand));
    }

    /**
     * @param playerId the player's UUID, may be {@code null}
     * @param hand     the hand, may be {@code null}
     * @return whether a reload is active for that player and hand
     */
    public static boolean isReloading(UUID playerId, InteractionHand hand) {
        return session(playerId, hand) != null;
    }

    /**
     * Ends one session, whether it completed or was cancelled.
     *
     * @param playerId the player's UUID, may be {@code null}
     * @param hand     the hand, may be {@code null}
     * @return the session that was removed, or empty when there was none
     */
    public static Optional<ReloadSession> remove(UUID playerId, InteractionHand hand) {
        if (playerId == null || hand == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.remove(new Key(playerId, hand)));
    }

    /** @return a snapshot of the active sessions, for the server tick pass. */
    public static List<ReloadSession> sessions() {
        return List.copyOf(sessions.values());
    }

    /**
     * Forgets every session and sequence record of one player; called when the player disconnects.
     *
     * @param playerId the player's UUID, may be {@code null}
     */
    public static void clear(UUID playerId) {
        if (playerId == null) {
            return;
        }
        sessions.keySet().removeIf(key -> key.playerId().equals(playerId));
        lastSequences.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    /** Forgets every session and sequence record; called when the server starts and stops. */
    public static void clearAll() {
        sessions.clear();
        lastSequences.clear();
    }

    /** @return the number of active sessions, for diagnostics. */
    public static int size() {
        return sessions.size();
    }
}
