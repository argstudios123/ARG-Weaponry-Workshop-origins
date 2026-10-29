package com.arg_studios.arg_weaponry.client.animation;

import net.minecraft.world.InteractionHand;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifies one first-person weapon view: a player and one hand.
 *
 * <p>This is a <em>transient view</em> key, not a weapon identity. It says "the weapon this player is
 * currently holding in this hand", which is exactly what a runtime animation controller belongs to. It
 * is deliberately not stored anywhere persistent, and the item it refers to is re-resolved from the
 * player's hand on every frame.</p>
 *
 * <p>The hand is part of the key, so the main-hand and off-hand views are independent: they never share
 * a controller, an animation, or a pose.</p>
 *
 * <p>Weapon identity comes from the item id and the stack's {@code WeaponState}, never from this key and
 * never from a persistent stack UUID.</p>
 *
 * @param playerId the id of the player whose view this is
 * @param hand     the hand the weapon is held in
 */
public record WeaponViewKey(UUID playerId, InteractionHand hand) {

    public WeaponViewKey {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(hand, "hand");
    }
}
