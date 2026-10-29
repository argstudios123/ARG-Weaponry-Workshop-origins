package com.arg_studios.arg_weaponry.weapon.equip;

import com.arg_studios.arg_weaponry.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: one equip event the server cannot observe by itself.
 *
 * <p>The client delays a hotbar switch until the holster clip ends, so for the length of that clip the server still sees the
 * old weapon held - correctly, but without any way to know that a switch is in progress. This payload tells it that one
 * started ({@link #ACTION_START_HOLSTER}) or that the player changed their mind ({@link #ACTION_CANCEL_HOLSTER}), which is
 * all the server needs in order to block weapon actions for the window and to let other players hear the holster
 * sound.</p>
 *
 * <p>It carries no animation name, no duration, no sound and no item: the server resolves the held stack, the definition and
 * the magazine state from its own copy and decides everything itself. {@code targetSlot} is used for exactly one thing -
 * recognising the arrival of the delayed switch as a normal completion instead of a forced change - and never to choose an
 * effect (see {@link DrawHolsterManager#requestHolster}).</p>
 *
 * @param action     {@link #ACTION_START_HOLSTER} or {@link #ACTION_CANCEL_HOLSTER}
 * @param targetSlot the hotbar slot the client is switching to, or {@link EquipPhase#NO_SLOT} for a cancel
 * @param sequence   a strictly increasing request number, used for replay protection and rate limiting
 */
public record EquipActionPayload(int action, int targetSlot, int sequence) implements CustomPacketPayload {

    /** The player started a delayed switch away from the held weapon. */
    public static final int ACTION_START_HOLSTER = 0;

    /** The player returned to the slot they were already on, so the delayed switch is abandoned. */
    public static final int ACTION_CANCEL_HOLSTER = 1;

    /** The payload identifier. */
    public static final Type<EquipActionPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "equip_action"));

    /** The codec: two bytes and a var-int. */
    public static final StreamCodec<FriendlyByteBuf, EquipActionPayload> CODEC =
            StreamCodec.of(EquipActionPayload::write, EquipActionPayload::read);

    public EquipActionPayload {
        if (action != ACTION_START_HOLSTER && action != ACTION_CANCEL_HOLSTER) {
            throw new IllegalArgumentException("Unknown equip action id " + action);
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("An equip request sequence must be positive but is " + sequence);
        }
        if (action == ACTION_START_HOLSTER && targetSlot < 0) {
            throw new IllegalArgumentException("A holster request must name a target slot");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buffer, EquipActionPayload payload) {
        buffer.writeByte(payload.action());
        buffer.writeByte(payload.targetSlot + 1);
        buffer.writeVarInt(payload.sequence());
    }

    private static EquipActionPayload read(FriendlyByteBuf buffer) {
        int action = buffer.readUnsignedByte();
        int targetSlot = buffer.readUnsignedByte() - 1;
        return new EquipActionPayload(action, targetSlot, buffer.readVarInt());
    }
}
