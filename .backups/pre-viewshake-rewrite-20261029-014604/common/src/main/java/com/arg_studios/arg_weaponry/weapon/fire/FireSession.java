package com.arg_studios.arg_weaponry.weapon.fire;

import com.arg_studios.arg_weaponry.weapon.state.FireMode;
import net.minecraft.world.InteractionHand;

import java.util.UUID;

/**
 * One server-owned trigger session: the authoritative record of "this player is holding the trigger of this weapon".
 *
 * <p>Created by {@link WeaponFireSessions#start} when a trigger press is accepted, advanced by
 * {@link WeaponFireSessions#serverTick}, and removed when the trigger is released, the burst completes (unless
 * {@code continuous_burst} is on), the lease expires, or any validation stops applying.</p>
 *
 * <h2>What each field is for</h2>
 * <ul>
 *   <li>{@code itemId} and {@code slot} identify the weapon the session belongs to, so switching weapons - or moving
 *   the stack to another hotbar slot - ends the session instead of continuing it with a different weapon;</li>
 *   <li>{@code mode} is the mode that was resolved and validated when the session started, so a mid-session file
 *   change cannot silently turn a burst into automatic fire;</li>
 *   <li>{@code triggerSequence} is the press's sequence number: heartbeats of the same press repeat it, and a
 *   <em>new</em> press must exceed it. That is what keeps a held trigger from starting a second burst after the first
 *   one finished;</li>
 *   <li>{@code shotsRemaining} counts the shots left in the current burst;</li>
 *   <li>{@code nextShotTick} and {@code nextBurstTick} are the cadence gates, in server game ticks;</li>
 *   <li>{@code expiresAtTick} is the lease, refreshed by every heartbeat, so a lost release message cannot leave a
 *   weapon firing forever.</li>
 * </ul>
 *
 * <p>The record is immutable; the scheduler replaces instances with {@code with...} copies. Nothing here holds a
 * player, a level, a stack or a mutable {@code ItemStack}: the entry is keyed by the player's UUID and the hand, and
 * every tick re-resolves the live state from the server.</p>
 *
 * @param playerId        the player holding the trigger
 * @param hand            the hand the weapon is in
 * @param itemId          the registered identifier of that weapon when the session started
 * @param slot            the inventory slot of that hand at session start
 * @param mode            the resolved firing mode: never {@link FireMode#SINGLE}
 * @param burstCount      the validated {@code burst_count} of the definition
 * @param continuousBurst the validated {@code continuous_burst} flag
 * @param cooldownTicks   ticks between two shots of the same weapon, derived from {@code rpm}
 * @param burstGapTicks   ticks between two bursts, derived from {@code time_between_bursts}
 * @param triggerSequence the sequence number of the trigger press that started the session
 * @param shotsRemaining  shots still to fire in the current burst
 * @param shotsFired      shots this session has fired, for diagnostics
 * @param nextShotTick    earliest game tick at which the next shot may be fired
 * @param nextBurstTick   earliest game tick at which the next burst of a continuous session may begin
 * @param expiresAtTick   game tick after which the session is abandoned (a lost release or heartbeat)
 */
public record FireSession(UUID playerId, InteractionHand hand, String itemId, int slot, FireMode mode,
                          int burstCount, boolean continuousBurst, int cooldownTicks, int burstGapTicks,
                          int triggerSequence, int shotsRemaining, int shotsFired, long nextShotTick,
                          long nextBurstTick, long expiresAtTick) {

    public FireSession {
        if (playerId == null || hand == null || itemId == null || mode == null) {
            throw new IllegalArgumentException("A firing session needs a player, a hand, a weapon and a mode");
        }
        if (mode == FireMode.SINGLE) {
            throw new IllegalArgumentException("A single-shot weapon needs no firing session");
        }
    }

    /** @return the map key of this session */
    public Key key() {
        return new Key(playerId, hand);
    }

    /**
     * @param now             the current game tick
     * @param expiresAtTick   the new lease end
     * @param nextShotTick    the next shot gate
     * @param nextBurstTick   the next burst gate
     * @param shotsRemaining  the remaining shots of the current burst
     * @param shotsFired      the new fired counter
     * @return a copy with the scheduler's timing and counters updated
     */
    public FireSession advanced(long now, long expiresAtTick, long nextShotTick, long nextBurstTick,
                                int shotsRemaining, int shotsFired) {
        return new FireSession(playerId, hand, itemId, slot, mode, burstCount, continuousBurst, cooldownTicks,
                burstGapTicks, triggerSequence, shotsRemaining, shotsFired, nextShotTick, nextBurstTick,
                expiresAtTick);
    }

    /**
     * @param newRemaining the shots left in the current burst
     * @return a copy with a different remaining count
     */
    public FireSession withShotsRemaining(int newRemaining) {
        return advanced(nextShotTick, expiresAtTick, nextShotTick, nextBurstTick, newRemaining, shotsFired);
    }

    /**
     * @param newExpiry the new lease end
     * @return a copy with a refreshed lease
     */
    public FireSession withExpiry(long newExpiry) {
        return advanced(nextShotTick, newExpiry, nextShotTick, nextBurstTick, shotsRemaining, shotsFired);
    }

    /** The map key of a session: one session per player and hand. */
    public record Key(UUID playerId, InteractionHand hand) {
    }
}
