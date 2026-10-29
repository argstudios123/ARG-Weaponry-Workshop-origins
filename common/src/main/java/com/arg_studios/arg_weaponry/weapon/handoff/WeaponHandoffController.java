package com.arg_studios.arg_weaponry.weapon.handoff;

import com.arg_studios.arg_weaponry.Constants;

/**
 * The single owner of a hand's transition state.
 *
 * <p>This replaces the previous client equip controller. It owns <em>only</em> the handoff: which weapon is leaving, where
 * the player is going, which stage the transition is in, and when it must be over. It never touches a stack, a model, a
 * pose, a sound or a packet directly - those are behind {@link HandoffVisuals} and {@link HandoffEnvironment}.</p>
 *
 * <h2>Architecture</h2>
 * <p>The design follows the reference implementation's verified handoff handler, not its class names:</p>
 * <ul>
 *     <li>a request is <strong>deferred</strong>, not applied: the input is consumed, the destination is remembered, and the
 *     outgoing weapon stays the visual weapon;</li>
 *     <li>the outgoing weapon keeps <strong>its own animation state</strong> for the whole holster, because the visual weapon
 *     owns the animator instead of the hand;</li>
 *     <li>the slot is committed <strong>once</strong>, at the boundary, and only when this controller deferred it;</li>
 *     <li>the incoming weapon's draw starts at the boundary from its <strong>own fresh animator</strong>, so its first drawn
 *     frame is the clip's first frame;</li>
 *     <li>every stage has a wall-clock <strong>deadline</strong> taken from the authored clip length, so a missing completion
 *     can never wedge the hand;</li>
 *     <li>every accepted request, retarget, cancellation and reset bumps a <strong>generation</strong>, so a superseded
 *     session cannot commit anything.</li>
 * </ul>
 *
 * <h2>Stages</h2>
 * <p>{@code IDLE -> HOLSTERING -> HANDOFF -> DRAWING -> IDLE}. {@code HANDOFF} is entered and left inside
 * {@link #commit(HandoffSession)}, so no frame is rendered with two visual weapons or none.</p>
 *
 * <p>Not thread-safe by design: it is only touched from the client tick thread, and so are its two ports.</p>
 */
public final class WeaponHandoffController {

    /** Observability hook: the tests use it to prove stage order, boundary count and slot-commit count. */
    public interface Listener {

        /**
         * @param previous the session that was replaced, may be {@code null}
         * @param next     the session now active, or {@code null} when the transition ended
         */
        default void onSession(HandoffSession previous, HandoffSession next) {
        }

        /**
         * @param generation  the generation whose boundary this is
         * @param outgoing    the weapon that stopped being visual
         * @param incoming    the weapon that became visual
         * @param slot        the slot the transition switched to
         * @param slotApplied whether this boundary applied the deferred slot change
         */
        default void onBoundary(long generation, WeaponIdentity outgoing, WeaponIdentity incoming, int slot,
                                boolean slotApplied) {
        }
    }

    private final HandoffEnvironment env;
    private final HandoffVisuals visuals;

    private Listener listener = new Listener() {
    };

    private HandoffSession session;
    private int pendingSlot = -1;
    private long generation;

    /**
     * @param env     the player, inventory and notification port
     * @param visuals the visual weapon port
     */
    public WeaponHandoffController(HandoffEnvironment env, HandoffVisuals visuals) {
        if (env == null || visuals == null) {
            throw new IllegalArgumentException("A handoff controller needs an environment and a visuals port");
        }
        this.env = env;
        this.visuals = visuals;
    }

    /**
     * @param listener the listener to notify, may be {@code null} to detach
     */
    public void setListener(Listener listener) {
        this.listener = listener == null ? new Listener() {
        } : listener;
    }

    /** @return the stage of the running transition, or {@link HandoffStage#IDLE}. */
    public HandoffStage stage() {
        return session == null ? HandoffStage.IDLE : session.stage();
    }

    /** @return the running session, or {@code null} when no transition is running. */
    public HandoffSession session() {
        return session;
    }

    /** @return {@code true} while a transition is running. */
    public boolean isTransitioning() {
        return session != null;
    }

    /**
     * Handles one player-initiated switch.
     *
     * @param targetSlot the slot the player asked for
     * @return {@code true} when vanilla must not perform the switch, because this controller took responsibility for it
     */
    public boolean requestSwitch(int targetSlot) {
        if (!env.canTransition() || targetSlot < 0 || targetSlot >= env.hotbarSize()) {
            return false;
        }
        int selected = env.selectedSlot();

        if (session == null) {
            if (targetSlot == selected) {
                // Re-selecting the current slot is not a switch; vanilla would do nothing either.
                pendingSlot = -1;
                return false;
            }
            WeaponIdentity outgoing = visuals.activeIdentity();
            if (!outgoing.isPresent()) {
                // Nothing is adopted, so there is no visual weapon to keep on screen.
                return false;
            }
            WeaponIdentity destination = env.identityAt(targetSlot);
            if (destination.isPresent() && outgoing.itemId().equals(destination.itemId())) {
                // Point Blank's matchesGunIdentity: two slots holding the same item look identical, so there is nothing to
                // hide or draw - let vanilla switch instantly instead of playing a pointless holster/draw pair.
                return false;
            }
            if (env.actionOwnsPlayback()) {
                // A reload, a shot or an inspection owns the weapon: remember the destination and try again once that clip
                // has finished, instead of cutting it mid-animation.
                pendingSlot = targetSlot;
                return true;
            }
            return begin(targetSlot, true);
        }

        if (session.stage() == HandoffStage.HOLSTERING) {
            if (targetSlot == selected) {
                cancelHolster();
                return true;
            }
            if (session.targetSlot() != targetSlot) {
                reassign(session.retarget(targetSlot));
                env.notifySwitchStarted(targetSlot);
            }
            return true;
        }

        // DRAWING: the visual weapon is the one that was just drawn. Pressing its own slot changes nothing; anything else
        // starts a fresh session away from it, holstering the weapon that is currently being raised.
        if (targetSlot == selected) {
            return false;
        }
        return begin(targetSlot, true);
    }

    /**
     * Advances the machine by one client tick. Completion is polled, never pushed, so it cannot arrive twice or mutate a
     * newer session.
     */
    public void tick() {
        if (!env.canTransition()) {
            if (session != null) {
                // Death, spectator or leaving the world: the transition is over and the visual weapon must not outlive it.
                reset();
            }
            return;
        }

        if (session == null) {
            if (pendingSlot >= 0) {
                if (env.actionOwnsPlayback()) {
                    return;
                }
                int slot = pendingSlot;
                pendingSlot = -1;
                begin(slot, true);
                return;
            }
            repairExternalChange();
            return;
        }

        double now = env.nowSeconds();
        switch (session.stage()) {
            case HOLSTERING -> {
                boolean finished = visuals.holsterFinished();
                boolean expired = session.isExpired(now);
                if (Constants.LOG.isDebugEnabled() && (finished || expired)) {
                    Constants.LOG.debug("[ARG] Handoff HOLSTERING boundary: finished={} expired={}", finished, expired);
                }
                if (finished || expired) {
                    commit(session);
                }
            }
            case DRAWING -> {
                if (visuals.drawFinished() || session.isExpired(now)) {
                    visuals.settleDraw();
                    endSession();
                }
            }
            case HANDOFF, IDLE -> {
                // HANDOFF never persists across a call; IDLE is the absence of a session.
            }
        }
    }

    /**
     * Drops every session and the visual weapon. Called on death, respawn, disconnect, world changes, resource reloads and
     * when the feature is disabled.
     */
    public void reset() {
        generation++;
        pendingSlot = -1;
        HandoffSession previous = session;
        session = null;
        visuals.release();
        if (previous != null) {
            listener.onSession(previous, null);
        }
    }

    // ------------------------------------------------------------------ transitions

    /**
     * Starts a transition away from the current visual weapon.
     *
     * @param targetSlot the destination slot
     * @param deferred   whether this controller owns the slot change
     * @return {@code true} when the request was taken over
     */
    private boolean begin(int targetSlot, boolean deferred) {
        WeaponIdentity outgoing = visuals.activeIdentity();
        if (!outgoing.isPresent()) {
            return false;
        }
        long nextGeneration = ++generation;
        double clipSeconds = Math.max(0.0, visuals.playHolster());
        HandoffSession started = new HandoffSession(nextGeneration, outgoing, targetSlot, deferred,
                HandoffStage.HOLSTERING, clipSeconds,
                env.nowSeconds() + HandoffTiming.deadline(clipSeconds));
        reassign(started);
        if (clipSeconds > 0.0) {
            env.notifySwitchStarted(targetSlot);
            return true;
        }
        // No holster clip: delaying the switch for an animation that does not exist is the one thing that would look
        // broken, so the boundary happens immediately instead.
        commit(started);
        return true;
    }

    /**
     * The handoff boundary: apply the deferred slot change once, retire the outgoing weapon, adopt the incoming weapon and
     * start its draw. {@link HandoffStage#HANDOFF} exists only inside this method.
     *
     * @param running the holstering session to complete
     */
    private void commit(HandoffSession running) {
        Constants.LOG.debug("[ARG] Handoff commit: stage={} outgoing='{}' targetSlot={}",
                running.stage(), running.outgoing().itemId(), running.targetSlot());
        int targetSlot = running.targetSlot();
        WeaponIdentity incoming = env.identityAt(targetSlot);
        boolean slotApplied = false;
        if (running.deferred() && targetSlot != env.selectedSlot()) {
            env.selectSlot(targetSlot);
            slotApplied = true;
        }
        listener.onBoundary(running.generation(), running.outgoing(), incoming, targetSlot, slotApplied);

        reassign(running.enter(HandoffStage.HANDOFF, 0.0, env.nowSeconds()));
        visuals.adopt(incoming);
        if (!incoming.isPresent()) {
            // The destination holds no weapon: rendering falls back to the live hand and the transition is over.
            endSession();
            return;
        }
        double clipSeconds = visuals.playDraw();
        Constants.LOG.debug("[ARG] Handoff commit: playDraw returned {} for '{}'({})",
                clipSeconds, incoming.itemId(), incoming.slot());
        if (clipSeconds <= 0.0) {
            // No draw clip: the weapon appears already in its idle playback.
            endSession();
            return;
        }
        reassign(running.enter(HandoffStage.DRAWING, clipSeconds, env.nowSeconds()));
    }

    /** Abandons a running holster without changing the slot: the outgoing weapon returns to idle playback. */
    private void cancelHolster() {
        visuals.stopHolster();
        env.notifySwitchCancelled();
        endSession();
    }

    /**
     * Repairs a selection change that did not pass through {@link #requestSwitch} - a command, another mod, a hotbar slot
     * bound to a mouse button. The slot is already applied, so this only keeps the outgoing weapon visible for its holster
     * before adopting whatever is held now.
     */
    private void repairExternalChange() {
        WeaponIdentity visual = visuals.activeIdentity();
        WeaponIdentity selected = env.selectedIdentity();
        if (!visual.isPresent() || visual.sameAs(selected) || env.actionOwnsPlayback()) {
            return;
        }
        long nextGeneration = ++generation;
        double clipSeconds = Math.max(0.0, visuals.playHolster());
        HandoffSession started = new HandoffSession(nextGeneration, visual, env.selectedSlot(), false,
                HandoffStage.HOLSTERING, clipSeconds,
                env.nowSeconds() + HandoffTiming.deadline(clipSeconds));
        reassign(started);
        if (clipSeconds > 0.0) {
            env.notifySwitchStarted(env.selectedSlot());
            return;
        }
        commit(started);
    }

    private void reassign(HandoffSession next) {
        HandoffSession previous = session;
        session = next;
        listener.onSession(previous, next);
    }

    private void endSession() {
        HandoffSession previous = session;
        session = null;
        generation++;
        if (previous != null) {
            listener.onSession(previous, null);
        }
    }
}
