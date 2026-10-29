package com.arg_studios.arg_weaponry.weapon.inspect;

import net.minecraft.world.InteractionHand;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server-side registry of running inspections.
 *
 * <p>One entry per player and hand, keyed by the player's UUID, never persisted, and cleared when a player disconnects or
 * the server stops - the same lifecycle the firing and reloading runtimes use. Nothing here references a stack or a
 * definition, so a stale entry can never keep a weapon alive.</p>
 */
public final class WeaponInspectRuntime {

    /** How a start attempt ends. */
    public enum StartResult {

        /** The session was registered. */
        STARTED,

        /** The hand is already inspecting; a second copy is refused. */
        ALREADY_INSPECTING,

        /** The request's sequence number is not newer than the one already accepted for that hand. */
        STALE_REQUEST
    }

    private static final Map<InspectSession.Key, InspectSession> sessions = new ConcurrentHashMap<>();
    private static final Map<InspectSession.Key, Integer> lastSequences = new ConcurrentHashMap<>();

    private WeaponInspectRuntime() {
    }

    /**
     * Registers a session.
     *
     * @param session the session to start
     * @return how the start ended
     */
    public static StartResult start(InspectSession session) {
        InspectSession.Key key = session.key();
        if (sessions.containsKey(key)) {
            return StartResult.ALREADY_INSPECTING;
        }
        int previous = lastSequences.getOrDefault(key, 0);
        if (session.sequence() <= previous) {
            return StartResult.STALE_REQUEST;
        }
        lastSequences.put(key, session.sequence());
        sessions.put(key, session);
        return StartResult.STARTED;
    }

    /**
     * @param playerId the player, may be {@code null}
     * @param hand     the hand, may be {@code null}
     * @return the running session, or {@code null}
     */
    public static InspectSession get(UUID playerId, InteractionHand hand) {
        if (playerId == null || hand == null) {
            return null;
        }
        return sessions.get(new InspectSession.Key(playerId, hand));
    }

    /**
     * @param playerId the player, may be {@code null}
     * @param hand     the hand, may be {@code null}
     * @return whether that hand is inspecting
     */
    public static boolean isInspecting(UUID playerId, InteractionHand hand) {
        return get(playerId, hand) != null;
    }

    /**
     * Removes one session.
     *
     * @param playerId the player, may be {@code null}
     * @param hand     the hand, may be {@code null}
     * @return the removed session, or {@code null} when none was running
     */
    public static InspectSession remove(UUID playerId, InteractionHand hand) {
        if (playerId == null || hand == null) {
            return null;
        }
        return sessions.remove(new InspectSession.Key(playerId, hand));
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

    /** @return a snapshot of the running sessions; used by the server tick and by tests */
    public static Map<InspectSession.Key, InspectSession> snapshot() {
        return Map.copyOf(sessions);
    }

    /** Forgets one player's sessions and sequence record; called when the player disconnects. */
    public static void clear(UUID playerId) {
        if (playerId != null) {
            cancelAll(playerId);
            lastSequences.keySet().removeIf(key -> key.playerId().equals(playerId));
        }
    }

    /** Forgets every session; called when the server starts and stops. */
    public static void clearAll() {
        sessions.clear();
        lastSequences.clear();
    }

    /** @return the number of running sessions, for diagnostics */
    public static int size() {
        return sessions.size();
    }
}
