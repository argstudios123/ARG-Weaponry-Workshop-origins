package com.arg_studios.arg_weaponry.weapon.reload;

import java.util.List;

/**
 * One animated clip of a reload, positioned on the reload's own clock.
 *
 * <p>Pure on purpose: the same record is produced by {@link ReloadTimeline}, carried to the client in the reload
 * outcome payload and scheduled there, so the server and the client can never disagree about which clip plays when. The
 * animation <em>name</em> is all that crosses the wire - the client resolves it against the same content pack it renders
 * from.</p>
 *
 * <p>{@code transitionMillis} is the cross-fade the client uses when this clip starts, taken from the entry's optional
 * {@code transition_duration}. It is a rendering detail: it does not move {@code startTick}, does not shorten
 * {@code durationTicks} and cannot delay an insertion, because the client blends on its own frame clock while the
 * timeline keeps its authoritative ticks.</p>
 *
 * @param animation     the declared clip name
 * @param startTick     when the clip starts, in ticks relative to the reload start
 * @param durationTicks how long the clip lasts, in ticks
 * @param transitionMillis how long the cross-fade into this clip lasts, in milliseconds; {@code 0} disables blending
 */
public record ReloadClip(String animation, int startTick, int durationTicks, int transitionMillis) {

    public ReloadClip {
        animation = animation == null ? "" : animation;
        startTick = Math.max(0, startTick);
        durationTicks = Math.max(0, durationTicks);
        transitionMillis = Math.max(0, transitionMillis);
    }

    /**
     * Builds a clip that uses the documented default cross-fade.
     *
     * @param animation     the declared clip name
     * @param startTick     when the clip starts, in ticks relative to the reload start
     * @param durationTicks how long the clip lasts, in ticks
     */
    public ReloadClip(String animation, int startTick, int durationTicks) {
        this(animation, startTick, durationTicks, ReloadRules.transitionMillis(null));
    }

    /** @return whether this entry names a clip to play. */
    public boolean playable() {
        return !animation.isBlank();
    }

    /** @return the clip's start on the reload clock, in milliseconds. */
    public long startMillis() {
        return startTick * 1000L / ReloadRules.TICKS_PER_SECOND;
    }

    /** @return the clip's end on the reload clock, in milliseconds. */
    public long endMillis() {
        return (startTick + durationTicks) * 1000L / ReloadRules.TICKS_PER_SECOND;
    }

    /** @return the clip's length in seconds, for the view controller's retiming. */
    public double durationSeconds() {
        return durationTicks / (double) ReloadRules.TICKS_PER_SECOND;
    }

    /** @return the cross-fade into this clip, in seconds; {@code 0} disables blending. */
    public double transitionSeconds() {
        return transitionMillis / 1000.0;
    }

    /**
     * The index of the last clip that has started by the given time.
     *
     * <p>The whole client-side stage schedule is this one question, which is why it lives here instead of in a
     * scheduler held together by timers: a tick may be late or skipped entirely and the answer stays correct.</p>
     *
     * @param clips         the clips in play order
     * @param elapsedMillis milliseconds since the reload started
     * @return the highest index whose start has passed, or {@code -1} when none has
     */
    public static int dueIndex(List<ReloadClip> clips, long elapsedMillis) {
        if (clips == null || clips.isEmpty()) {
            return -1;
        }
        int due = -1;
        for (int index = 0; index < clips.size(); index++) {
            ReloadClip clip = clips.get(index);
            if (clip != null && clip.playable() && clip.startMillis() <= elapsedMillis) {
                due = index;
            }
        }
        return due;
    }
}
