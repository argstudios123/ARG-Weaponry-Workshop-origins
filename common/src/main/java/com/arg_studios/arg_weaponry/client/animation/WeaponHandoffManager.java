package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterConfig;
import com.arg_studios.arg_weaponry.weapon.handoff.HandoffSession;
import com.arg_studios.arg_weaponry.weapon.handoff.WeaponHandoffController;
import com.arg_studios.arg_weaponry.weapon.handoff.WeaponIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;

/**
 * The client-side entry point of the handoff subsystem, and the only place that advances it.
 *
 * <p>Three things call into this class and nothing else does: the input path (through {@code DrawHolsterManager}), the
 * client tick, and the lifecycle resets. It owns exactly one {@link WeaponHandoffController} - one handoff at a time, which
 * is what makes "no competing sessions" true by construction - and the visual weapons it drives live in
 * {@link VisualWeapons}.</p>
 *
 * <p>The controller is per process, not per player: it is reset whenever the player, the level or the session changes, so a
 * transition can never leak from one world into another.</p>
 */
public final class WeaponHandoffManager {

    private static final WeaponHandoffManager INSTANCE = new WeaponHandoffManager();

    private final ClientHandoffEnvironment environment = new ClientHandoffEnvironment();
    private final WeaponHandoffController controller =
            new WeaponHandoffController(environment, new ClientHandoffVisuals(InteractionHand.MAIN_HAND));

    private long lastTickNanos;

    /** Whether the subsystem is already known to be inert, so the reset is not repeated every tick. */
    private boolean idle = true;

    private WeaponHandoffManager() {
    }

    /** @return the shared manager. */
    public static WeaponHandoffManager instance() {
        return INSTANCE;
    }

    /** @return the controller, for diagnostics and for the rendering facade's "is a transition running" question. */
    public WeaponHandoffController controller() {
        return controller;
    }

    /** @return whether a handoff is running, i.e. whether the visual weapon may differ from the selected one. */
    public boolean isTransitioning() {
        return controller.isTransitioning();
    }

    /**
     * Handles one requested switch.
     *
     * @param player     the local player, may be {@code null}
     * @param targetSlot the slot the player asked for
     * @return {@code true} when vanilla must not perform the switch
     */
    public boolean requestSwitch(LocalPlayer player, int targetSlot) {
        if (player == null || !DrawHolsterConfig.get().handoffEnabled()) {
            return false;
        }
        return controller.requestSwitch(targetSlot);
    }

    /**
     * @param player  the local player, may be {@code null}
     * @param yOffset the scroll delta vanilla reported; positive scrolls towards the previous slot
     * @return {@code true} when vanilla must not switch the slot
     */
    public boolean requestScroll(LocalPlayer player, double yOffset) {
        if (player == null || !DrawHolsterConfig.get().handoffEnabled()) {
            return false;
        }
        int direction = yOffset > 0.0 ? -1 : yOffset < 0.0 ? 1 : 0;
        if (direction == 0) {
            return false;
        }
        return controller.requestSwitch(Math.floorMod(scrollBaseSlot(player) + direction,
                Inventory.getSelectionSize()));
    }

    /**
     * The slot a scroll is computed from: the destination of the running transition while one waits, so scroll spam walks
     * the hotbar instead of restarting the holster.
     *
     * @param player the local player, may be {@code null}
     * @return the base slot
     */
    public int scrollBaseSlot(LocalPlayer player) {
        if (player == null) {
            return 0;
        }
        HandoffSession session = controller.session();
        return session != null ? session.targetSlot() : player.getInventory().getSelectedSlot();
    }

    /**
     * Advances the subsystem by one client tick: the visual weapons' own timelines first, then the transition state, so a
     * clip that finished during this tick is seen by the state machine in the same tick.
     *
     * @param minecraft the client
     */
    public void clientTick(Minecraft minecraft) {
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        boolean runnable = player != null && minecraft.level != null && player.isAlive() && !player.isSpectator()
                && DrawHolsterConfig.get().handoffEnabled();
        if (!runnable) {
            if (!idle) {
                // Reset once, not once per tick: dropping the visual weapon every tick would recreate its animator on every
                // frame and freeze playback at the first frame while the feature is off.
                reset();
                idle = true;
            }
            return;
        }
        idle = false;
        VisualWeapons.instance().tick(elapsedSeconds());
        controller.tick();
    }

    /** Drops every session and visual weapon; used on disconnect, world changes, resource reloads and feature toggle. */
    public void reset() {
        controller.reset();
        VisualWeapons.instance().reset();
        environment.resetSequence();
        lastTickNanos = 0L;
        idle = true;
    }

    /** Logs the current transition state once; used at boundaries, never per frame. */
    public void logTransition(String reason) {
        HandoffSession session = controller.session();
        if (session == null) {
            Constants.LOG.debug("[ARG] Handoff {}: idle", reason);
            return;
        }
        WeaponIdentity outgoing = session.outgoing();
        Constants.LOG.debug("[ARG] Handoff {}: {} gen={} from='{}'({}) to={} deferred={}", reason, session.stage(),
                session.generation(), outgoing.itemId(), outgoing.slot(), session.targetSlot(), session.deferred());
    }

    private double elapsedSeconds() {
        long now = System.nanoTime();
        long previous = lastTickNanos;
        lastTickNanos = now;
        if (previous == 0L) {
            return WeaponViewController.SECONDS_PER_TICK;
        }
        double delta = (now - previous) / 1_000_000_000.0;
        return delta > 0.0 ? Math.min(delta, WeaponViewController.MAX_FRAME_DELTA_SECONDS)
                : WeaponViewController.SECONDS_PER_TICK;
    }
}
