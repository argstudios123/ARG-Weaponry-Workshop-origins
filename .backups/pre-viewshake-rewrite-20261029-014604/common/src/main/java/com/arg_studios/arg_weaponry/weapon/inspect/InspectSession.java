package com.arg_studios.arg_weaponry.weapon.inspect;

import net.minecraft.world.InteractionHand;

import java.util.UUID;

/**
 * One accepted inspection: which weapon, which clip, and when the server considers it finished.
 *
 * <p>Same shape and same purpose as a reload session: the server owns the duration, the client is told the clip name and
 * that duration, and everything is re-validated while it runs so an inspection can never outlive the weapon, the player
 * or the world it belongs to.</p>
 *
 * @param playerId        the inspecting player
 * @param hand            the hand the weapon is in
 * @param itemId          the registered identifier of the weapon at inspect start
 * @param slot            the inventory slot of that hand at inspect start
 * @param sequence        the request sequence number that started it
 * @param animation       the declared animation name being played
 * @param durationTicks   the configured duration, in server ticks
 * @param emptyMagazine   whether the magazine was empty, which selected the clip and the sound
 * @param startedAtTick   the game tick the inspection started on
 * @param endsAtTick      the game tick it ends on, calculated once
 */
public record InspectSession(UUID playerId, InteractionHand hand, String itemId, int slot, int sequence,
                             String animation, int durationTicks, boolean emptyMagazine, long startedAtTick,
                             long endsAtTick) {

    public InspectSession {
        if (playerId == null || hand == null || itemId == null || animation == null) {
            throw new IllegalArgumentException("An inspection session needs a player, a hand, a weapon and a clip");
        }
        if (durationTicks <= 0) {
            throw new IllegalArgumentException("An inspection session needs a positive duration");
        }
    }

    /** @return the map key of this session */
    public Key key() {
        return new Key(playerId, hand);
    }

    /**
     * @param now the current game tick
     * @return whether the configured duration has elapsed
     */
    public boolean isFinished(long now) {
        return now >= endsAtTick;
    }

    /** The map key of an inspection session: one session per player and hand. */
    public record Key(UUID playerId, InteractionHand hand) {
    }
}
