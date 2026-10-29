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
 * Server to client: the outcome of one firing-mode request and the mode that is now selected.
 *
 * <p>The client uses the mode only for feedback (the action bar message and diagnostics); the authoritative value is
 * the stack's {@code weapon_state} component, which Minecraft synchronises by itself. A rejection still carries the
 * mode that is in force, so the client never has to guess.</p>
 *
 * @param hand     the hand the request referred to
 * @param sequence the request's sequence number
 * @param result   the server's decision
 * @param mode     the mode selected afterwards, or {@code null} when none could be resolved
 */
public record WeaponFireModeResultPayload(InteractionHand hand, int sequence, WeaponActionResult result, FireMode mode)
        implements CustomPacketPayload {

    /** The payload identifier. */
    public static final Type<WeaponFireModeResultPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_fire_mode_result"));

    /** The codec: hand, sequence, result id, mode id (0 for none). */
    public static final StreamCodec<FriendlyByteBuf, WeaponFireModeResultPayload> CODEC =
            StreamCodec.of(WeaponFireModeResultPayload::write, WeaponFireModeResultPayload::read);

    public WeaponFireModeResultPayload {
        if (hand == null) {
            throw new IllegalArgumentException("A fire-mode result must name a hand");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("A fire-mode result sequence must be positive but is " + sequence);
        }
        if (result == null) {
            throw new IllegalArgumentException("A fire-mode result must name an outcome");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buffer, WeaponFireModeResultPayload payload) {
        buffer.writeByte(payload.hand().ordinal());
        buffer.writeVarInt(payload.sequence());
        buffer.writeVarInt(payload.result().networkId());
        buffer.writeVarInt(payload.mode() == null ? 0 : payload.mode().networkId());
    }

    private static WeaponFireModeResultPayload read(FriendlyByteBuf buffer) {
        int hand = buffer.readUnsignedByte();
        if (hand >= InteractionHand.values().length) {
            throw new IllegalArgumentException("Unknown hand id " + hand + " in a fire-mode result");
        }
        int sequence = buffer.readVarInt();
        WeaponActionResult result = WeaponActionResult.byNetworkId(buffer.readVarInt());
        int modeId = buffer.readVarInt();
        return new WeaponFireModeResultPayload(InteractionHand.values()[hand], sequence, result,
                modeId == 0 ? null : FireMode.byNetworkId(modeId));
    }
}
