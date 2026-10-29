package com.arg_studios.arg_weaponry.weapon.equip;

import com.arg_studios.arg_weaponry.client.animation.WeaponViewControllers;
import com.arg_studios.arg_weaponry.client.audio.WeaponActionSounds;
import com.arg_studios.arg_weaponry.client.input.WeaponInspectInput;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


final class ClientEquipController {

    // This Class is responsible for  Gun Equipping controller
    private static final ClientEquipController INSTANCE = new ClientEquipController();

    private final Map<UUID, EquipPhase> phases = new ConcurrentHashMap<>();
    private final Map<UUID, HeldWeapon> held = new ConcurrentHashMap<>();

    /** The request number of the next payload; monotonic for the process and reset when leaving the world. */
    private int requestSequence;

    private ClientEquipController() {
    }

    /** @return the shared client controller. */
    static ClientEquipController instance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ queries

    /**
     * @param player the player to test, may be {@code null}
     * @return the predicted phase, never {@code null}
     */
    EquipPhase phase(Player player) {
        if (player == null) {
            return EquipPhase.IDLE;
        }
        EquipPhase phase = phases.get(player.getUUID());
        return phase == null ? EquipPhase.IDLE : phase;
    }


    boolean isBusy(Player player) {
        return phase(player).isBusy();
    }


    int scrollBaseSlot(LocalPlayer player) {
        if (player == null) {
            return 0;
        }
        EquipPhase phase = phase(player);
        return phase.hasPendingSlot() ? phase.pendingSlot() : player.getInventory().getSelectedSlot();
    }

    // ------------------------------------------------------------------ tick

    void tick(Minecraft minecraft) {
        if (minecraft == null) {
            return;
        }
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            // A momentarily missing player is not a session change: the predicted state goes away, but the request sequence
            // must keep rising, or the server's replay guard would reject every later request of this connection.
            forgetPredictedState();
            return;
        }
        UUID id = player.getUUID();
        if (!player.isAlive() || player.isSpectator() || !DrawHolsterConfig.get().enableDrawHolster()) {
            // Death, spectator mode and switching the feature off end the window: nothing may stay intercepted or blocked.
            dropPhase(player, phases.get(id));
            held.remove(id);
            return;
        }
        EquipPhase phase = phases.getOrDefault(id, EquipPhase.IDLE);
        int slot = player.getInventory().getSelectedSlot();
        ItemStack stack = player.getMainHandItem();
        String itemId = HeldWeapon.itemIdOf(stack);
        if (phase.isBusy()) {
            tickPhase(player, phase, stack, slot, itemId, minecraft);
            return;
        }
        // Idle: did a different weapon become the held one? Covers a hotbar switch, joining, respawning and being swapped
        // into the main hand, and it is the only place a draw can start from.
        HeldWeapon previous = held.put(id, HeldWeapon.of(stack, slot));
        if (previous == null || previous.differsFrom(slot, itemId)) {
            beginDraw(player, stack, slot, previous, false);
        }
    }


    private void tickPhase(LocalPlayer player, EquipPhase phase, ItemStack stack, int slot, String itemId,
                           Minecraft minecraft) {
        if (phase.state() == EquipState.HOLSTERING && minecraft.gui.screen() != null) {
            // Opening a screen applies the switch at once - a pending slot must not survive the menu.
            commit(player, phase);
            return;
        }
        EquipPhase.Observation observation = phase.classify(slot, itemId);
        if (observation == EquipPhase.Observation.HARD_INTERRUPT) {
            interrupt(player, phase, stack, slot);
            return;
        }
        if (observation == EquipPhase.Observation.EXPECTED_COMPLETION) {
            commit(player, phase);
            return;
        }
        EquipPhase advanced = phase.advance();
        if (advanced.state() == EquipState.HOLSTERING && advanced.isWindowElapsed()) {
            commit(player, advanced);
            return;
        }
        if (advanced.state() == EquipState.DRAWING && advanced.isWindowElapsed()) {
            dropPhase(player, advanced);
            return;
        }
        if (advanced.isTimedOut()) {
            // Safety net: a window something else prevented from finishing is forced to its outcome instead of blocking.
            if (advanced.state() == EquipState.HOLSTERING && advanced.hasPendingSlot()) {
                commit(player, advanced);
            } else {
                dropPhase(player, advanced);
            }
            return;
        }
        phases.put(player.getUUID(), advanced);
    }

    // ------------------------------------------------------------------ transitions


    private void commit(LocalPlayer player, EquipPhase phase) {
        int target = phase.pendingSlot();
        dropPhase(player, phase);
        if (target >= 0 && target < Inventory.getSelectionSize()
                && target != player.getInventory().getSelectedSlot()) {
            player.getInventory().setSelectedSlot(target);
        }
        int slot = player.getInventory().getSelectedSlot();
        ItemStack stack = player.getMainHandItem();
        HeldWeapon previous = held.put(player.getUUID(), HeldWeapon.of(stack, slot));
        beginDraw(player, stack, slot, previous, true);
    }

    /**
     * Drops a phase whose weapon was replaced without this controller asking for it.
     *
     * @param player the local player
     * @param phase  the interrupted phase
     * @param stack  the stack held now
     * @param slot   the slot selected now
     */
    private void interrupt(LocalPlayer player, EquipPhase phase, ItemStack stack, int slot) {
        boolean holstering = phase.state() == EquipState.HOLSTERING;
        dropPhase(player, phase);
        HeldWeapon previous = held.put(player.getUUID(), HeldWeapon.of(stack, slot));
        // A holster that was cut short already played its sound, so the new weapon only plays its draw.
        beginDraw(player, stack, slot, previous, holstering);
    }

    /**
     * Ends one phase and returns its clip to idle.
     *
     * @param player the local player
     * @param phase  the phase to drop, may be {@code null}
     */
    private void dropPhase(LocalPlayer player, EquipPhase phase) {
        if (player == null) {
            return;
        }
        phases.remove(player.getUUID());
        WeaponViewControllers.instance().finishEquipAnimation(InteractionHand.MAIN_HAND,
                phase == null ? null : phase.clip());
    }

    /**
     * Starts the draw of a weapon that has just become the held one.
     *
     * <p>Three things happen here and nowhere else: the clip is selected from the weapon's own {@code draw_anims} and played,
     * the predicted window is opened, and the two equip sounds are played when this client owns the change. The sounds mirror
     * what {@code WeaponHeldLifecycle} used to do, which is why that lifecycle stays silent for a pair of weapons this
     * subsystem handles (see {@link DrawHolsterManager#ownsLifecycle}).</p>
     *
     * @param player                   the local player
     * @param stack                    the stack held now
     * @param slot                     the main-hand slot
     * @param previous                 the previous observation, may be {@code null}
     * @param holsterSoundAlreadyPlayed whether a holster clip already played the outgoing weapon's sound
     */
    private void beginDraw(LocalPlayer player, ItemStack stack, int slot, HeldWeapon previous,
                           boolean holsterSoundAlreadyPlayed) {
        WeaponDefinition current = stack.getItem() instanceof WeaponItem weapon ? weapon.definition() : null;
        boolean owned = DrawHolsterManager.ownsLifecycle(previous == null ? null : previous.definition(), current);
        EquipAnimationResolver.Selection selection = null;
        if (current != null && DrawHolsterManager.handles(current)) {
            selection = startDraw(player, stack, slot, current);
        }
        if (!owned) {
            // Neither weapon of this change declares a draw or holster clip: the legacy lifecycle keeps its behaviour.
            return;
        }
        if (!holsterSoundAlreadyPlayed && previous != null && previous.definition() != null) {
            WeaponActionSounds.holster(previous.definition(), previous.emptyMagazine());
        }
        if (current != null) {
            boolean firstDraw = selection != null ? selection.firstDraw()
                    : FirstDrawFlag.shouldUseFirstDraw(stack, slot, HeldWeapon.itemIdOf(stack));
            WeaponActionSounds.draw(current, firstDraw, stack.getItem() instanceof WeaponItem weapon
                    && weapon.animationAmmoCount(stack) <= 0);
        }
    }

    /**
     * Selects, plays and opens the draw window of one weapon.
     *
     * <p>The configured clips are tried in order - the applicable one first, the declared remainder as the fallback of a pack
     * whose animation resource is missing the selected variant - and the first clip the controller can actually start wins.
     * The window always uses the duration of the entry that was chosen, so the clip and the window end together.</p>
     *
     * @param player     the local player
     * @param stack      the held weapon stack
     * @param slot       the main-hand slot
     * @param definition the weapon definition
     * @return the selection that was used, or {@code null} when the weapon declares no draw clip at all
     */
    private EquipAnimationResolver.Selection startDraw(LocalPlayer player, ItemStack stack, int slot,
                                                       WeaponDefinition definition) {
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return null;
        }
        int ammoCount = weapon.animationAmmoCount(stack);
        String itemId = HeldWeapon.itemIdOf(stack);
        boolean firstDraw = FirstDrawFlag.shouldUseFirstDraw(stack, slot, itemId);
        List<EquipAnimationResolver.Selection> candidates =
                EquipAnimationResolver.drawCandidates(definition, ammoCount, firstDraw);
        EquipAnimationResolver.Selection played = playFirst(candidates);
        EquipAnimationResolver.Selection chosen = played != null ? played
                : candidates.isEmpty() ? null : candidates.get(0);
        if (chosen == null) {
            return null;
        }
        if (chosen.firstDrawEntry()) {
            // The first-draw animation has been consumed on this client; the guard keeps a fast switch from replaying it
            // before the server's persistent flag arrives.
            FirstDrawGuard.markPlayed(slot, itemId);
        }
        EquipPhase phase = EquipPhase.drawing(slot, itemId, chosen.clip(),
                EquipTiming.secondsToTicks(chosen.durationSeconds()));
        if (phase.isBusy()) {
            phases.put(player.getUUID(), phase);
        }
        return chosen;
    }

    /**
     * Starts the first clip of the list that the weapon's animation resource can provide.
     *
     * @param candidates the ordered candidates
     * @return the clip that started, or {@code null} when none could
     */
    private static EquipAnimationResolver.Selection playFirst(List<EquipAnimationResolver.Selection> candidates) {
        for (EquipAnimationResolver.Selection candidate : candidates) {
            if (WeaponViewControllers.instance().playEquipAnimation(InteractionHand.MAIN_HAND, candidate.clip(),
                    candidate.durationSeconds())) {
                return candidate;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ input

    /**
     * Intercepts one player-initiated switch and either starts, re-targets or cancels a holster.
     *
     * <p>This is the whole client rule of the delayed switch; {@link DrawHolsterInput} only translates a scroll or a hotbar
     * key into the slot it means.</p>
     *
     * @param player     the local player
     * @param targetSlot the slot the player asked for
     * @return whether the input was consumed, i.e. whether vanilla must not perform the switch
     */
    boolean requestSwitch(LocalPlayer player, int targetSlot) {
        if (player == null || player.isSpectator() || !player.isAlive() || targetSlot < 0
                || targetSlot >= Inventory.getSelectionSize()) {
            return false;
        }
        DrawHolsterConfig.Settings config = DrawHolsterConfig.get();
        if (!config.enableDrawHolster() || !config.holsterSwitchDelay()) {
            // Delay disabled: the vanilla instant switch happens, and only the draw clip plays.
            return false;
        }
        UUID id = player.getUUID();
        int currentSlot = player.getInventory().getSelectedSlot();
        EquipPhase phase = phases.getOrDefault(id, EquipPhase.IDLE);

        if (phase.state() == EquipState.HOLSTERING) {
            if (targetSlot == currentSlot) {
                // Re-selecting the current slot cancels the holster and plays no draw.
                cancel(player, phase);
                return true;
            }
            if (phase.pendingSlot() != targetSlot) {
                // The latest target wins and the running clip is not restarted, so scroll spam walks the hotbar.
                phases.put(id, phase.withPendingSlot(targetSlot));
                // The server keeps its own copy of the target, so a moved destination is announced. It never chooses an
                // effect from it - only whether the arriving slot is the expected completion.
                Services.NETWORK.sendToServer(new EquipActionPayload(EquipActionPayload.ACTION_START_HOLSTER,
                        targetSlot, ++requestSequence));
            }
            return true;
        }

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            return false;
        }
        WeaponDefinition definition = weapon.definition();
        if (!DrawHolsterManager.handles(definition) || !EquipAnimationResolver.hasHolsterClip(definition)) {
            return false;
        }
        if (targetSlot == currentSlot) {
            return false;
        }

        if (phase.state() == EquipState.DRAWING) {
            boolean skip = config.skipsHolsterBelow(phase.progress());
            // The draw is interrupted either way; only the holster that follows is optional.
            dropPhase(player, phase);
            if (skip) {
                applySwitch(player, targetSlot);
                return true;
            }
        }

        int ammoCount = weapon.animationAmmoCount(stack);
        EquipAnimationResolver.Selection played = playFirst(
                EquipAnimationResolver.holsterCandidates(definition, ammoCount));
        if (played == null) {
            // The animation resource can provide none of the declared holster clips, so delaying the switch would leave the
            // player looking at a static weapon. Vanilla performs the switch instead, and the incoming weapon's draw (and the
            // outgoing weapon's holster sound) still happen through the normal held-item change.
            return false;
        }
        EquipPhase holster = EquipPhase.holstering(currentSlot, HeldWeapon.itemIdOf(stack), played.clip(),
                EquipTiming.secondsToTicks(played.durationSeconds()), targetSlot);
        phases.put(id, holster);
        cancelConflictingActions();
        WeaponActionSounds.holster(definition, ammoCount <= 0);
        // The server cannot observe a switch that has not been applied yet, so it is told that one started.
        Services.NETWORK.sendToServer(new EquipActionPayload(EquipActionPayload.ACTION_START_HOLSTER, targetSlot,
                ++requestSequence));
        return true;
    }

    /**
     * Applies a switch at once, without a holster clip, and starts the incoming draw.
     *
     * @param player     the local player
     * @param targetSlot the slot to select
     */
    private void applySwitch(LocalPlayer player, int targetSlot) {
        if (targetSlot != player.getInventory().getSelectedSlot()) {
            player.getInventory().setSelectedSlot(targetSlot);
        }
        int slot = player.getInventory().getSelectedSlot();
        ItemStack stack = player.getMainHandItem();
        HeldWeapon previous = held.put(player.getUUID(), HeldWeapon.of(stack, slot));
        beginDraw(player, stack, slot, previous, true);
    }

    /**
     * Cancels a running holster without switching anything, and tells the server so it stops blocking immediately.
     *
     * @param player the local player
     * @param phase  the running holster
     */
    private void cancel(LocalPlayer player, EquipPhase phase) {
        boolean hadPending = phase.hasPendingSlot();
        dropPhase(player, phase);
        if (hadPending) {
            Services.NETWORK.sendToServer(new EquipActionPayload(EquipActionPayload.ACTION_CANCEL_HOLSTER,
                    EquipPhase.NO_SLOT, ++requestSequence));
        }
    }

    /**
     * Ends the actions that cannot keep playing under a holster clip.
     *
     * <p>Only the existing stop primitives are used: the inspection request is cancelled, and the two looping action sounds
     * are stopped. The playback itself is replaced by the holster clip in
     * {@link WeaponViewControllers#playEquipAnimation}, which drops a firing, reloading or inspecting clip anyway.</p>
     */
    private static void cancelConflictingActions() {
        WeaponInspectInput.cancelActive();
        WeaponActionSounds.stopInspection(InteractionHand.MAIN_HAND);
        WeaponActionSounds.stopReload(InteractionHand.MAIN_HAND);
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Drops every predicted phase and the held baseline, without touching the request sequence or the session guards.
     *
     * <p>Used when the client has no player for a moment (the main menu, a disconnect in progress); the session itself has not
     * ended, so nothing that the server tracks by connection may be reset here.</p>
     */
    private void forgetPredictedState() {
        phases.clear();
        held.clear();
        WeaponViewControllers.instance().finishEquipAnimation(InteractionHand.MAIN_HAND);
    }

    /**
     * Forgets every player's predicted state; used when the client leaves a world.
     *
     * <p>The first-draw guard is reset with it: a rejoin is a new session, so a weapon the server has not marked as drawn
     * plays its first-draw clip again, exactly as the persistent flag says it should. The request sequence starts over too,
     * because a rejoin is a new connection and the server's replay guard starts empty for it.</p>
     */
    void clear() {
        forgetPredictedState();
        requestSequence = 0;
        FirstDrawGuard.reset();
    }
}
