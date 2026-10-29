package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

/**
 * Server to client: one shot was accepted and has been executed.
 *
 * <p>Sent only for accepted shots, so the client can drive firing feedback from a server decision instead of
 * from its own click. A rejected request produces no payload at all: there is nothing to undo on the client,
 * because the client never predicts a shot. Ammunition is not carried here either — the changed
 * {@code weapon_state} component synchronises through Minecraft's own component mechanism.</p>
 *
 * <p>The payload cannot make the client do anything it could not already do: it names the hand, reports the shot id the
 * server assigned, and describes the hit the server already applied. The client resolves the animation and the hit
 * sounds from its own copy of the weapon definition.</p>
 *
 * <h2>Hit facts (Milestone 10R)</h2>
 * <p>{@code headshot} and {@code killed} are computed on the server from the real ray/entity result and the real damage
 * application ({@code HitFacts}), never from anything the client sent. They exist so the shooter can play the
 * <em>shooter-only</em> hit feedback the weapon declares ({@code headshot}, {@code entity_hit},
 * {@code entity_wasted}) without a second packet: other players do not hear those sounds, which is exactly the
 * multiplayer behaviour the audio engine documents.</p>
 *
 * @param hand     the hand the accepted shot came from
 * @param sequence the server-assigned shot id, monotonically increasing per player and hand
 * @param hit      whether the shot hit an entity at all
 * @param headshot whether the hit landed in the entity's head region
 * @param killed   whether the hit killed the entity
 */
public record WeaponFireResultPayload(InteractionHand hand, int sequence, boolean hit, boolean headshot, boolean killed)
        implements CustomPacketPayload {

    /** The payload identifier. */
    public static final Type<WeaponFireResultPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_fire_result"));

    /** The codec: hand, shot id, then three flags packed into one byte. */
    public static final StreamCodec<FriendlyByteBuf, WeaponFireResultPayload> CODEC =
            StreamCodec.of(WeaponFireResultPayload::write, WeaponFireResultPayload::read);

    public WeaponFireResultPayload {
        if (hand == null) {
            throw new IllegalArgumentException("A firing result must name a hand");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("A firing result sequence must be positive but is " + sequence);
        }
        if (!hit && (headshot || killed)) {
            throw new IllegalArgumentException("A shot that hit nothing cannot be a headshot or a kill");
        }
    }

    /**
     * @param hand     the hand
     * @param sequence the shot id
     * @return a result for a shot that hit nothing
     */
    public static WeaponFireResultPayload miss(InteractionHand hand, int sequence) {
        return new WeaponFireResultPayload(hand, sequence, false, false, false);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static void write(FriendlyByteBuf buffer, WeaponFireResultPayload payload) {
        buffer.writeByte(payload.hand().ordinal());
        buffer.writeVarInt(payload.sequence());
        int flags = (payload.hit() ? 1 : 0) | (payload.headshot() ? 2 : 0) | (payload.killed() ? 4 : 0);
        buffer.writeByte(flags);
    }

    private static WeaponFireResultPayload read(FriendlyByteBuf buffer) {
        int hand = buffer.readUnsignedByte();
        if (hand >= InteractionHand.values().length) {
            throw new IllegalArgumentException("Unknown hand id " + hand + " in a firing result");
        }
        int sequence = buffer.readVarInt();
        int flags = buffer.readUnsignedByte();
        return new WeaponFireResultPayload(InteractionHand.values()[hand], sequence, (flags & 1) != 0,
                (flags & 2) != 0, (flags & 4) != 0);
    }
}
