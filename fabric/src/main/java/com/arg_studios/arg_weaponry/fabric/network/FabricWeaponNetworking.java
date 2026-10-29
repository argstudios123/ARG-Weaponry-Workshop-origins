package com.arg_studios.arg_weaponry.fabric.network;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.compat.WeaponCompatibility;
import com.arg_studios.arg_weaponry.compat.WeaponCompatibilityProtocol;
import com.arg_studios.arg_weaponry.network.WeaponFireProtocol;
import com.arg_studios.arg_weaponry.network.WeaponInspectProtocol;
import com.arg_studios.arg_weaponry.network.WeaponTriggerProtocol;
import com.arg_studios.arg_weaponry.network.WeaponReloadProtocol;
import com.arg_studios.arg_weaponry.network.WeaponManifestPayload;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.fire.WeaponFireModeService;
import com.arg_studios.arg_weaponry.weapon.fire.WeaponFireRuntime;
import com.arg_studios.arg_weaponry.weapon.fire.WeaponFireSessions;
import com.arg_studios.arg_weaponry.weapon.inspect.WeaponInspectRuntime;
import com.arg_studios.arg_weaponry.weapon.inspect.WeaponInspectService;
import com.arg_studios.arg_weaponry.weapon.reload.WeaponReloadRuntime;
import com.arg_studios.arg_weaponry.weapon.reload.WeaponReloadService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Wires the shared compatibility handshake into Fabric's configuration phase.
 *
 * <p>The handshake is a vanilla {@link ConfigurationTask} added from
 * {@link ServerConfigurationConnectionEvents#CONFIGURE}. Fabric fires that event once the client's channel
 * registration has arrived, which is why {@link ServerConfigurationNetworking#canSend} is meaningful there —
 * Fabric's own {@code c:version}/{@code c:register} exchange uses exactly this event and API. Adding a task
 * also <em>holds the connection</em>: vanilla does not tell the client to join the world until the task
 * finishes, so a rejection happens before any item stack is used.</p>
 *
 * <p>Ordering relative to the registry: Fabric does not synchronise the item registry at all, so there is no
 * earlier point at which the two registries could be compared. This task is still the earliest place a mod
 * payload can travel, and it runs before the player exists.</p>
 */
public final class FabricWeaponNetworking {

    private FabricWeaponNetworking() {
    }

    /** Registers the payload types, the configuration task hook and the lifecycle cleanup. Both sides. */
    public static void registerCommon() {
        // The clientbound handler is client-only, so it is registered from the client entry point instead.
        WeaponCompatibilityProtocol.declarePayloads(Services.NETWORK, false);
        WeaponFireProtocol.declarePayloads(Services.NETWORK, false);
        WeaponTriggerProtocol.declarePayloads(Services.NETWORK, false);
        WeaponInspectProtocol.declarePayloads(Services.NETWORK, false);
        WeaponReloadProtocol.declarePayloads(Services.NETWORK, false);
        // [DrawHolster hook] The equip request payload (serverbound only; the client needs nothing back).
        com.arg_studios.arg_weaponry.weapon.equip.EquipProtocol.declarePayloads(Services.NETWORK);

        ServerConfigurationConnectionEvents.CONFIGURE.register(FabricWeaponNetworking::onConfigure);
        ServerConfigurationConnectionEvents.DISCONNECT.register((listener, server) -> {
            WeaponCompatibility.clear(listener.getOwner().id());
            WeaponFireRuntime.clear(listener.getOwner().id());
            WeaponFireSessions.clear(listener.getOwner().id());
            WeaponInspectRuntime.clear(listener.getOwner().id());
            WeaponFireModeService.clear(listener.getOwner().id());
            WeaponReloadRuntime.clear(listener.getOwner().id());
            // [DrawHolster hook] Drop the connecting player's equip bookkeeping with the other runtimes.
            com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.clear(listener.getOwner().id());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            WeaponCompatibility.clear(handler.player.getUUID());
            WeaponFireRuntime.clear(handler.player.getUUID());
            WeaponFireSessions.clear(handler.player.getUUID());
            WeaponInspectRuntime.clear(handler.player.getUUID());
            WeaponFireModeService.clear(handler.player.getUUID());
            WeaponReloadRuntime.clear(handler.player.getUUID());
            // [DrawHolster hook] Drop the player's equip windows and request bookkeeping.
            com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.clear(handler.player.getUUID());
        });
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            WeaponCompatibility.clearAll();
            WeaponFireRuntime.clearAll();
            WeaponFireSessions.clearAll();
            WeaponInspectRuntime.clearAll();
            WeaponFireModeService.clearAll();
            WeaponReloadRuntime.clearAll();
            // [DrawHolster hook] A new server starts with no equip state.
            com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.clearAll();
        });
        // Reload sessions complete on the server thread, inside the normal server tick.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            WeaponReloadService.serverTick(server);
            WeaponFireSessions.serverTick(server);
            WeaponInspectService.serverTick(server);
            // [DrawHolster hook] The authoritative draw/holster windows advance with the same server tick.
            com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.serverTick(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            WeaponCompatibility.clearAll();
            WeaponFireRuntime.clearAll();
            WeaponFireSessions.clearAll();
            WeaponInspectRuntime.clearAll();
            WeaponFireModeService.clearAll();
            WeaponReloadRuntime.clearAll();
            // [DrawHolster hook] Nothing may outlive the server.
            com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.clearAll();
        });
    }

    /** Registers the client-side handlers that answer the harness and animate accepted shots. Client only. */
    public static void registerClient() {
        WeaponCompatibilityProtocol.declarePayloads(Services.NETWORK, true);
        WeaponFireProtocol.declarePayloads(Services.NETWORK, true);
        WeaponTriggerProtocol.declarePayloads(Services.NETWORK, true);
        WeaponInspectProtocol.declarePayloads(Services.NETWORK, true);
        WeaponReloadProtocol.declarePayloads(Services.NETWORK, true);
    }

    private static void onConfigure(ServerConfigurationPacketListenerImpl listener, MinecraftServer server) {
        if (!(listener instanceof FabricServerConfigurationPacketListenerImpl fabricListener)) {
            return;
        }
        if (!ServerConfigurationNetworking.canSend(listener, WeaponManifestPayload.TYPE)) {
            // The client does not accept our payload: either it runs an older build of this mod or the channel
            // negotiation did not complete. Nothing can be verified, so no verdict is recorded and later
            // gameplay refuses actions for that connection.
            Constants.LOG.warn("[ARG] The connecting client cannot receive the weapon manifest; its content stays "
                    + "unverified and weapon actions will be refused");
            return;
        }
        fabricListener.addTask(new CompatibilityTask(listener.getOwner().id()));
    }

    /**
     * Sends the manifest once and waits for the reply, at most {@link WeaponCompatibilityProtocol#TIMEOUT_TICKS}
     * ticks. Vanilla ticks the current task every server tick and finishes it when {@link #tick()} returns
     * {@code true}.
     */
    private static final class CompatibilityTask implements ConfigurationTask {

        private final UUID playerId;
        private int ticks;

        private CompatibilityTask(UUID playerId) {
            this.playerId = playerId;
            // A new connection attempt starts without a verdict: nothing from an earlier connection for this
            // profile may be inherited, and this also clears a previous attempt that was refused.
            WeaponCompatibility.clear(playerId);
        }

        @Override
        public void start(Consumer<Packet<?>> sender) {
            sender.accept(ServerConfigurationNetworking
                    .createClientboundPacket(WeaponCompatibilityProtocol.manifestPayload()));
        }

        @Override
        public boolean tick() {
            if (WeaponCompatibility.reportFor(playerId).isPresent()) {
                // The reply already completed the handshake; nothing left to wait for.
                return true;
            }
            if (++ticks > WeaponCompatibilityProtocol.TIMEOUT_TICKS) {
                WeaponCompatibilityProtocol.onTimeout(playerId);
                return true;
            }
            return false;
        }

        @Override
        public ConfigurationTask.Type type() {
            return WeaponCompatibilityProtocol.taskType();
        }
    }
}
