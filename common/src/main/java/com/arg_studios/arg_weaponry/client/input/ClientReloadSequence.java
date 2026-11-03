package com.arg_studios.arg_weaponry.client.input;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.animation.WeaponViewControllers;
import com.arg_studios.arg_weaponry.weapon.reload.ReloadClip;
import net.minecraft.world.InteractionHand;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Plays the staged clips of an accepted reload on the client, in the order and at the times the server planned.
 *
 * <p>A staged reload is several clips - typically an opening clip, one clip per inserted shell and a closing clip - and
 * the server runs them back to back on its own clock. This class is the client half: it keeps the clip list the server
 * sent and starts each clip when its scheduled moment arrives.</p>
 *
 * <h2>What it may and may not decide</h2>
 * <p>Nothing is decided here. The clip names, their order and their timing all come from the payload; this class only
 * compares the elapsed wall time with each clip's scheduled start. It never inserts ammunition, never changes a count and
 * never invents a clip: the magazine only changes when the server's {@code weapon_state} component arrives.</p>
 *
 * <h2>Late or skipped ticks</h2>
 * <p>The schedule is derived from the elapsed time, not from a chain of timers, so a tick that arrives late - or not at
 * all - simply plays every clip that should already have started, in order, and carries on. A missing clip is logged and
 * skipped rather than ending the sequence, so one unresolved animation name cannot silence the rest of a reload.</p>
 *
 * <h2>Threading</h2>
 * <p>All methods run on the client thread: the reload outcome arrives through the networking layer (which hops to the
 * client thread) and {@link #tick()} is called from {@code WeaponClientTick}.</p>
 */
public final class ClientReloadSequence {

    private static final Map<InteractionHand, Active> ACTIVE = new EnumMap<>(InteractionHand.class);

    private ClientReloadSequence() {
    }

    /** One running sequence: the clips, the clock and how far playback has progressed. */
    private static final class Active {

        private final List<ReloadClip> clips;
        private final long startNanos;
        private int next;

        private Active(List<ReloadClip> clips) {
            this.clips = clips;
            this.startNanos = System.nanoTime();
        }

        private long elapsedMillis() {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }
    }

    /**
     * Starts a sequence for one hand and immediately plays every clip that is already due.
     *
     * @param hand  the hand the reload belongs to
     * @param clips the staged clips from the server, in play order
     */
    public static void start(InteractionHand hand, List<ReloadClip> clips) {
        if (hand == null) {
            return;
        }
        if (clips == null || clips.isEmpty()) {
            ACTIVE.remove(hand);
            return;
        }
        ACTIVE.put(hand, new Active(List.copyOf(clips)));
        advance(hand);
    }

    /** Advances every running sequence by one client tick. */
    public static void tick() {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (InteractionHand hand : List.copyOf(ACTIVE.keySet())) {
            advance(hand);
        }
    }

    /**
     * Stops the sequence of one hand; called when the server reports the reload ended.
     *
     * @param hand the hand whose sequence ends, may be {@code null}
     */
    public static void cancel(InteractionHand hand) {
        if (hand != null) {
            ACTIVE.remove(hand);
        }
    }

    /** Stops every sequence; called when the client leaves a world. */
    public static void reset() {
        ACTIVE.clear();
    }

    /**
     * @param hand the hand to test, may be {@code null}
     * @return whether a staged sequence is still running for that hand
     */
    public static boolean running(InteractionHand hand) {
        return hand != null && ACTIVE.containsKey(hand);
    }

    /** @return the number of clips already handed to the view controller for the given hand. */
    public static int playedClips(InteractionHand hand) {
        Active active = hand == null ? null : ACTIVE.get(hand);
        return active == null ? 0 : active.next;
    }

    private static void advance(InteractionHand hand) {
        Active active = ACTIVE.get(hand);
        if (active == null) {
            return;
        }
        int due = ReloadClip.dueIndex(active.clips, active.elapsedMillis());
        while (active.next <= due && active.next < active.clips.size()) {
            ReloadClip clip = active.clips.get(active.next);
            active.next++;
            if (!clip.playable()) {
                continue;
            }
            if (!WeaponViewControllers.instance().playReloadAnimation(hand, clip.animation(),
                    clip.durationSeconds(), clip.transitionSeconds())) {
                Constants.LOG.debug("[ARG] The staged reload clip '{}' could not be started; the sequence continues",
                        clip.animation());
            }
        }
        if (active.next >= active.clips.size()) {
            ACTIVE.remove(hand);
        }
    }
}
