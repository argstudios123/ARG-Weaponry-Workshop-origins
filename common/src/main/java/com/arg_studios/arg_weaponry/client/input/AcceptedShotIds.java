package com.arg_studios.arg_weaponry.client.input;

import java.util.HashMap;
import java.util.Map;

/**
 * The "is this accepted shot newer than the one I handled last?" gate for one hand.
 *
 * <p>Accepted shots come back from the server with a monotonically increasing, server-assigned shot id. A shot the server
 * accepted exactly once can still be delivered more than once - a duplicated payload, a retry, or a second listener - and
 * everything downstream of this gate is a per-shot effect: the firing animation, the firing sound, the hit feedback and,
 * since this milestone, the camera-recoil impulse. Applying an effect twice for one shot is a visible bug ("the recoil
 * doubled for one round"), so the gate is a single place that decides once per shot.</p>
 *
 * <h2>Why it is a separate, generic class</h2>
 * <p>The rule is pure bookkeeping and has nothing to do with Minecraft, so it lives on its own and is exercised by a
 * headless test with plain keys: {@link com.arg_studios.arg_weaponry.client.input.WeaponFireInput} supplies
 * {@code InteractionHand} as the key, and the test supplies strings.</p>
 *
 * @param <K> the key the ids are tracked per, for example the hand that fired
 */
public final class AcceptedShotIds<K> {

    private final Map<K, Integer> lastAccepted = new HashMap<>();

    /**
     * @param key    the tracked key, for example the hand
     * @param shotId the server-assigned shot id
     * @return {@code true} when this id is newer than the last one accepted for {@code key} and must therefore be acted
     *         on, {@code false} when it is a duplicate or an out-of-order replay and must be ignored
     */
    public boolean accept(K key, int shotId) {
        int previous = lastAccepted(key);
        if (shotId <= previous) {
            return false;
        }
        lastAccepted.put(key, shotId);
        return true;
    }

    /**
     * @param key the tracked key
     * @return the last id accepted for it, or {@code 0} when none has been accepted yet
     */
    public int lastAccepted(K key) {
        Integer previous = lastAccepted.get(key);
        return previous == null ? 0 : previous;
    }

    /** Forgets every key; used when the client leaves the world. */
    public void clear() {
        lastAccepted.clear();
    }
}
