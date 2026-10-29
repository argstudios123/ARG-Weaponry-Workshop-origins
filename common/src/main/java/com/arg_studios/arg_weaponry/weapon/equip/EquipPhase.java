package com.arg_studios.arg_weaponry.weapon.equip;

/**
 * One immutable snapshot of a player's equip state: which state, how far the window has progressed, which stack it was
 * started for, and which slot a delayed switch is waiting for.
 *
 * <p>The class is deliberately free of Minecraft types and of side effects, so every transition decision - starting a draw,
 * starting a holster, re-targeting a pending slot, completing a window, giving up on a safety timeout, and deciding whether
 * an observed slot/stack change is expected or a hard interrupt - is a pure function that the unit tests drive directly. The
 * two controllers only add the Minecraft-facing effects: {@link ClientEquipController} plays the clip and applies the slot,
 * {@link ServerEquipAuthority} broadcasts the sound and answers the action gate.</p>
 *
 * <h2>Why one phase per player is enough</h2>
 * <p>A newer transition always replaces the previous one for that player, and every completion is checked against the
 * identity this value carries - the weapon's item id, its slot and the clip name - before anything is ended or applied. A
 * stale completion therefore cannot reach a weapon that is no longer part of the transition, which is the property the
 * reference implementation tracks with a numeric generation counter.</p>
 *
 * <h2>Expected versus unexpected changes</h2>
 * <p>{@link #classify(int, String)} is the whole interrupt rule:</p>
 * <ul>
 *   <li>a slot change arrives that is exactly the {@link #pendingSlot()} a holster is waiting for - that is the normal
 *   completion of the delayed switch (whether the client applied it itself or the server observed the packet), not an
 *   interrupt;</li>
 *   <li>the tracked slot and item are still held - nothing to do;</li>
 *   <li>anything else - another slot, another item, an empty hand, a forced change - is a hard interrupt.</li>
 * </ul>
 * <p>The slot is part of that identity on purpose. The first-person view controller is bound per slot
 * ({@code WeaponViewController.Owner}), so moving the held weapon to another hotbar slot resets its playback; a transition
 * that kept running would block the weapon while nothing is displayed. Restarting the draw for the new slot is therefore the
 * correct answer, and the one the animation architecture requires.</p>
 *
 * @param state         the current state
 * @param elapsedTicks  ticks the window has been running; {@code 0} when idle
 * @param durationTicks the authored window length in ticks; {@code 0} when idle
 * @param clip          the clip being played, or {@code null}
 * @param slot          the main-hand slot this phase was started for, or {@link #NO_SLOT}
 * @param itemId        the registered id of the tracked item, or {@code null}
 * @param pendingSlot   the slot a delayed switch is waiting for, or {@link #NO_SLOT}
 */
public record EquipPhase(EquipState state, int elapsedTicks, int durationTicks, String clip, int slot, String itemId,
                         int pendingSlot) {

    /** The value of {@link #slot()} and {@link #pendingSlot()} when no slot is tracked. */
    public static final int NO_SLOT = -1;

    /** The idle phase: nothing tracked, nothing blocked, nothing pending. */
    public static final EquipPhase IDLE =
            new EquipPhase(EquipState.IDLE, 0, 0, null, NO_SLOT, null, NO_SLOT);

    public EquipPhase {
        state = state == null ? EquipState.IDLE : state;
        if (state == EquipState.IDLE) {
            elapsedTicks = 0;
            durationTicks = 0;
            clip = null;
            slot = NO_SLOT;
            itemId = null;
            pendingSlot = NO_SLOT;
        } else {
            elapsedTicks = Math.max(0, elapsedTicks);
            durationTicks = Math.max(EquipTiming.MIN_DURATION_TICKS, durationTicks);
            clip = clip == null || clip.isBlank() ? null : clip;
            slot = Math.max(NO_SLOT, slot);
            itemId = itemId == null || itemId.isBlank() ? null : itemId;
            pendingSlot = Math.max(NO_SLOT, pendingSlot);
        }
    }

    // ---------------------------------------------------------------- factories

    /**
     * @param slot          the main-hand slot that now holds the weapon
     * @param itemId        the registered id of that weapon
     * @param clip          the draw clip, or {@code null} when the weapon has none
     * @param durationTicks the clip's window, in ticks; {@code 0} means "no window at all"
     * @return the drawing phase, or {@link #IDLE} when there is no clip to play
     */
    public static EquipPhase drawing(int slot, String itemId, String clip, int durationTicks) {
        if (clip == null || clip.isBlank() || durationTicks <= 0) {
            return IDLE;
        }
        return new EquipPhase(EquipState.DRAWING, 0, durationTicks, clip, slot, itemId, NO_SLOT);
    }

    /**
     * @param slot          the main-hand slot that still holds the weapon being holstered
     * @param itemId        the registered id of that weapon
     * @param clip          the holster clip
     * @param durationTicks the clip's window, in ticks
     * @param pendingSlot   the slot the switch is waiting for
     * @return the holstering phase, or {@link #IDLE} when there is no clip to play
     */
    public static EquipPhase holstering(int slot, String itemId, String clip, int durationTicks, int pendingSlot) {
        if (clip == null || clip.isBlank() || durationTicks <= 0) {
            return IDLE;
        }
        return new EquipPhase(EquipState.HOLSTERING, 0, durationTicks, clip, slot, itemId, pendingSlot);
    }

    // ---------------------------------------------------------------- transitions

    /**
     * Advances the running window by one tick.
     *
     * <p>The clock keeps running past the end of the window, up to the safety deadline, so the manager can still tell "the
     * window is over, complete it" from "something went wrong, force it" (see {@link #isWindowElapsed()} and
     * {@link #isTimedOut()}). It never passes the deadline, so a forgotten phase cannot grow without bound.</p>
     *
     * @return the next phase
     */
    public EquipPhase advance() {
        if (!state.isBusy() || elapsedTicks >= EquipTiming.deadlineTicks(durationTicks)) {
            return this;
        }
        return new EquipPhase(state, elapsedTicks + 1, durationTicks, clip, slot, itemId, pendingSlot);
    }

    /**
     * Re-targets the delayed switch. The latest target wins, which is what makes a scroll spam walk through the hotbar
     * instead of queueing every intermediate slot.
     *
     * @param targetSlot the new target, or {@link #NO_SLOT} to give up on the switch
     * @return the updated phase
     */
    public EquipPhase withPendingSlot(int targetSlot) {
        if (!state.isBusy()) {
            return this;
        }
        return new EquipPhase(state, elapsedTicks, durationTicks, clip, slot, itemId, targetSlot);
    }

    /** @return the idle phase, forgetting everything this phase tracked. */
    public EquipPhase cleared() {
        return IDLE;
    }

    // ---------------------------------------------------------------- queries

    /** @return whether a window is running, i.e. whether weapon actions are blocked. */
    public boolean isBusy() {
        return state.isBusy();
    }

    /** @return whether the authored window has elapsed and the manager must complete the phase. */
    public boolean isWindowElapsed() {
        return state.isBusy() && elapsedTicks >= durationTicks;
    }

    /**
     * @return whether the phase has outlived the safety deadline (the window plus {@link EquipTiming#SAFETY_MARGIN_TICKS}),
     *         i.e. whether the manager must force its outcome instead of waiting
     */
    public boolean isTimedOut() {
        return state.isBusy() && elapsedTicks >= EquipTiming.deadlineTicks(durationTicks);
    }

    /** @return how far through the window the phase is, in {@code [0, 1]}; {@code 0} when idle. */
    public double progress() {
        if (!state.isBusy() || durationTicks <= 0) {
            return 0.0;
        }
        return Math.min(1.0, elapsedTicks / (double) durationTicks);
    }

    /** @return whether a slot change is being waited for. */
    public boolean hasPendingSlot() {
        return pendingSlot != NO_SLOT;
    }

    /**
     * @param observedSlot   the main-hand slot currently selected
     * @param observedItemId the registered id of the item held there, or {@code null} for an empty hand
     * @return whether the observation matches, completes the pending switch, or is a hard interrupt
     */
    public Observation classify(int observedSlot, String observedItemId) {
        if (!state.isBusy()) {
            return Observation.MATCH;
        }
        if (observedSlot == pendingSlot && state == EquipState.HOLSTERING) {
            return Observation.EXPECTED_COMPLETION;
        }
        if (observedSlot == slot && sameItem(observedItemId, itemId)) {
            return Observation.MATCH;
        }
        return Observation.HARD_INTERRUPT;
    }

    private static boolean sameItem(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    /** What an observed (slot, item) pair means for a running phase. */
    public enum Observation {

        /** Nothing changed: the phase keeps running. */
        MATCH,

        /**
         * The slot that was being switched to is now the held one. This is the delayed switch arriving through the normal
         * path - including the client's own {@code setSelectedSlot} - and it completes the phase instead of interrupting
         * it.
         */
        EXPECTED_COMPLETION,

        /**
         * Anything else: another slot, another item, an empty hand, a forced change, a drop, an inventory move. The phase
         * must be dropped without playing the rest of the holster, and a draw must be started for whatever is held now.
         */
        HARD_INTERRUPT
    }
}
