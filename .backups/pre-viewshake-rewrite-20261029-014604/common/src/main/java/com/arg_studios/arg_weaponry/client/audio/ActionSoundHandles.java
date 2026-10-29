package com.arg_studios.arg_weaponry.client.audio;

import com.arg_studios.arg_weaponry.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.InteractionHand;

import java.util.List;

/**
 * The playback instances of the weapon actions that can be interrupted.
 *
 * <p>Only the two long, interruptible actions are tracked: an inspection and a reload. One-shots (a shot, a hit, a draw)
 * are left to ring out - they are shorter than the action that produced them, so cancelling them would only ever cut a
 * sound nobody asked to cut.</p>
 *
 * <p>A handle is dropped when its action ends, whatever ended it: the regular completion, an interrupting shot, a reload
 * that replaces an inspection, a weapon change, death or a disconnect. A handle is never replayed or recreated by a render
 * pass, so a stopped sound cannot come back on the next frame.</p>
 *
 * <p>The ownership rules live in {@link SoundHandleRegistry}, which has no Minecraft dependency and is tested on its own;
 * this class only adds "stop exactly this instance" through the sound manager.</p>
 */
public final class ActionSoundHandles {

    /** The interruptible actions whose audio is tracked. */
    public enum Action {

        /** A weapon inspection. */
        INSPECT,

        /** A reload, tracked so a cancelled reload stops its own sound. */
        RELOAD
    }

    private static final SoundHandleRegistry<SoundInstance> REGISTRY = new SoundHandleRegistry<>();

    private ActionSoundHandles() {
    }

    /**
     * Records the instance one action started.
     *
     * @param action   the action
     * @param hand     the hand it belongs to
     * @param instance the playback instance, may be {@code null} for a weapon that declares no such sound
     */
    public static void track(Action action, InteractionHand hand, SoundInstance instance) {
        if (action == null || hand == null) {
            return;
        }
        SoundInstance previous = REGISTRY.track(key(action, hand), instance);
        if (previous != null) {
            stopInstance(previous);
        }
    }

    /**
     * Stops the audio of one action on one hand, if it is still tracked.
     *
     * @param action the action that ended
     * @param hand   the hand it belonged to
     * @return {@code true} when a tracked sound was stopped
     */
    public static boolean stop(Action action, InteractionHand hand) {
        if (action == null || hand == null) {
            return false;
        }
        SoundInstance instance = REGISTRY.remove(key(action, hand));
        if (instance == null) {
            return false;
        }
        stopInstance(instance);
        return true;
    }

    /**
     * Stops every tracked action of one hand.
     *
     * @param hand   the hand whose actions ended
     * @param reason what ended them, for diagnostics
     */
    public static void stopAll(InteractionHand hand, String reason) {
        if (hand == null) {
            return;
        }
        for (SoundInstance instance : REGISTRY.removeAllWithPrefix(hand.name())) {
            stopInstance(instance);
            Constants.LOG.debug("[ARG] Stopped a tracked weapon sound of {} because {}", hand, reason);
        }
    }

    /** Stops every tracked action of every hand; used when leaving the world or on a resource reload. */
    public static void reset() {
        for (SoundInstance instance : REGISTRY.clear()) {
            stopInstance(instance);
        }
    }

    /** @return the number of tracked handles, for tests and diagnostics. */
    public static int size() {
        return REGISTRY.size();
    }

    /** @return a snapshot of the tracked handles, for tests. */
    public static List<SoundInstance> instances() {
        return REGISTRY.all();
    }

    /**
     * @param action the action
     * @param hand   the hand
     * @return the registry key of that action, exposed so tests and diagnostics can name it
     */
    public static String key(Action action, InteractionHand hand) {
        return hand.name() + ":" + action.name();
    }

    private static void stopInstance(SoundInstance instance) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.getSoundManager() != null) {
                client.getSoundManager().stop(instance);
            }
        } catch (RuntimeException e) {
            Constants.LOG.warn("[ARG] A tracked weapon sound could not be stopped ({}); it will simply finish",
                    e.getMessage());
        }
    }
}
