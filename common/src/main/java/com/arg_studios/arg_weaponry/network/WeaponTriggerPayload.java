package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

/**
 * Client to server: the trigger of one hand was pressed or released.
 *
 * <p>Introduced by Milestone 10Q for automatic and burst fire. A semiautomatic weapon needs no trigger state - one
 * {@link WeaponFirePayload} is one press - but a held button cannot produce one packet per shot, and letting the client
 * send one packet per shot would hand it the rate. The client therefore reports only the two edges and a sequence
 * number for the press; the server decides the weapon, the mode, the cadence, the hits and the ammunition.</p>
 *
 * <h2>One sequence per press</h2>
 * <p>The press carries one sequence number that stays the same for every heartbeat of that press. The server treats a
 * repeat of the running number as a heartbeat (it refreshes the session lease) and only a <em>higher</em> number as a
 * new press, so a held trigger can never start a second burst after the first one finished. A release reuses the same
 * number.</p>
 *
 * <p>Nothing here names a weapon, a mode, a rate, a damage or a round: those are resolved by the server from its own
 * copy of the definition and the held stack.</p>
 *
 * @param hand     the hand holding the weapon
 * @param pressed  {@code true} for a press or heartbeat, {@code false} for a release
 * @param sequence the press's sequence number, strictly increasing between presses
 */
public record WeaponTriggerPayload(InteractionHand hand, boolean pressed, int sequence)
        implements CustomPacketPayload {

    /** The payload identifier. */
    public static final Type<WeaponTriggerPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_trigger"));

    /** The codec: one byte for the hand, one for the edge, a var-int for the sequence. */
    public static final StreamCodec<FriendlyByteBuf, WeaponTriggerPayload> CODEC =
            StreamCodec.of(WeaponTriggerPayload::write, WeaponTriggerPayload::read);

    public WeaponTriggerPayload {
        if (hand == null) {
            throw new IllegalArgumentException("A trigger payload must name a hand");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("A trigger sequence must be positive but is " + sequence);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buffer, WeaponTriggerPayload payload) {
        buffer.writeByte(payload.hand().ordinal());
        buffer.writeBoolean(payload.pressed());
        buffer.writeVarInt(payload.sequence());
    }

    private static WeaponTriggerPayload read(FriendlyByteBuf buffer) {
        int hand = buffer.readUnsignedByte();
        if (hand >= InteractionHand.values().length) {
            throw new IllegalArgumentException("Unknown hand id " + hand + " in a trigger payload");
        }
        return new WeaponTriggerPayload(InteractionHand.values()[hand], buffer.readBoolean(), buffer.readVarInt());
    }
}
