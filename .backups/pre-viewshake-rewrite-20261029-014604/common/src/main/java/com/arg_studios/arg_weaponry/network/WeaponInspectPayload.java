package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

/**
 * Client to server: "inspect the weapon in this hand".
 *
 * <p>Carries a hand and a request sequence and nothing else - no animation name, no duration, no sound. The server selects
 * the clip from its own copy of the definition and the stack's authoritative ammunition, and answers with
 * {@link WeaponInspectResultPayload}.</p>
 *
 * @param hand     the hand holding the weapon
 * @param sequence a strictly increasing request number, used for replay protection
 */
public record WeaponInspectPayload(InteractionHand hand, int sequence) implements CustomPacketPayload {

    /** The payload identifier. */
    public static final Type<WeaponInspectPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_inspect"));

    /** The codec: one byte for the hand, a var-int for the sequence. */
    public static final StreamCodec<FriendlyByteBuf, WeaponInspectPayload> CODEC =
            StreamCodec.of(WeaponInspectPayload::write, WeaponInspectPayload::read);

    public WeaponInspectPayload {
        if (hand == null) {
            throw new IllegalArgumentException("An inspection request must name a hand");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("An inspection sequence must be positive but is " + sequence);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buffer, WeaponInspectPayload payload) {
        buffer.writeByte(payload.hand().ordinal());
        buffer.writeVarInt(payload.sequence());
    }

    private static WeaponInspectPayload read(FriendlyByteBuf buffer) {
        int hand = buffer.readUnsignedByte();
        if (hand >= InteractionHand.values().length) {
            throw new IllegalArgumentException("Unknown hand id " + hand + " in an inspection request");
        }
        return new WeaponInspectPayload(InteractionHand.values()[hand], buffer.readVarInt());
    }
}
