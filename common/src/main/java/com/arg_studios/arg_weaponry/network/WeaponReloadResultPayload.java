package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult;
import com.arg_studios.arg_weaponry.weapon.reload.ReloadClip;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: the outcome of one reload request, or of a session that ended later.
 *
 * <p>Unlike the firing result, this payload is also sent for <em>rejections</em>, because the reload key has
 * client-side feedback to give (a cancelled animation, a log line) and because a rejection is the answer to a
 * request the client explicitly made. It still cannot make the client do anything meaningful: it names the
 * hand, echoes the request sequence and reports the server's decision.</p>
 *
 * <p>The animation name and duration are the server's choice, taken from the content pack's
 * {@code reload_sequences}. The client uses them only to play the clip for the duration the server is running;
 * they are not a client input. Ammunition and magazine counts are never carried here — the changed
 * {@code weapon_state} component synchronises through Minecraft's own component mechanism.</p>
 *
 * <h2>Staged reloads</h2>
 * <p>{@link #stages()} carries the same plan the server executes, one entry per clip, so a shell-by-shell reload can be
 * animated stage by stage on the client while the server stays the only authority over timing and ammunition. The
 * single {@link #animation()} and {@link #durationTicks()} remain the opening clip and the total duration, which keeps
 * the payload readable for a definition that declares one clip.</p>
 *
 * @param hand          the hand the request referred to
 * @param sequence      the request's sequence number
 * @param result        what the server decided or did
 * @param animation     the reload animation to play, or an empty string when none applies
 * @param durationTicks the server-side reload duration in ticks, {@code 0} for a rejection
 * @param emptyMagazine whether the magazine was empty at reload start, which selects the empty-reload sound
 * @param stages        the staged clips of the accepted reload, in play order; empty when there are none
 */
public record WeaponReloadResultPayload(InteractionHand hand, int sequence, WeaponActionResult result,
                                        String animation, int durationTicks,
                                        boolean emptyMagazine, List<ReloadClip> stages) implements CustomPacketPayload {

    /** Longest animation name the codec will read, matching a content-pack animation key. */
    public static final int MAX_ANIMATION_LENGTH = 128;

    /** Most stages a reload result may carry; a reload never needs more than a handful. */
    public static final int MAX_STAGES = 64;

    /** The payload identifier. */
    public static final Type<WeaponReloadResultPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_reload_result"));

    /** The codec: hand, sequence, result id, animation name, duration, empty flag, stages. */
    public static final StreamCodec<FriendlyByteBuf, WeaponReloadResultPayload> CODEC =
            StreamCodec.of(WeaponReloadResultPayload::write, WeaponReloadResultPayload::read);

    /**
     * Backwards-compatible construction of a result that carries no stage list.
     *
     * @param hand          the hand the request referred to
     * @param sequence      the request's sequence number
     * @param result        what the server decided or did
     * @param animation     the reload animation to play, or an empty string when none applies
     * @param durationTicks the server-side reload duration in ticks
     * @param emptyMagazine whether the magazine was empty at reload start
     */
    public WeaponReloadResultPayload(InteractionHand hand, int sequence, WeaponActionResult result, String animation,
                                     int durationTicks, boolean emptyMagazine) {
        this(hand, sequence, result, animation, durationTicks, emptyMagazine, List.of());
    }

    public WeaponReloadResultPayload {
        if (hand == null) {
            throw new IllegalArgumentException("A reload result must name a hand");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("A reload result sequence must be positive but is " + sequence);
        }
        if (result == null) {
            throw new IllegalArgumentException("A reload result must name an outcome");
        }
        animation = animation == null ? "" : animation;
        if (durationTicks < 0) {
            throw new IllegalArgumentException("A reload result duration must not be negative");
        }
        if (stages == null) {
            stages = List.of();
        }
        if (stages.size() > MAX_STAGES) {
            throw new IllegalArgumentException("A reload result cannot carry more than " + MAX_STAGES + " stages");
        }
        stages = List.copyOf(stages);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** @return whether this outcome reports a started session. */
    public boolean started() {
        return result == WeaponActionResult.RELOAD_STARTED;
    }

    /** @return whether this outcome ends the reload on the client (completed or cancelled). */
    public boolean endsReload() {
        return result == WeaponActionResult.RELOAD_COMPLETED || result == WeaponActionResult.RELOAD_CANCELLED;
    }

    private static void write(FriendlyByteBuf buffer, WeaponReloadResultPayload payload) {
        buffer.writeByte(payload.hand().ordinal());
        buffer.writeVarInt(payload.sequence());
        buffer.writeVarInt(payload.result().networkId());
        buffer.writeUtf(payload.animation(), MAX_ANIMATION_LENGTH);
        buffer.writeVarInt(payload.durationTicks());
        buffer.writeBoolean(payload.emptyMagazine());
        buffer.writeVarInt(payload.stages().size());
        for (ReloadClip stage : payload.stages()) {
            buffer.writeUtf(stage.animation(), MAX_ANIMATION_LENGTH);
            buffer.writeVarInt(stage.startTick());
            buffer.writeVarInt(stage.durationTicks());
            buffer.writeVarInt(stage.transitionMillis());
        }
    }

    private static WeaponReloadResultPayload read(FriendlyByteBuf buffer) {
        int hand = buffer.readUnsignedByte();
        if (hand >= InteractionHand.values().length) {
            throw new IllegalArgumentException("Unknown hand id " + hand + " in a reload result");
        }
        int sequence = buffer.readVarInt();
        WeaponActionResult result = WeaponActionResult.byNetworkId(buffer.readVarInt());
        String animation = buffer.readUtf(MAX_ANIMATION_LENGTH);
        int durationTicks = buffer.readVarInt();
        boolean emptyMagazine = buffer.readBoolean();
        int stageCount = buffer.readVarInt();
        if (stageCount < 0 || stageCount > MAX_STAGES) {
            throw new IllegalArgumentException("A reload result carried " + stageCount + " stages, which is not a "
                    + "valid amount");
        }
        List<ReloadClip> stages = new ArrayList<>(stageCount);
        for (int i = 0; i < stageCount; i++) {
            stages.add(new ReloadClip(buffer.readUtf(MAX_ANIMATION_LENGTH), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarInt()));
        }
        return new WeaponReloadResultPayload(InteractionHand.values()[hand], sequence, result, animation,
                durationTicks, emptyMagazine, stages);
    }
}
