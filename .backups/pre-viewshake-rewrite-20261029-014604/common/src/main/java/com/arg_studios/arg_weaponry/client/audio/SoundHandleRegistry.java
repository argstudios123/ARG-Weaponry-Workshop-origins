package com.arg_studios.arg_weaponry.client.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ownership of live sound instances, keyed by "which action of which hand".
 *
 * <h2>Why a registry and not a sound identifier</h2>
 * <p>A sound identifier is shared: two hands, two weapons and two players can play the same event at the same time, and
 * stopping by identifier would silence all of them. This registry keeps the actual instance each action started, so an
 * interrupted inspection stops its own remaining audio and nothing else.</p>
 *
 * <p>The class is deliberately free of Minecraft's client types - it stores whatever {@code T} is - so the ownership rules
 * can be tested without an audio device, and {@link ActionSoundHandles} is then the thin layer that knows how to stop a
 * {@code SoundInstance}.</p>
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Tracking a second instance for the same key returns the previous one, so the caller can stop it: two overlapping
 *   copies of one action can never both be tracked and leak.</li>
 *   <li>Tracking {@code null} forgets the key, because a weapon that declares no sound for the action must not keep a
 *   stale handle from the previous weapon.</li>
 *   <li>Stopping removes and returns the instance; the caller decides whether that instance should be silenced.</li>
 *   <li>Stopping one key never touches another key, another hand or another player's entry.</li>
 * </ul>
 *
 * @param <T> the playback handle type
 */
public final class SoundHandleRegistry<T> {

    private final Map<String, T> handles = new ConcurrentHashMap<>();

    /**
     * Records one handle.
     *
     * @param key    the identity of the action, for example {@code INSPECT:MAIN_HAND}
     * @param handle the instance that action started, or {@code null} to forget the key
     * @return the instance this call replaced, or {@code null}
     */
    public T track(String key, T handle) {
        if (key == null) {
            return null;
        }
        if (handle == null) {
            return handles.remove(key);
        }
        T previous = handles.put(key, handle);
        return previous == handle ? null : previous;
    }

    /**
     * Removes one handle without deciding what to do with it.
     *
     * @param key the identity of the action
     * @return the instance that was tracked, or {@code null} when there was none
     */
    public T remove(String key) {
        return key == null ? null : handles.remove(key);
    }

    /**
     * Removes every handle whose key starts with the given prefix.
     *
     * @param prefix the prefix, for example {@code MAIN_HAND} to end every action of one hand
     * @return the removed instances, in no particular order
     */
    public List<T> removeAllWithPrefix(String prefix) {
        List<T> removed = new ArrayList<>();
        if (prefix == null) {
            return removed;
        }
        for (String key : new ArrayList<>(handles.keySet())) {
            if (key.startsWith(prefix)) {
                T handle = handles.remove(key);
                if (handle != null) {
                    removed.add(handle);
                }
            }
        }
        return removed;
    }

    /**
     * @param key the identity of the action
     * @return the tracked instance, or {@code null}
     */
    public T get(String key) {
        return key == null ? null : handles.get(key);
    }

    /** @return every tracked instance. */
    public List<T> all() {
        return List.copyOf(handles.values());
    }

    /** @return the number of tracked handles. */
    public int size() {
        return handles.size();
    }

    /** @return whether nothing is tracked. */
    public boolean isEmpty() {
        return handles.isEmpty();
    }

    /** Removes every handle and returns them, so the caller can silence them. */
    public List<T> clear() {
        List<T> removed = new ArrayList<>(handles.values());
        handles.clear();
        return removed;
    }
}
