package com.arg_studios.arg_weaponry.weapon.state;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;

/**
 * The data components owned by ARG Weaponry.
 *
 * <p>There is exactly one component today: {@code arg_weaponry:weapon_state}, which carries the
 * persistent {@link WeaponState} of a weapon stack. It is registered into Minecraft's own
 * {@code data_component_type} registry, so vanilla's item serialization and inventory synchronization
 * handle it; no custom packet or registry abstraction is involved.</p>
 *
 * <p>Registration follows the same convention as {@code WeaponRegistration}: common code performs the
 * registry write, and each loader calls it from its own safe registration point. The component must be
 * registered before weapon item components are <em>bound</em> (item construction itself only records a
 * deferred {@code DataComponentInitializers} entry), and both loaders guarantee the stricter ordering
 * described in {@link #register(ValidationReport)}.</p>
 */
public final class WeaponComponents {

    /** The registry id of the weapon state component. */
    public static final Identifier WEAPON_STATE_ID =
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_state");

    private static final ResourceKey<DataComponentType<?>> WEAPON_STATE_KEY =
            ResourceKey.create(Registries.DATA_COMPONENT_TYPE, WEAPON_STATE_ID);

    /**
     * Persistent, network-synchronized per-stack weapon state.
     *
     * <p>{@code persistent} makes the value part of the item's save data; {@code networkSynchronized}
     * makes vanilla send it with the stack whenever the stack is synchronized, which is how ammunition
     * will reach the client without a custom packet.</p>
     */
    public static final DataComponentType<WeaponState> WEAPON_STATE = DataComponentType.<WeaponState>builder()
            .persistent(WeaponState.CODEC)
            .networkSynchronized(WeaponState.STREAM_CODEC)
            .build();

    private static boolean registered;

    private WeaponComponents() {
    }

    /**
     * Registers {@link #WEAPON_STATE}. Safe to call more than once; only the first call registers.
     *
     * <p><strong>Ordering.</strong> The component must exist before any weapon item's components are
     * bound. On Fabric this method is called during mod initialisation, before item registration. On
     * NeoForge it is called from the {@code RegisterEvent} for {@code minecraft:data_component_type},
     * which NeoForge fires before {@code minecraft:item} (its registration order explicitly places data
     * component types second for exactly this reason).</p>
     *
     * @param report the report to collect diagnostics in
     */
    public static void register(ValidationReport report) {
        if (registered) {
            return;
        }
        registered = true;

        if (BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(WEAPON_STATE_ID)) {
            report.error(ValidationReport.frameworkScope(), WEAPON_STATE_ID.toString(),
                    "The data component id '" + WEAPON_STATE_ID + "' is already registered; the weapon state "
                            + "component was skipped and nothing was overwritten");
            return;
        }

        try {
            Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, WEAPON_STATE_KEY, WEAPON_STATE);
        } catch (RuntimeException e) {
            report.error(ValidationReport.frameworkScope(), WEAPON_STATE_ID.toString(),
                    "Failed to register the weapon state data component '" + WEAPON_STATE_ID + "': "
                            + e.getMessage());
            return;
        }

        Constants.LOG.info("[ARG] Registered the weapon state data component '{}'", WEAPON_STATE_ID);
    }

    /**
     * Reads the stored state of a stack.
     *
     * <p>Vanilla already falls back to the item's declared default components, so a stack written before
     * this component existed resolves to the default the weapon item was registered with. The value is
     * returned as stored; interpreting it against a magazine capacity belongs to the caller.</p>
     *
     * @param stack the stack to read
     * @return the stored state, or {@code null} when the stack's item declares no weapon state at all
     */
    public static WeaponState get(ItemStack stack) {
        return stack.get(WEAPON_STATE);
    }
}
