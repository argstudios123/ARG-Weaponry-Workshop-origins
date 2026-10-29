package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

/**
 * Client to server: a request to fire the weapon held in one hand.
 *
 * <p>The payload carries an intent and nothing else — which hand, and a sequence number the server uses to
 * reject replays. It deliberately contains no weapon identifier, damage, cadence, ammunition or target: the
 * server resolves all of that from its own player, held stack and registry, so a modified client can only ask
 * for a shot, never describe one.</p>
 *
 * <p>Two bytes on the wire for a first shot: the hand and the sequence.</p>
 *
 * @param hand     the hand the player is firing from
 * @param sequence a per-connection, monotonically increasing request counter starting at 1
 */
public record WeaponFirePayload(InteractionHand hand, int sequence) implements CustomPacketPayload {

    /** The payload identifier. */
    public static final Type<WeaponFirePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_fire"));

    /** The codec: one byte for the hand, a var-int for the sequence. */
    public static final StreamCodec<FriendlyByteBuf, WeaponFirePayload> CODEC =
            StreamCodec.of(WeaponFirePayload::write, WeaponFirePayload::read);

    public WeaponFirePayload {
        if (hand == null) {
            throw new IllegalArgumentException("A firing request must name a hand");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("A firing request sequence must be positive but is " + sequence);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buffer, WeaponFirePayload payload) {
        buffer.writeByte(payload.hand().ordinal());
        buffer.writeVarInt(payload.sequence());
    }

    private static WeaponFirePayload read(FriendlyByteBuf buffer) {
        int hand = buffer.readUnsignedByte();
        if (hand >= InteractionHand.values().length) {
            throw new IllegalArgumentException("Unknown hand id " + hand + " in a firing request");
        }
        return new WeaponFirePayload(InteractionHand.values()[hand], buffer.readVarInt());
    }
}
