package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

/**
 * Client to server: a request to reload the weapon held in one hand.
 *
 * <p>Like the firing request, this payload carries an intent and nothing else — which hand, and a sequence
 * number the server uses to reject replays. It deliberately contains no ammunition count, no item id, no
 * magazine capacity, no animation name and no duration: the server resolves all of that from its own player,
 * inventory, registries and weapon definitions.</p>
 *
 * <p>Two bytes on the wire: the hand and the sequence.</p>
 *
 * @param hand     the hand the reload is requested for
 * @param sequence a per-connection, monotonically increasing request counter starting at 1
 */
public record WeaponReloadPayload(InteractionHand hand, int sequence) implements CustomPacketPayload {

    /** The payload identifier. */
    public static final Type<WeaponReloadPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_reload"));

    /** The codec: one byte for the hand, a var-int for the sequence. */
    public static final StreamCodec<FriendlyByteBuf, WeaponReloadPayload> CODEC =
            StreamCodec.of(WeaponReloadPayload::write, WeaponReloadPayload::read);

    public WeaponReloadPayload {
        if (hand == null) {
            throw new IllegalArgumentException("A reload request must name a hand");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("A reload request sequence must be positive but is " + sequence);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buffer, WeaponReloadPayload payload) {
        buffer.writeByte(payload.hand().ordinal());
        buffer.writeVarInt(payload.sequence());
    }

    private static WeaponReloadPayload read(FriendlyByteBuf buffer) {
        int hand = buffer.readUnsignedByte();
        if (hand >= InteractionHand.values().length) {
            throw new IllegalArgumentException("Unknown hand id " + hand + " in a reload request");
        }
        return new WeaponReloadPayload(InteractionHand.values()[hand], buffer.readVarInt());
    }
}
