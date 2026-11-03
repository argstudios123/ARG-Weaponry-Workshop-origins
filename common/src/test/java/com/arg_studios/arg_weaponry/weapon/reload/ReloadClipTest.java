package com.arg_studios.arg_weaponry.weapon.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The client-side stage schedule: which clip is due at a given moment. */
class ReloadClipTest {

    private static final List<ReloadClip> CLIPS = List.of(
            new ReloadClip("anim.model.reload_start", 0, 8),
            new ReloadClip("anim.model.load_first", 8, 27),
            new ReloadClip("anim.model.reload_loop", 35, 15));

    @Test
    void noClipIsDueBeforeTheFirstOneStarts() {
        assertEquals(0, ReloadClip.dueIndex(CLIPS, 0));
        assertEquals(0, ReloadClip.dueIndex(CLIPS, 399));
    }

    @Test
    void aClipBecomesDueAtItsOwnStart() {
        assertEquals(1, ReloadClip.dueIndex(CLIPS, 400), "eight ticks are four hundred milliseconds");
        assertEquals(2, ReloadClip.dueIndex(CLIPS, 1750));
        assertEquals(2, ReloadClip.dueIndex(CLIPS, 99_999), "the last started clip stays the answer");
    }

    @Test
    void anEmptyOrUnplayableListHasNothingDue() {
        assertEquals(-1, ReloadClip.dueIndex(List.of(), 5_000));
        assertEquals(-1, ReloadClip.dueIndex(null, 5_000));
        assertEquals(-1, ReloadClip.dueIndex(List.of(new ReloadClip("", 0, 10)), 5_000));
    }

    @Test
    void theClipConvertsItsTicksToTheClientClock() {
        ReloadClip clip = new ReloadClip("anim.model.reload_loop", 35, 15);

        assertEquals(1_750L, clip.startMillis());
        assertEquals(2_500L, clip.endMillis());
        assertEquals(0.75, clip.durationSeconds(), 1.0E-9);
        assertTrue(clip.playable());
    }
}
