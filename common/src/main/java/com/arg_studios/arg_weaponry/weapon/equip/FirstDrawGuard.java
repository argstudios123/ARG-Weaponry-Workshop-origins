package com.arg_studios.arg_weaponry.weapon.equip;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-side guard against replaying {@code draw_first} while the server's component update is still in flight.
 *
 * <h2>The race this closes</h2>
 * <p>The authoritative first-draw flag lives on the stack ({@link StackEquipState}) and is set by the server when the draw
 * <em>starts</em>. The client selects its clip at the same moment, so a player who holsters and re-draws within the same
 * server round trip could still read the old, undrawn value and hear the first-draw clip twice. This guard remembers, per
 * held slot and item, that the client already played a first draw in this session, so the second equip uses the ordinary
 * clip even before the synchronised flag arrives.</p>
 *
 * <h2>Scope and limits</h2>
 * <p>It is a bounded, in-memory client-side cache: {@value #MAX_ENTRIES} entries at most (oldest evicted), cleared when the
 * client leaves the world. It never writes anything, so it cannot desynchronise the server. Two different stacks of the
 * same item in the same slot cannot be told apart - the same documented limitation the view controllers have, which is
 * why the key is (slot, item id) and not a stack identity.</p>
 */
public final class FirstDrawGuard {

    /** Largest number of remembered slot/item pairs; enough for every hotbar slot of every weapon seen this session. */
    public static final int MAX_ENTRIES = 64;

    private static final Map<Key, Boolean> PLAYED = new LinkedHashMap<>();

    private FirstDrawGuard() {
    }

    /** One remembered slot/item pair. */
    public record Key(int slot, String itemId) {

        public Key {
            itemId = itemId == null ? "" : itemId;
        }
    }

    /**
     * @param slot   the main-hand slot the draw happened in
     * @param itemId the registered id of the drawn item
     */
    public static void markPlayed(int slot, String itemId) {
        Key key = new Key(slot, itemId);
        synchronized (PLAYED) {
            PLAYED.put(key, Boolean.TRUE);
            while (PLAYED.size() > MAX_ENTRIES) {
                Iterator<Key> oldest = PLAYED.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    /**
     * @param slot   the main-hand slot to test
     * @param itemId the registered id of the item held there
     * @return whether this session already played a first draw for that slot and item
     */
    public static boolean hasPlayed(int slot, String itemId) {
        synchronized (PLAYED) {
            return PLAYED.containsKey(new Key(slot, itemId));
        }
    }

    /**
     * Forgets one slot, used when a different item occupies it (so that item's own first draw is not suppressed).
     *
     * @param slot the slot to forget
     */
    public static void forgetSlot(int slot) {
        synchronized (PLAYED) {
            PLAYED.keySet().removeIf(key -> key.slot() == slot);
        }
    }

    /** Forgets every remembered draw; called when the client leaves the world. */
    public static void reset() {
        synchronized (PLAYED) {
            PLAYED.clear();
        }
    }

    /** @return how many entries are remembered, for diagnostics and tests. */
    public static int size() {
        synchronized (PLAYED) {
            return PLAYED.size();
        }
    }
}
