package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult;
import com.arg_studios.arg_weaponry.weapon.state.FireMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

/**
 * Server to client: the state of one trigger session.
 *
 * <p>Sent when a press starts a session, when a session ends (release, expiry, burst complete, refusal) and when a
 * heartbeat is answered. It cannot make the client do anything: it names the hand, echoes the press sequence, reports
 * the server's decision, the mode that was resolved and whether the session is still running.</p>
 *
 * <p>Accepted <em>shots</em> travel separately, as {@link WeaponFireResultPayload}; this payload only describes the
 * trigger lifecycle. Ammunition is never carried here - the {@code weapon_state} component already synchronises
 * through Minecraft's own component mechanism.</p>
 *
 * @param hand     the hand the trigger belonged to
 * @param sequence the press's sequence number
 * @param result   the server's decision about the session
 * @param mode     the resolved firing mode, or {@code null} when no mode applied
 * @param active   whether a session is running after this payload
 */
public record WeaponFireSessionPayload(InteractionHand hand, int sequence, WeaponActionResult result, FireMode mode,
                                       boolean active) implements CustomPacketPayload {

    /** The payload identifier. */
    public static final Type<WeaponFireSessionPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_fire_session"));

    /** The codec: hand, sequence, result id, mode id (0 for none), active flag. */
    public static final StreamCodec<FriendlyByteBuf, WeaponFireSessionPayload> CODEC =
            StreamCodec.of(WeaponFireSessionPayload::write, WeaponFireSessionPayload::read);

    public WeaponFireSessionPayload {
        if (hand == null) {
            throw new IllegalArgumentException("A firing session payload must name a hand");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("A firing session sequence must be positive but is " + sequence);
        }
        if (result == null) {
            throw new IllegalArgumentException("A firing session payload must name an outcome");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buffer, WeaponFireSessionPayload payload) {
        buffer.writeByte(payload.hand().ordinal());
        buffer.writeVarInt(payload.sequence());
        buffer.writeVarInt(payload.result().networkId());
        buffer.writeVarInt(payload.mode() == null ? 0 : payload.mode().networkId());
        buffer.writeBoolean(payload.active());
    }

    private static WeaponFireSessionPayload read(FriendlyByteBuf buffer) {
        int hand = buffer.readUnsignedByte();
        if (hand >= InteractionHand.values().length) {
            throw new IllegalArgumentException("Unknown hand id " + hand + " in a firing session payload");
        }
        int sequence = buffer.readVarInt();
        WeaponActionResult result = WeaponActionResult.byNetworkId(buffer.readVarInt());
        int modeId = buffer.readVarInt();
        FireMode mode = modeId == 0 ? null : FireMode.byNetworkId(modeId);
        return new WeaponFireSessionPayload(InteractionHand.values()[hand], sequence, result, mode,
                buffer.readBoolean());
    }
}
