package com.arg_studios.arg_weaponry.weapon.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * The selectable fire modes of a weapon.
 *
 * <p>This enum carries no behaviour yet: it only names a selection and defines how that selection is
 * stored and transmitted. Firing is a later milestone.</p>
 *
 * <h2>Stable serialized values</h2>
 * <p>Every constant declares <em>both</em> of its serialized forms explicitly:</p>
 * <ul>
 *     <li>{@link #serializedName()} is the persistent value written by {@link #CODEC} to the item data
 *     component in a save file,</li>
 *     <li>{@link #networkId()} is the value written by {@link #STREAM_CODEC} to the network.</li>
 * </ul>
 * <p>Neither is derived from {@link Enum#ordinal()}. Adding a constant later therefore cannot silently
 * change the meaning of a value that was already written: a new constant must choose a new unique name
 * and a new unique id, and existing ids must never be renumbered or reused. Ids start at 1 so that a
 * forgotten value cannot be mistaken for {@code SINGLE}.</p>
 */
public enum FireMode {

    /** One shot per trigger pull. */
    SINGLE(1, "single"),

    /** A fixed number of shots per trigger pull. */
    BURST(2, "burst"),

    /** Continuous fire while the trigger is held. */
    AUTO(3, "auto");

    private final int networkId;
    private final String serializedName;

    FireMode(int networkId, String serializedName) {
        this.networkId = networkId;
        this.serializedName = serializedName;
    }

    /** @return the wire value used by {@link #STREAM_CODEC}; stable and never reused. */
    public int networkId() {
        return networkId;
    }

    /** @return the persistent value used by {@link #CODEC}; stable and never renamed. */
    public String serializedName() {
        return serializedName;
    }

    /**
     * Persistent codec: writes the explicit {@link #serializedName()}, never the ordinal.
     *
     * <p>An unknown name produces a {@link DataResult} error, so a malformed save is rejected cleanly
     * instead of falling back to another mode.</p>
     */
    public static final Codec<FireMode> CODEC =
            Codec.STRING.comapFlatMap(FireMode::fromSerializedName, FireMode::serializedName);

    /**
     * Network codec: writes the explicit {@link #networkId()}, never the ordinal.
     *
     * <p>The id is read with {@code VAR_INT} so the encoding stays compact for the one place a fire mode
     * changes: an explicit player selection.</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, FireMode> STREAM_CODEC = StreamCodec.of(
            (buffer, mode) -> ByteBufCodecs.VAR_INT.encode(buffer, mode.networkId),
            buffer -> byNetworkId(ByteBufCodecs.VAR_INT.decode(buffer)));

    /**
     * @param name a persistent value as written by {@link #CODEC}
     * @return the matching mode, or a {@link DataResult} error naming the accepted values
     */
    public static DataResult<FireMode> fromSerializedName(String name) {
        for (FireMode mode : values()) {
            if (mode.serializedName.equals(name)) {
                return DataResult.success(mode);
            }
        }
        return DataResult.error(() -> "Unknown fire mode '" + name + "'; expected one of " + serializedNames());
    }

    /**
     * @param id a wire value as written by {@link #STREAM_CODEC}
     * @return the matching mode
     * @throws IllegalArgumentException when no mode declares that id, which means the stream is corrupt
     *                                  or comes from an incompatible version
     */
    public static FireMode byNetworkId(int id) {
        for (FireMode mode : values()) {
            if (mode.networkId == id) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown fire mode id " + id + "; the weapon state stream is corrupt "
                + "or was written by an incompatible version");
    }

    /** @return the persistent values, in declaration order, for diagnostics. */
    public static List<String> serializedNames() {
        List<String> names = new ArrayList<>(values().length);
        for (FireMode mode : values()) {
            names.add(mode.serializedName);
        }
        return List.copyOf(names);
    }
}
