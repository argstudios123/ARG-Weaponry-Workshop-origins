package com.arg_studios.arg_weaponry.weapon.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The persistent state of one weapon ItemStack.
 *
 * <p>This is the mutable half of a weapon: everything that belongs to <em>this stack</em> rather than to
 * the weapon definition. It lives in the {@code arg_weaponry:weapon_state} data component, which is both
 * persisted and network synchronized, so it survives inventory moves, drops, world saves and
 * server-to-client synchronization without a custom packet.</p>
 *
 * <p>The record is immutable and holds only the three fields the project needs today. Reload timers,
 * cooldowns, recoil accumulation, reserve ammunition and attachments are deliberately absent: they are
 * either runtime execution state or a later milestone's concern.</p>
 *
 * <h2>Validation</h2>
 * <p>Invalid serialized input is rejected as a normal {@link DataResult} error: a negative ammunition
 * count, a schema version this build cannot interpret, an unknown fire mode. The canonical constructor
 * repeats the checks as a guard for programmatic construction, so no code path can build an impossible
 * state.</p>
 *
 * <h2>Persistent vs network schema version</h2>
 * <p>The persistent codec writes {@code schema} as an optional field whose default is
 * {@link #MINIMUM_SCHEMA}, the <em>oldest</em> schema this build understands, and not the current one.
 * That choice is what makes the field forward compatible: a map codec omits a value that equals its
 * default, so state written today carries no {@code schema} field, and a later build that bumps
 * {@link #CURRENT_SCHEMA} will write the field explicitly for new data while still reading today's data
 * as schema 1. Defaulting to the current version instead would silently reinterpret old saves as the new
 * schema after such a bump.</p>
 *
 * <p>The network codec writes only the ammunition count and the fire mode and reconstructs the schema
 * from {@link #CURRENT_SCHEMA}: a network stream is only exchanged between two sides running the same
 * mod version, so it cannot carry a schema this build does not understand, and the component's stream
 * codec is not a save format.</p>
 *
 * @param schema   the schema version this state was written with, between {@link #MINIMUM_SCHEMA} and
 *                 {@link #CURRENT_SCHEMA}
 * @param ammo     rounds currently in the magazine; never negative
 * @param fireMode the selected fire mode; never {@code null}
 */
public record WeaponState(int schema, int ammo, FireMode fireMode) {

    /** The schema version written by this build. */
    public static final int CURRENT_SCHEMA = 1;

    /** The oldest schema version this build can still read. */
    public static final int MINIMUM_SCHEMA = 1;

    /** Schema versions are validated before construction so a bad save is a {@link DataResult} error. */
    private static final Codec<Integer> SCHEMA_CODEC = Codec.INT.validate(WeaponState::validateSchema);

    /** Ammunition is validated the same way, with a message that names the offending value. */
    private static final Codec<Integer> AMMO_CODEC = Codec.INT.validate(WeaponState::validateAmmo);

    /**
     * Persistent codec used by the data component.
     *
     * <p>{@code schema} is optional and defaults to {@link #MINIMUM_SCHEMA}, not to the current version:
     * state written today therefore stores no {@code schema} field, and a later build that raises
     * {@link #CURRENT_SCHEMA} will write it explicitly while still reading today's data as schema 1.
     * {@code fire_mode} is optional with {@link FireMode#SINGLE} as its default. Both {@code schema} and
     * {@code ammo} are validated, so a corrupt save becomes a data error instead of a crash.</p>
     */
    public static final Codec<WeaponState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            SCHEMA_CODEC.optionalFieldOf("schema", MINIMUM_SCHEMA).forGetter(WeaponState::schema),
            AMMO_CODEC.fieldOf("ammo").forGetter(WeaponState::ammo),
            FireMode.CODEC.optionalFieldOf("fire_mode", FireMode.SINGLE).forGetter(WeaponState::fireMode)
    ).apply(instance, WeaponState::new));

    /**
     * Network codec used by the data component's synchronization.
     *
     * <p>Writes the ammunition count ({@code VAR_INT}) followed by the fire mode's explicit wire id, and
     * reconstructs the schema from {@link #CURRENT_SCHEMA}. A minimal state is therefore two small
     * integers, which is what makes it affordable to synchronize on every shot in a later milestone.</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, WeaponState> STREAM_CODEC = StreamCodec.of(
            (buffer, state) -> {
                ByteBufCodecs.VAR_INT.encode(buffer, state.ammo());
                FireMode.STREAM_CODEC.encode(buffer, state.fireMode());
            },
            buffer -> {
                int ammo = ByteBufCodecs.VAR_INT.decode(buffer);
                FireMode fireMode = FireMode.STREAM_CODEC.decode(buffer);
                return new WeaponState(CURRENT_SCHEMA, ammo, fireMode);
            });

    public WeaponState {
        if (schema < MINIMUM_SCHEMA || schema > CURRENT_SCHEMA) {
            throw new IllegalArgumentException("Unsupported weapon state schema " + schema + "; this build reads "
                    + MINIMUM_SCHEMA + " to " + CURRENT_SCHEMA);
        }
        if (ammo < 0) {
            throw new IllegalArgumentException("Ammunition must not be negative but is " + ammo);
        }
        if (fireMode == null) {
            throw new IllegalArgumentException("A weapon state must have a fire mode");
        }
    }

    /**
     * @param ammo the starting ammunition count; must not be negative
     * @return the state a fresh stack starts with: current schema, {@link FireMode#SINGLE}
     */
    public static WeaponState initial(int ammo) {
        return new WeaponState(CURRENT_SCHEMA, ammo, FireMode.SINGLE);
    }

    /**
     * @param ammo the new ammunition count; must not be negative
     * @return a new state with the same schema and fire mode
     */
    public WeaponState withAmmo(int ammo) {
        return new WeaponState(schema, ammo, fireMode);
    }

    /**
     * @param fireMode the new fire mode; must not be {@code null}
     * @return a new state with the same schema and ammunition count
     */
    public WeaponState withFireMode(FireMode fireMode) {
        return new WeaponState(schema, ammo, fireMode);
    }

    /** @return {@code true} when the magazine is empty. */
    public boolean isEmpty() {
        return ammo == 0;
    }

    /**
     * @param capacity the weapon's magazine capacity
     * @return {@code true} when the magazine holds at least {@code capacity}
     */
    public boolean isFull(int capacity) {
        return ammo >= capacity;
    }

    private static DataResult<Integer> validateSchema(int schema) {
        if (schema < MINIMUM_SCHEMA || schema > CURRENT_SCHEMA) {
            return DataResult.error(() -> "Unsupported weapon state schema " + schema + "; this build reads "
                    + MINIMUM_SCHEMA + " to " + CURRENT_SCHEMA);
        }
        return DataResult.success(schema);
    }

    private static DataResult<Integer> validateAmmo(int ammo) {
        if (ammo < 0) {
            return DataResult.error(() -> "Ammunition must not be negative but is " + ammo);
        }
        return DataResult.success(ammo);
    }
}
