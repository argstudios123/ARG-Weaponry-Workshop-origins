package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

/**
 * Server to client: the outcome of one inspection request.
 *
 * <p>The clip name and the duration are the server's choice, taken from the content pack's {@code inspect_anims}; the
 * client uses them only to play the clip for the duration the server is running. A rejection carries an empty clip name
 * and a zero duration, which is how the client knows it must not animate or sound anything.</p>
 *
 * @param hand          the hand the request referred to
 * @param sequence      the request's sequence number
 * @param result        the server's decision
 * @param animation     the inspection clip to play, or an empty string when none applies
 * @param durationTicks the server-side inspection duration in ticks, {@code 0} for a rejection
 * @param emptyMagazine whether the magazine was empty, which selected the clip and the sound
 * @param aimingBlocked the definition's {@code aiming.no_aiming_on_inspect}, forwarded so a future aiming system can
 *                      honour it without re-reading the definition on the client
 */
public record WeaponInspectResultPayload(InteractionHand hand, int sequence, WeaponActionResult result,
                                         String animation, int durationTicks, boolean emptyMagazine,
                                         boolean aimingBlocked) implements CustomPacketPayload {

    /** Longest animation name the codec will read, matching a content-pack animation key. */
    public static final int MAX_ANIMATION_LENGTH = 128;

    /** The payload identifier. */
    public static final Type<WeaponInspectResultPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_inspect_result"));

    /** The codec: hand, sequence, result id, animation name, duration, two flags. */
    public static final StreamCodec<FriendlyByteBuf, WeaponInspectResultPayload> CODEC =
            StreamCodec.of(WeaponInspectResultPayload::write, WeaponInspectResultPayload::read);

    public WeaponInspectResultPayload {
        if (hand == null) {
            throw new IllegalArgumentException("An inspection result must name a hand");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("An inspection result sequence must be positive but is " + sequence);
        }
        if (result == null) {
            throw new IllegalArgumentException("An inspection result must name an outcome");
        }
        animation = animation == null ? "" : animation;
        if (durationTicks < 0) {
            throw new IllegalArgumentException("An inspection duration must not be negative");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buffer, WeaponInspectResultPayload payload) {
        buffer.writeByte(payload.hand().ordinal());
        buffer.writeVarInt(payload.sequence());
        buffer.writeVarInt(payload.result().networkId());
        buffer.writeUtf(payload.animation(), MAX_ANIMATION_LENGTH);
        buffer.writeVarInt(payload.durationTicks());
        int flags = (payload.emptyMagazine() ? 1 : 0) | (payload.aimingBlocked() ? 2 : 0);
        buffer.writeByte(flags);
    }

    private static WeaponInspectResultPayload read(FriendlyByteBuf buffer) {
        int hand = buffer.readUnsignedByte();
        if (hand >= InteractionHand.values().length) {
            throw new IllegalArgumentException("Unknown hand id " + hand + " in an inspection result");
        }
        int sequence = buffer.readVarInt();
        WeaponActionResult result = WeaponActionResult.byNetworkId(buffer.readVarInt());
        String animation = buffer.readUtf(MAX_ANIMATION_LENGTH);
        int durationTicks = buffer.readVarInt();
        int flags = buffer.readUnsignedByte();
        return new WeaponInspectResultPayload(InteractionHand.values()[hand], sequence, result, animation,
                durationTicks, (flags & 1) != 0, (flags & 2) != 0);
    }
}
