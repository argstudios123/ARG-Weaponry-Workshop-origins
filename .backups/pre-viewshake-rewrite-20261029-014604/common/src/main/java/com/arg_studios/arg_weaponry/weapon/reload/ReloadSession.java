package com.arg_studios.arg_weaponry.weapon.reload;

import net.minecraft.world.InteractionHand;

import java.util.UUID;

/**
 * One active reload, as the server tracks it.
 *
 * <p>This is transient runtime state and nothing else: it is never written to {@code WeaponState}, never
 * persisted, and never sent to a client as authority. The persistent magazine stays exactly what milestone
 * 10K made it — an immutable {@code WeaponState} in a data component that only changes when the transfer is
 * actually committed.</p>
 *
 * <h2>Why the slot and the item id are stored</h2>
 * <p>A reload must finish on the same weapon it started on. Comparing the hand alone is not enough: the player
 * can drop the weapon, pick up a different stack of the same item or swap hotbar slots. The session therefore
 * remembers the inventory slot and the registered item id, and completion refuses when either changed.</p>
 *
 * <h2>Timing</h2>
 * <p>Times are server game ticks, the same clock firing cadence uses. {@link #completeTick()} is exclusive in
 * the sense that the reload completes when {@code gameTime >= completeTick}, which makes a duration of
 * {@code n} ticks last exactly {@code n} ticks.</p>
 *
 * @param playerId       the reloading player's UUID
 * @param hand           the hand holding the weapon
 * @param slot           the inventory slot the weapon occupied at reload start
 * @param weaponItemId   the registered item id of the weapon stack
 * @param sequence       the accepted request's sequence number, for anti-replay and feedback correlation
 * @param startTick      the server tick the reload started at
 * @param completeTick   the server tick the reload completes at
 * @param animation      the declared reload animation name, or {@code null} when the definition declares none
 * @param durationTicks  the reload duration in ticks, at least one
 * @param startingAmmo   the magazine count at reload start, the value of {@code reload_count}
 * @param emptyMagazine  whether the magazine was empty at reload start, which selects the empty-reload sound
 * @param soundKey       the {@code sounds} key the client should play ({@code reload_empty} or {@code reload})
 */
public record ReloadSession(UUID playerId, InteractionHand hand, int slot, String weaponItemId, int sequence,
                            long startTick, long completeTick, String animation, int durationTicks,
                            int startingAmmo, boolean emptyMagazine, String soundKey) {

    public ReloadSession {
        if (playerId == null || hand == null || weaponItemId == null) {
            throw new IllegalArgumentException("A reload session needs a player, a hand and a weapon");
        }
        if (slot < 0) {
            throw new IllegalArgumentException("A reload session needs a non-negative inventory slot");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("A reload session needs a positive request sequence");
        }
        if (durationTicks < 1) {
            throw new IllegalArgumentException("A reload session must last at least one tick");
        }
        if (completeTick < startTick) {
            throw new IllegalArgumentException("A reload cannot complete before it started");
        }
    }

    /**
     * @param gameTime the current server game time in ticks
     * @return whether the reload duration has elapsed
     */
    public boolean isComplete(long gameTime) {
        return gameTime >= completeTick;
    }
}
