package com.arg_studios.arg_weaponry.weapon.equip;

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
 * The data component owned by the draw/holster feature: {@code arg_weaponry:equip_state}.
 *
 * <p>Registered exactly like the weapon-state component ({@link com.arg_studios.arg_weaponry.weapon.state.WeaponComponents}):
 * common code performs the registry write, and both loaders call it from the same safe registration point, which is
 * {@code ArgWeaponry.registerComponents()} - Fabric during mod initialisation, NeoForge from the
 * {@code minecraft:data_component_type} registration event. Nothing here touches the weapon-state component, its schema
 * or its codecs.</p>
 *
 * <p>A weapon stack that carries no value is treated as undrawn (see {@link StackEquipState#NOT_DRAWN}), so no migration
 * is needed and no item definition has to declare a default.</p>
 */
public final class EquipComponents {

    /** The registry id of the equip state component. */
    public static final Identifier EQUIP_STATE_ID =
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "equip_state");

    private static final ResourceKey<DataComponentType<?>> EQUIP_STATE_KEY =
            ResourceKey.create(Registries.DATA_COMPONENT_TYPE, EQUIP_STATE_ID);

    /**
     * Persistent, network-synchronized per-stack equip state.
     *
     * <p>{@code persistent} makes the first-draw flag part of the item's save data, and {@code networkSynchronized}
     * makes vanilla send it with the stack, which is how the client learns that the server already counted its first
     * draw - without a packet of our own.</p>
     */
    public static final DataComponentType<StackEquipState> EQUIP_STATE =
            DataComponentType.<StackEquipState>builder()
                    .persistent(StackEquipState.CODEC)
                    .networkSynchronized(StackEquipState.STREAM_CODEC)
                    .build();

    private static boolean registered;

    private EquipComponents() {
    }

    /**
     * Registers {@link #EQUIP_STATE}. Safe to call more than once; only the first call registers.
     *
     * @param report the report to collect diagnostics in
     */
    public static void register(ValidationReport report) {
        if (registered) {
            return;
        }
        registered = true;

        if (BuiltInRegistries.DATA_COMPONENT_TYPE.containsKey(EQUIP_STATE_ID)) {
            report.error(ValidationReport.frameworkScope(), EQUIP_STATE_ID.toString(),
                    "The data component id '" + EQUIP_STATE_ID + "' is already registered; the equip state component "
                            + "was skipped and nothing was overwritten");
            return;
        }

        try {
            Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, EQUIP_STATE_KEY, EQUIP_STATE);
        } catch (RuntimeException e) {
            report.error(ValidationReport.frameworkScope(), EQUIP_STATE_ID.toString(),
                    "Failed to register the equip state data component '" + EQUIP_STATE_ID + "': " + e.getMessage());
            return;
        }

        Constants.LOG.info("[ARG] Registered the equip state data component '{}'", EQUIP_STATE_ID);
    }

    /**
     * Reads the stored equip state of a stack.
     *
     * @param stack the stack to read, may be {@code null}
     * @return the stored state, or {@code null} when the stack carries none (which means "never drawn")
     */
    public static StackEquipState get(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.get(EQUIP_STATE);
    }

    /** @return whether the component type has been registered in this process. */
    public static boolean isRegistered() {
        return registered;
    }
}
