package com.arg_studios.arg_weaponry.weapon.equip;

import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.client.animation.WeaponHandoffManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * The one entry point the rest of the mod talks to for the draw/holster feature.
 *
 * <p>Everything outside this package reaches the feature through {@link #isEquipBusy(Player)} - the single action gate firing,
 * reloading, inspection, fire-mode switching and aiming consult - and, on the client, the tick and input entry points. That
 * surface is unchanged on purpose: the lifecycle itself was re-implemented in three focused classes, and none of the weapon
 * systems that merely ask "is the weapon busy?" had to change.</p>
 *
 * <h2>The three classes behind this facade</h2>
 * <ul>
 *   <li>{@link ClientEquipController} - the prediction: which weapon became held, the deferred slot handoff, and the clip. It
 *   owns at most one transition per player, so a stale completion cannot outlive a newer switch.</li>
 *   <li>{@link ServerEquipAuthority} - the authority: the tick windows that gate gameplay actions, the persistent first-draw
 *   flag, the request guard and the sounds other players hear.</li>
 *   <li>{@link EquipPhase} - the immutable value both sides share, and the only place the transition rules (window elapsed,
 *   safety deadline, expected completion versus hard interrupt) are written down. It is pure, so those rules are unit tested
 *   without a game.</li>
 * </ul>
 *
 * <h2>Per weapon, not global</h2>
 * <p>{@link #handles(WeaponDefinition)} is the only definition of "this feature owns that weapon": a weapon that declares no
 * {@code draw_anims} and no {@code holster_anims} is never handled, so nothing about it changes - including its current draw
 * and holster sounds, which keep coming from the legacy held-item lifecycle. The same is true for every weapon while
 * {@code enableDrawHolster} is false.</p>
 *
 * <h2>What is purely visual and what is gameplay</h2>
 * <p>The clips, the first-person pose and the local sounds are client-only. What affects gameplay is the window itself, and
 * that is timed on both sides from the same authored duration: the client predicts it so input is refused immediately, and the
 * server enforces it so a client that skips a transition still cannot fire or reload. No packet carries an animation, a
 * duration or an item, so a client cannot choose its own timing.</p>
 */
public final class DrawHolsterManager {

    /** Minimum server ticks between two accepted equip payloads from the same player. */
    public static final int REQUEST_COOLDOWN_TICKS = EquipRequestGuard.COOLDOWN_TICKS;

    private DrawHolsterManager() {
    }

    // ------------------------------------------------------------------ queries

    /**
     * The single gate the existing action paths call: firing, reloading, inspecting, switching the firing mode and entering
     * aim-down-sights are all refused while this returns {@code true}.
     *
     * <p>It is side-aware: on the client it reports the predicted window, on the server the authoritative one. A player the
     * feature does not know is never busy, so a weapon it does not handle behaves exactly as before.</p>
     *
     * @param player the player to test, may be {@code null}
     * @return whether a draw or holster window is currently running for that player
     */
    public static boolean isEquipBusy(Player player) {
        if (player == null) {
            return false;
        }
        return player.level().isClientSide()
                ? WeaponHandoffManager.instance().isTransitioning()
                : ServerEquipAuthority.instance().isBusy(player);
    }

    /**
     * @param player the player to inspect, may be {@code null}
     * @return the current phase of that side, never {@code null}
     */
    public static EquipPhase phase(Player player) {
        if (player == null) {
            return EquipPhase.IDLE;
        }
        return player.level().isClientSide()
                ? ClientEquipController.instance().phase(player)
                : ServerEquipAuthority.instance().phase(player);
    }

    /**
     * @param definition the weapon definition, may be {@code null}
     * @return whether the feature owns this weapon's draw/holster events at all: it is enabled and the weapon declares at
     *         least one draw or holster clip
     */
    public static boolean handles(WeaponDefinition definition) {
        return DrawHolsterConfig.get().enableDrawHolster() && EquipAnimationResolver.handles(definition);
    }

    /**
     * @param stack the held stack, may be {@code null}
     * @return whether the feature owns this stack's draw/holster events
     */
    public static boolean handles(ItemStack stack) {
        return stack != null && stack.getItem() instanceof WeaponItem weapon && handles(weapon.definition());
    }

    /**
     * The legacy held-item lifecycle keeps its behaviour unless the feature owns the event.
     *
     * <p>The event is owned when the feature is enabled and <em>either</em> side of the change declares clips. Owning it means
     * this feature plays both sounds - the holster of the old weapon and the draw of the new one - so a hand-off between a
     * handled and an unhandled weapon cannot lose a sound, and a hand-off between two unhandled weapons keeps playing through
     * {@code WeaponHeldLifecycle} exactly as it always did.</p>
     *
     * @param previous the weapon that stopped being held, may be {@code null}
     * @param current  the weapon that became held, may be {@code null}
     * @return whether the legacy lifecycle should stay out of this change
     */
    public static boolean ownsLifecycle(WeaponDefinition previous, WeaponDefinition current) {
        return handles(previous) || handles(current);
    }

    // ------------------------------------------------------------------ tick and input

    /**
     * Advances every player's authoritative equip state. Runs on the server thread once per tick.
     *
     * @param server the running server, may be {@code null}
     */
    public static void serverTick(MinecraftServer server) {
        ServerEquipAuthority.instance().tick(server);
    }

    /**
     * Kept so both loaders keep one client-tick entry point.
     *
     * <p>The handoff subsystem advances from the shared weapon tick ({@code WeaponViewControllers#tickHeldWeapons}), which is
     * the single owner of playback time, so there is nothing left to advance here. The predicted equip state machine this
     * method used to drive is retired; see {@code WeaponHandoffManager}.</p>
     *
     * @param minecraft the client, may be {@code null}
     */
    public static void clientTick(Minecraft minecraft) {
        // Intentionally empty: the handoff subsystem is ticked by the shared weapon tick.
    }

    /**
     * Intercepts one player-initiated switch: start, re-target or cancel the holster.
     *
     * @param player     the local player
     * @param targetSlot the slot the player asked for
     * @return whether the input was consumed, i.e. whether vanilla must not apply the switch
     */
    public static boolean requestClientHolster(LocalPlayer player, int targetSlot) {
        return WeaponHandoffManager.instance().requestSwitch(player, targetSlot);
    }

    /**
     * @param player the local player, may be {@code null}
     * @return the slot a scroll should be computed from: the pending target while a switch waits, otherwise the selected
     *         slot
     */
    public static int scrollBaseSlot(LocalPlayer player) {
        return WeaponHandoffManager.instance().scrollBaseSlot(player);
    }

    /**
     * Requirement of the protocol: one holster request from the client, validated entirely against the server's own state.
     *
     * @param player     the requesting player
     * @param targetSlot the slot the client is switching to
     * @param sequence   the request's sequence number
     */
    public static void requestHolster(ServerPlayer player, int targetSlot, int sequence) {
        ServerEquipAuthority.instance().requestHolster(player, targetSlot, sequence);
    }

    /**
     * Abandons a delayed switch the client cancelled.
     *
     * @param player   the requesting player
     * @param sequence the request's sequence number
     */
    public static void cancelHolster(ServerPlayer player, int sequence) {
        ServerEquipAuthority.instance().cancelHolster(player, sequence);
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Forgets one player's authoritative state and the held baseline the server tracks.
     *
     * <p>Server side only: both loaders call it from their server disconnect handlers, and touching the client controllers from
     * a dedicated server would resolve client-only classes. The client half is {@link #clearClient()}, which the client calls
     * when it leaves the world.</p>
     *
     * @param playerId the player, may be {@code null}
     */
    public static void clear(UUID playerId) {
        ServerEquipAuthority.instance().clear(playerId);
    }

    /**
     * Forgets every player's authoritative state; used when the server starts or stops.
     *
     * <p>Server side only, for the same reason as {@link #clear(UUID)}.</p>
     */
    public static void clearAll() {
        ServerEquipAuthority.instance().clearAll();
    }

    /**
     * Forgets the local client's state; used when the client leaves a world.
     *
     * <p>The first-draw guard is reset with it: a rejoin is a new session, so a weapon the server has not marked as drawn
     * plays its first-draw clip again.</p>
     */
    public static void clearClient() {
        ClientEquipController.instance().clear();
        WeaponHandoffManager.instance().reset();
    }
}
