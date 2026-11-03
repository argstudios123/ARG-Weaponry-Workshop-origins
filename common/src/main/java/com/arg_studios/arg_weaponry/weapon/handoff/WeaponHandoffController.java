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
 * <p>The chain is entered at {@code HOLSTERING} when something is drawn and the player switches away from it, and at
 * {@code DRAWING} directly when nothing is drawn and a weapon becomes held - from an empty slot, from ammunition, from an
 * ordinary item, or because a command or another mod selected the slot. That second entry point has no holster half, and it
 * is not optional: without it the weapon would simply appear in its idle pose, which is exactly what happened before
 * {@link #repairExternalChange()} stopped asking a query that adopted the live stack.</p>
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
     * The selection the previous idle tick observed, or {@code null} before the first one.
     *
     * <p>This is what separates a real switch from the first look at a hand that simply already holds a weapon: joining the
     * world, respawning and a resource reload all start from an empty visual registry, and none of them is a draw. Only a
     * selection that changes <em>after</em> it was observed starts one.</p>
     */
    private WeaponIdentity observedSelection;

    /**
     * Whether the draw clip of the running session is armed (paused at its first frame) and still waiting for the frame in
     * which the weapon is really drawn.
     *
     * <p>{@link #notifyWeaponRendered(WeaponIdentity)} releases it, so the raise starts exactly when the player first sees
     * the weapon. {@link #tick()} releases it anyway after {@link #MAX_ARMED_TICKS}, which covers the cases where no
     * first-person frame is drawn at all (third person, an open screen), so an armed clip can never leave the hand frozen.</p>
     */
    private boolean drawAwaitingFirstFrame;

    /** Ticks the armed draw has been waiting, so the safety release is measured in ticks like the rest of the machine. */
    private int armedTicks;

    /** How long an armed draw may wait for its first rendered frame before it is released anyway. */
    static final int MAX_ARMED_TICKS = 2;

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
            WeaponIdentity outgoing = visuals.visualIdentity();
            if (!outgoing.isPresent()) {
                // Nothing is drawn, so there is no holster half to play and no reason to hold the switch back: vanilla
                // applies it, and the next tick adopts the weapon that became held and starts its draw (see
                // repairExternalChange). Declining here is what keeps a switch that has nothing to animate instant.
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
                if (visuals.visualIdentity().isPresent()) {
                    begin(slot, true);
                } else {
                    // The hand stopped drawing a weapon before the switch was applied, so there is nothing left to holster.
                    applySwitchAndDraw(slot);
                }
                return;
            }
            repairExternalChange();
            return;
        }

        double now = env.nowSeconds();
        switch (session.stage()) {
            case HOLSTERING -> {
                if (!visuals.visualIdentity().isPresent()) {
                    // Nothing is drawn any more, so there is no holster left to wait for. Committing now applies the slot
                    // this transition was waiting for instead of holding the hand until the safety deadline.
                    commit(session);
                    return;
                }
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
                if (!visuals.visualIdentity().isPresent()) {
                    // No clip can report completion for a weapon that is not drawn any more (the stack left the hand, a
                    // resource reload released it, ...). Waiting for the deadline here is what would turn a lost weapon into
                    // an unexpected incoming-action delay, so the transition ends now and rendering falls back to the live
                    // hand.
                    endSession();
                    return;
                }
                if (drawAwaitingFirstFrame) {
                    armedTicks++;
                    if (armedTicks > MAX_ARMED_TICKS) {
                        // No first-person frame drew the weapon (third person, an open screen), so the raise must not stay
                        // paused: releasing it here keeps gameplay timing identical to the server's window.
                        releaseArmedDraw();
                    }
                }
                if (visuals.drawFinished() || session.isExpired(now)) {
                    releaseArmedDraw();
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
        // The selection is re-observed from scratch, so whatever the hand happens to hold when the reset is over is taken as
        // the state of the world instead of being played as a draw.
        observedSelection = null;
        drawAwaitingFirstFrame = false;
        armedTicks = 0;
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
        WeaponIdentity outgoing = visuals.visualIdentity();
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
        // The slot this transition committed to is the selection the idle ticks after it must compare against.
        observedSelection = incoming;
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
        armDrawAwaitingFirstFrame();
        reassign(running.enter(HandoffStage.DRAWING, clipSeconds, env.nowSeconds()));
    }

    /** Abandons a running holster without changing the slot: the outgoing weapon returns to idle playback. */
    private void cancelHolster() {
        visuals.stopHolster();
        env.notifySwitchCancelled();
        endSession();
    }

    /**
     * Repairs a selection that did not pass through {@link #requestSwitch} - a command, another mod, a hotbar slot bound
     * to a mouse button, or vanilla applying a request this controller declined.
     *
     * <p>Two different repairs live here, and both are needed to keep the visual weapon and the inventory in step:</p>
     * <ul>
     *     <li>a weapon became selected and is not being drawn - a holster would have nothing to animate, so the weapon is
     *     adopted and its draw clip starts at once. This is the case the handoff-only implementation lost: switching from an
     *     empty slot, from ammunition or from any ordinary item to a weapon showed it in its idle pose without ever playing
     *     the draw, and without playing its draw sound either;</li>
     *     <li>a weapon is drawn and the selection moved somewhere else - the outgoing weapon plays its holster and the
     *     hand-off follows at its end, which is what keeps the weapon visible while it is being put away.</li>
     * </ul>
     * <p>The first idle tick after the controller is (re)created only observes the selection, so joining the world,
     * respawning, and a resource reload cannot fire a draw for a weapon that was already in the player's hand.</p>
     */
    private void repairExternalChange() {
        WeaponIdentity visual = visuals.visualIdentity();
        WeaponIdentity selected = env.selectedIdentity();
        boolean selectionChanged = observedSelection != null && !selected.sameAs(observedSelection);
        observedSelection = selected;

        if (selectionChanged && selected.isPresent() && (visual.sameAs(selected) || !visual.isPresent())) {
            // The selected weapon is either not drawn at all, or was adopted by a rendered frame before this tick could see
            // the switch: a scroll applies the slot between two ticks, so the next frame can reach the registry first and
            // leave the weapon in its idle clip on an animator that never played a draw. Raising it here covers both - the
            // clip starts on the animator that is already on screen and cross-fades in from the frame the player sees.
            startDraw(selected);
            return;
        }
        if (visual.isPresent() && !visual.sameAs(selected) && !env.actionOwnsPlayback()) {
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
    }

    /**
     * Applies a selection change this controller declined to defer and starts the draw of whatever it selects.
     *
     * @param targetSlot the slot that becomes selected
     */
    private void applySwitchAndDraw(int targetSlot) {
        if (targetSlot != env.selectedSlot()) {
            env.selectSlot(targetSlot);
        }
        WeaponIdentity selected = env.selectedIdentity();
        if (selected.isPresent()) {
            startDraw(selected);
        }
    }

    /**
     * Adopts a weapon that became held and starts its draw clip - the half of a hand-off that has no holster.
     *
     * <p>The order is the same one the boundary uses: the weapon is adopted first, so it owns a brand new animator, and only
     * then is the clip started on it. Its first drawn frame is therefore the clip's first frame, and the session blocks
     * gameplay actions for exactly as long as that clip runs, which is what the server already enforces for the same change.
     * A weapon that a rendered frame already adopted keeps its animator, so the clip cross-fades in from the pose the player
     * is looking at instead of starting over.</p>
     *
     * @param incoming the identity of the weapon that became held; must be present
     */
    private void startDraw(WeaponIdentity incoming) {
        long nextGeneration = ++generation;
        HandoffSession drawing = new HandoffSession(nextGeneration, WeaponIdentity.NONE, incoming.slot(), false,
                HandoffStage.DRAWING, 0.0, env.nowSeconds());
        reassign(drawing);
        // The selection this transition belongs to is the one it is raising; recording it here is what keeps the idle ticks
        // that follow from mistaking the same weapon for a fresh switch.
        observedSelection = incoming;
        if (!visuals.visualIdentity().sameAs(incoming)) {
            // A fresh animator, so the first drawn frame is the clip's first frame.
            visuals.adopt(incoming);
        }
        double clipSeconds = visuals.playDraw();
        Constants.LOG.debug("[ARG] Handoff draw-only: playDraw returned {} for '{}'({})", clipSeconds,
                incoming.itemId(), incoming.slot());
        if (clipSeconds <= 0.0) {
            // No draw clip: the weapon is adopted and shown in its idle playback, and nothing is left running.
            endSession();
            return;
        }
        armDrawAwaitingFirstFrame();
        reassign(drawing.enter(HandoffStage.DRAWING, clipSeconds, env.nowSeconds()));
    }

    /**
     * Marks the running draw as armed: the clip is paused at its first frame until the weapon is drawn.
     *
     * <p>{@link #notifyWeaponRendered(WeaponIdentity)} releases it, and {@link #tick()} releases it after
     * {@link #MAX_ARMED_TICKS} when no first-person frame draws the weapon at all.</p>
     */
    private void armDrawAwaitingFirstFrame() {
        drawAwaitingFirstFrame = true;
        armedTicks = 0;
    }

    /**
     * Releases an armed draw clip, if there is one.
     *
     * @return {@code true} when this call started the paused playback
     */
    private boolean releaseArmedDraw() {
        if (!drawAwaitingFirstFrame) {
            return false;
        }
        drawAwaitingFirstFrame = false;
        armedTicks = 0;
        return visuals.releaseDraw();
    }

    /**
     * Reports that a first-person frame is about to draw a weapon, so an armed draw clip can start on exactly that frame.
     *
     * <p>This is the reference implementations' "start the raise on the first rendered frame" rule. Without it the clip runs
     * while the weapon is invisible, and the player first sees it halfway through the raise - the "snap into position"
     * artifact. A render that belongs to a different weapon (a stale frame, or a newer session) releases nothing, which is
     * the same generation guard the rest of the machine uses.</p>
     *
     * @param rendered the identity of the weapon that frame is drawing
     */
    public void notifyWeaponRendered(WeaponIdentity rendered) {
        if (!drawAwaitingFirstFrame || session == null || rendered == null || !rendered.isPresent()) {
            return;
        }
        if (!rendered.sameAs(visuals.visualIdentity())) {
            return;
        }
        releaseArmedDraw();
    }

    /**
     * Observes the selection before a first-person frame is drawn.
     *
     * <p>A scroll applies the slot change between two ticks, so without this the frame that follows it would draw whatever
     * the registry holds - nothing for a weapon that just became held - and the state machine would only catch up on the
     * next tick. Running the idle repair here means a weapon that becomes held between ticks is adopted and armed before
     * that frame samples a pose, exactly like a switch that is observed on the tick.</p>
     *
     * <p>It never touches a running transition: while a session exists the machine owns the hand and is advanced by
     * {@link #tick()} only.</p>
     */
    public void observeBeforeRender() {
        if (session != null || !env.canTransition()) {
            return;
        }
        repairExternalChange();
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
        // An armed draw belongs to the session that armed it; a later one must not be released by a stale render.
        drawAwaitingFirstFrame = false;
        armedTicks = 0;
        if (previous != null) {
            listener.onSession(previous, null);
        }
    }
}
