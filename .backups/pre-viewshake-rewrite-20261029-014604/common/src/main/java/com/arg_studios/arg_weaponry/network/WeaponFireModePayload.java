package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

/**
 * Client to server: "cycle the firing mode of the weapon in this hand".
 *
 * <p>The payload deliberately carries no mode identifier. The server resolves the weapon's supported modes from its own
 * copy of the definition, walks to the next one in the authored order and writes it to the stack's
 * {@code weapon_state} component, so a client cannot select a mode its weapon does not support.</p>
 *
 * @param hand     the hand holding the weapon
 * @param sequence a strictly increasing request number, used for replay protection
 */
public record WeaponFireModePayload(InteractionHand hand, int sequence) implements CustomPacketPayload {

    /** The payload identifier. */
    public static final Type<WeaponFireModePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_fire_mode"));

    /** The codec: one byte for the hand, a var-int for the sequence. */
    public static final StreamCodec<FriendlyByteBuf, WeaponFireModePayload> CODEC =
            StreamCodec.of(WeaponFireModePayload::write, WeaponFireModePayload::read);

    public WeaponFireModePayload {
        if (hand == null) {
            throw new IllegalArgumentException("A fire-mode request must name a hand");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("A fire-mode request sequence must be positive but is " + sequence);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buffer, WeaponFireModePayload payload) {
        buffer.writeByte(payload.hand().ordinal());
        buffer.writeVarInt(payload.sequence());
    }

    private static WeaponFireModePayload read(FriendlyByteBuf buffer) {
        int hand = buffer.readUnsignedByte();
        if (hand >= InteractionHand.values().length) {
            throw new IllegalArgumentException("Unknown hand id " + hand + " in a fire-mode request");
        }
        return new WeaponFireModePayload(InteractionHand.values()[hand], buffer.readVarInt());
    }
}
