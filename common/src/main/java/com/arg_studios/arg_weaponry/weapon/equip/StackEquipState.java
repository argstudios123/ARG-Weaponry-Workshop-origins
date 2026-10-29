package com.arg_studios.arg_weaponry.weapon.equip;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;


public record StackEquipState(boolean drawn) {

    /** The state of a stack that has never been drawn; also what an absent component means. */
    public static final StackEquipState NOT_DRAWN = new StackEquipState(false);

    /** The state of a stack that has been drawn at least once. */
    public static final StackEquipState DRAWN = new StackEquipState(true);

    /**
     * The persistent codec. {@code drawn} is an optional field whose default is {@code false}, so a saved stack from
     * before this component existed reads as "never drawn" instead of failing to load.
     */
    public static final Codec<StackEquipState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("drawn", false).forGetter(StackEquipState::drawn)
    ).apply(instance, StackEquipState::new));

    /** The network codec: one boolean, so the component costs a single byte on the wire. */
    public static final StreamCodec<RegistryFriendlyByteBuf, StackEquipState> STREAM_CODEC = StreamCodec.of(
            (buffer, state) -> ByteBufCodecs.BOOL.encode(buffer, state.drawn()),
            buffer -> state(ByteBufCodecs.BOOL.decode(buffer)));


    public static StackEquipState state(boolean drawn) {
        return drawn ? DRAWN : NOT_DRAWN;
    }
}
