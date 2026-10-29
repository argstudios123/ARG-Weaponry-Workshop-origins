package com.arg_studios.arg_weaponry.client.input;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.animation.WeaponViewControllers;
import com.arg_studios.arg_weaponry.client.audio.WeaponActionSounds;
import com.arg_studios.arg_weaponry.client.recoil.WeaponRecoil;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.network.WeaponFirePayload;
import com.arg_studios.arg_weaponry.network.WeaponTriggerPayload;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult;
import com.arg_studios.arg_weaponry.weapon.fire.FiringModes;
import com.arg_studios.arg_weaponry.weapon.fire.WeaponFireSessions;
import com.arg_studios.arg_weaponry.weapon.state.FireMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Client side of weapon firing: turns the attack key into a request, and an accepted shot into an animation.
 *
 * <p>Input and feedback are split by the server on purpose: this class never predicts a shot, never touches ammunition
 * and never decides that a shot happened. It sends a request and, when the server confirms a shot, starts the firing
 * animation for that hand.</p>
 *
 * <h2>Two request shapes, chosen by the weapon's resolved mode</h2>
 * <ul>
 *   <li><strong>semi-automatic</strong> - one press is one {@link WeaponFirePayload}. Vanilla calls
 *   {@code Minecraft.startAttack()} once per discrete press and the attack mixin cancels the vanilla attack while a
 *   weapon is held, so holding the button cannot produce a stream of requests;</li>
 *   <li><strong>automatic and burst</strong> - one press opens one <em>trigger session</em> with a single
 *   {@link WeaponTriggerPayload}, the server schedules the shots, and the client only keeps that session alive. No
 *   packet is ever sent per shot, so the client cannot decide the firing rate.</li>
 * </ul>
 *
 * <h2>Trigger-session lifecycle on the client</h2>
 * <ol>
 *   <li>press: a new press sequence is allocated and sent as {@code pressed = true};</li>
 *   <li>held: every {@value #HEARTBEAT_TICKS} client ticks the <em>same</em> sequence is re-sent, which the server treats
 *   as a lease refresh. Nothing is sent on the other ticks;</li>
 *   <li>release: {@code pressed = false} with the same sequence, sent once;</li>
 *   <li>safety net: {@link #clientTick(Minecraft)} releases the session when the physical button is up, a screen opened,
 *   the mouse was released, the player died or left, or the held stack changed. A session the server completes or
 *   refuses is not restarted while the button stays down.</li>
 * </ol>
 *
 * <h2>Sequence numbers</h2>
 * <p>Firing requests and trigger presses use separate client-lifetime counters. Both must increase, and the server
 * rejects anything that does not, which makes a replayed or duplicated packet a no-op. Accepted shots come back with a
 * <em>server</em>-assigned shot id, so a duplicated acceptance is ignored by the same "is this newer?" test in every
 * firing mode.</p>
 */
public final class WeaponFireInput {

    /** Ticks between two heartbeats of a held trigger. */
    private static final int HEARTBEAT_TICKS = WeaponFireSessions.HEARTBEAT_TICKS;

    /**
     * The accepted-shot gate: one shot id per hand, so a duplicated acceptance of the same shot can never animate, sound
     * or recoil twice. See {@link AcceptedShotIds}.
     */
    private static final AcceptedShotIds<InteractionHand> ACCEPTED_SHOTS = new AcceptedShotIds<>();

    private static int nextSequence;

    /** The hand the trigger state belongs to, or {@code null} while no trigger is held. */
    private static InteractionHand triggerHand;

    /** The press/heartbeat/release state machine; see {@link TriggerState}. */
    private static final TriggerState TRIGGER = new TriggerState();

    private WeaponFireInput() {
    }

    /**
     * @param player the local player
     * @param hand   the hand to inspect
     * @return whether that hand holds a registered weapon item
     */
    public static boolean holdsWeapon(LocalPlayer player, InteractionHand hand) {
        return player != null && player.getItemInHand(hand).getItem() instanceof WeaponItem;
    }

    /**
     * @param player the local player, may be {@code null}
     * @param hand   the hand holding the weapon
     * @return the mode the held weapon would currently fire in, or {@code null} when it is not a weapon
     */
    public static FireMode currentMode(LocalPlayer player, InteractionHand hand) {
        if (player == null || hand == null) {
            return null;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return null;
        }
        return FiringModes.resolve(weapon.definition().firingModes(), weapon.state(stack).fireMode());
    }

    /**
     * Handles one discrete press of the dedicated fire key.
     *
     * <p>This is the only press path now: the attack mixin no longer sends anything, it only suppresses vanilla's attack
     * while a weapon is held. A press therefore cannot be processed twice, and the semi-automatic, automatic and burst
     * behaviours are the same three the 10Q trigger protocol already implements.</p>
     *
     * @param player the local player, may be {@code null}
     */
    public static void onFireKeyPressed(LocalPlayer player) {
        if (player == null || !holdsWeapon(player, InteractionHand.MAIN_HAND)) {
            return;
        }
        FireMode mode = currentMode(player, InteractionHand.MAIN_HAND);
        if (mode == FireMode.AUTO || mode == FireMode.BURST) {
            onTriggerPressed(player, InteractionHand.MAIN_HAND);
        } else if (mode == FireMode.SINGLE) {
            requestFire(player, InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Sends one semi-automatic firing request.
     *
     * <p>Only for a weapon whose resolved mode is {@link FireMode#SINGLE}; automatic and burst fire open a trigger
     * session through {@link #onTriggerPressed(LocalPlayer, InteractionHand)}.</p>
     *
     * @param player the local player
     * @param hand   the hand to fire from
     */
    public static void requestFire(LocalPlayer player, InteractionHand hand) {
        if (player == null) {
            return;
        }
        // [DrawHolster hook] No firing request is sent while the weapon is being drawn or holstered.
        if (com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.isEquipBusy(player)) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem)) {
            return;
        }
        if (currentMode(player, hand) != FireMode.SINGLE) {
            // Automatic and burst fire are driven by the server-side session, never by a packet per shot.
            return;
        }
        Services.NETWORK.sendToServer(new WeaponFirePayload(hand, ++nextSequence));
    }

    /**
     * Starts a trigger session for automatic or burst fire.
     *
     * <p>Called once per discrete attack press. A repeated callback while a trigger is already held is ignored, so it can
     * never open a second session.</p>
     *
     * @param player the local player
     * @param hand   the hand holding the weapon
     */
    public static void onTriggerPressed(LocalPlayer player, InteractionHand hand) {
        if (player == null || hand == null || triggerHand != null) {
            return;
        }
        // [DrawHolster hook] An automatic or burst session cannot be opened while the weapon is being drawn or holstered.
        if (com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.isEquipBusy(player)) {
            return;
        }
        FireMode mode = currentMode(player, hand);
        if (mode == null || mode == FireMode.SINGLE) {
            return;
        }
        TRIGGER.press().ifPresent(packet -> {
            triggerHand = hand;
            Services.NETWORK.sendToServer(new WeaponTriggerPayload(hand, packet.pressed(), packet.sequence()));
        });
    }

    /**
     * Keeps the trigger session alive while the button is held.
     *
     * <p>Called once per client tick from the attack-continue hook: one heartbeat every {@value #HEARTBEAT_TICKS} ticks,
     * deliberately not one packet per tick. Release detection lives in {@link #clientTick(Minecraft)}.</p>
     */
    public static void onTriggerHeld() {
        if (triggerHand == null) {
            return;
        }
        TRIGGER.heartbeat().ifPresent(packet -> Services.NETWORK.sendToServer(
                new WeaponTriggerPayload(triggerHand, packet.pressed(), packet.sequence())));
    }

    /**
     * Ends the trigger session of the held hand, if one is open.
     *
     * <p>Idempotent, so the release path, the client-tick safety net and a reload notification can all call it without
     * ever sending two releases.</p>
     */
    public static void onTriggerReleased() {
        if (triggerHand == null) {
            return;
        }
        InteractionHand hand = triggerHand;
        TRIGGER.release().ifPresent(packet -> Services.NETWORK.sendToServer(
                new WeaponTriggerPayload(hand, packet.pressed(), packet.sequence())));
        triggerHand = null;
    }

    /**
     * Client-tick safety net for the trigger: releases the session when the button is no longer physically held, when a
     * screen opened, when the mouse is no longer grabbed, when the player died or left, or when the held stack changed.
     *
     * <p>This is what makes input loss harmless: even if the attack-continue hook is never called again - because a
     * screen captured the input, the window lost focus, or the player disconnected - the session is released here, and
     * the server's own lease is the last line of defence.</p>
     *
     * @param minecraft the client
     */
    public static void clientTick(Minecraft minecraft) {
        if (triggerHand == null) {
            return;
        }
        LocalPlayer player = minecraft.player;
        boolean stillHolding = player != null
                && !player.isRemoved()
                && player.isAlive()
                && minecraft.gui.screen() == null
                && minecraft.mouseHandler.isMouseGrabbed()
                && WeaponFireKey.isHeld(minecraft)
                && player.getItemInHand(triggerHand).getItem() instanceof WeaponItem
                && currentMode(player, triggerHand) != FireMode.SINGLE;
        if (!stillHolding) {
            onTriggerReleased();
        }
    }

    /**
     * Applies a server report about a trigger session.
     *
     * @param hand     the hand
     * @param sequence the press sequence the report refers to
     * @param result   the server's decision
     * @param active   whether a session is running afterwards
     */
    public static void onSessionUpdate(InteractionHand hand, int sequence, WeaponActionResult result,
                                       boolean active) {
        if (triggerHand == null || triggerHand != hand || sequence != TRIGGER.sequence()) {
            // A report for a press this client no longer tracks.
            return;
        }
        TRIGGER.onSessionUpdate(active);
        if (!active && result != WeaponActionResult.OK) {
            Constants.LOG.debug("[ARG] The trigger session in {} ended: {}", hand, result);
        }
    }

    /**
     * Notifies the client that a reload started, which ends any automatic or burst session.
     *
     * <p>Called from the reload result handler. The trigger is suppressed until the button is released, so a burst can
     * never resume just because the old trigger state is still down.</p>
     *
     * @param hand the hand the reload belongs to
     */
    public static void onReloadStarted(InteractionHand hand) {
        if (triggerHand == null || triggerHand != hand) {
            return;
        }
        TRIGGER.suppress();
    }

    /**
     * Applies one accepted shot: the firing animation, the weapon's firing sound and the hit feedback.
     *
     * <p>Called on the client thread from the networking layer with the server's own hit facts. The animation and the
     * sounds are selected from the content pack's {@code fire_anims} and {@code sounds}; nothing here names a specific
     * animation or sound identifier, and nothing here can fire on its own - a rejected shot produces no payload at all.</p>
     *
     * @param hand     the hand the shot came from
     * @param shotId   the server-assigned shot id
     * @param hit      whether the shot hit an entity
     * @param headshot whether the hit was a headshot
     * @param killed   whether the hit killed the entity
     */
    public static void onAcceptedShot(InteractionHand hand, int shotId, boolean hit, boolean headshot,
                                      boolean killed) {
        if (!ACCEPTED_SHOTS.accept(hand, shotId)) {
            // A duplicate acceptance for a shot that was already animated, sounded and recoiled.
            return;
        }
        if (!WeaponViewControllers.instance().playFireAnimation(hand)) {
            Constants.LOG.debug("[ARG] The shot in {} was accepted but the weapon declares no fire animation", hand);
        }
        // A shot dominates an inspection: the clip is dropped by the controller and the inspection's remaining audio is
        // stopped with it, so an interrupted inspection cannot keep playing after the weapon already fired.
        WeaponActionSounds.stopInspection(hand);
        // A shot also ends the inspection itself on the client, so the aim restriction stops applying the moment the action
        // really ended instead of when its duration would have run out.
        WeaponInspectInput.cancelActive();
        WeaponActionSounds.shot(hand, hit, headshot, killed);
        // Recoil: one impulse for this accepted shot, and only for it. A rejected request never reaches this method, and
        // nothing is predicted client side, so a shot cannot be counted twice.
        WeaponRecoil.onAcceptedShot(hand);
    }

    /**
     * Forgets the client-side firing state.
     *
     * <p>Called when the client leaves a world. The sequence counters keep increasing - the server's per-connection
     * records are cleared on disconnect, so a higher number is always accepted there - while the animation history and
     * every trigger flag start empty.</p>
     */
    public static void reset() {
        ACCEPTED_SHOTS.clear();
        clearTrigger();
    }

    /** @return whether a trigger session is currently tracked, for diagnostics and tests */
    public static boolean isTriggerHeld() {
        return triggerHand != null && TRIGGER.isHeld();
    }

    /** @return the sequence of the tracked press, or {@code 0} when no trigger is held */
    public static int triggerSequence() {
        return TRIGGER.sequence();
    }

    /** @return whether the server reported a running session for the held trigger */
    public static boolean isSessionActive() {
        return TRIGGER.isSessionActive();
    }

    private static void clearTrigger() {
        triggerHand = null;
        TRIGGER.reset();
    }
}

