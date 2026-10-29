package com.arg_studios.arg_weaponry.neoforge.network;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.compat.WeaponCompatibility;
import com.arg_studios.arg_weaponry.compat.WeaponCompatibilityProtocol;
import com.arg_studios.arg_weaponry.network.WeaponFireProtocol;
import com.arg_studios.arg_weaponry.network.WeaponInspectProtocol;
import com.arg_studios.arg_weaponry.network.WeaponTriggerProtocol;
import com.arg_studios.arg_weaponry.network.WeaponReloadProtocol;
import com.arg_studios.arg_weaponry.network.WeaponManifestPayload;
import com.arg_studios.arg_weaponry.platform.Services;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.arg_studios.arg_weaponry.weapon.fire.WeaponFireModeService;
import com.arg_studios.arg_weaponry.weapon.fire.WeaponFireRuntime;
import com.arg_studios.arg_weaponry.weapon.fire.WeaponFireSessions;
import com.arg_studios.arg_weaponry.weapon.inspect.WeaponInspectRuntime;
import com.arg_studios.arg_weaponry.weapon.inspect.WeaponInspectService;
import com.arg_studios.arg_weaponry.weapon.reload.WeaponReloadRuntime;
import com.arg_studios.arg_weaponry.weapon.reload.WeaponReloadService;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Wires the shared compatibility handshake into NeoForge's configuration phase.
 *
 * <p>NeoForge runs configuration tasks in a fixed queue: the frozen-registry sync and the vanilla registry
 * synchronisation come first, then the optional tasks, then the tasks mods register from
 * {@link RegisterConfigurationTasksEvent}, and only afterwards the tasks that prepare the spawn and tell the
 * client to join the world. Registering here therefore runs <em>after</em> the item registry layout has been
 * synchronised and <em>before</em> the client is allowed into the world — exactly the window the handshake
 * needs.</p>
 *
 * <p>The task holds the connection's profile id rather than the connection itself, so no {@code ServerPlayer},
 * listener or connection object outlives the phase it belongs to.</p>
 */
public final class NeoForgeWeaponNetworking {

    private NeoForgeWeaponNetworking() {
    }

    /**
     * Subscribes the payload, configuration-task and lifecycle listeners.
     *
     * @param modEventBus the mod event bus of this mod
     */
    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(NeoForgeWeaponNetworking::onRegisterPayloads);
        modEventBus.addListener(NeoForgeWeaponNetworking::onRegisterConfigurationTasks);
        NeoForge.EVENT_BUS.addListener(NeoForgeWeaponNetworking::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(NeoForgeWeaponNetworking::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(NeoForgeWeaponNetworking::onServerStarted);
        NeoForge.EVENT_BUS.addListener(NeoForgeWeaponNetworking::onServerStopping);
        NeoForge.EVENT_BUS.addListener(NeoForgeWeaponNetworking::onServerTick);
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(WeaponCompatibilityProtocol.PROTOCOL_VERSION);
        NeoForgeNetworkService.beginRegistration(registrar);
        try {
            // The clientbound handlers are registered on both sides: they only use IPayloadContext or delegate to
            // a lazily resolved client holder, so a dedicated server can hold them safely (they are never invoked
            // there), and registering the types on the server is what lets the server send them.
            WeaponCompatibilityProtocol.declarePayloads(Services.NETWORK, true);
            WeaponFireProtocol.declarePayloads(Services.NETWORK, true);
            WeaponTriggerProtocol.declarePayloads(Services.NETWORK, true);
            WeaponInspectProtocol.declarePayloads(Services.NETWORK, true);
            WeaponReloadProtocol.declarePayloads(Services.NETWORK, true);
        } finally {
            NeoForgeNetworkService.endRegistration();
        }
    }

    private static void onRegisterConfigurationTasks(RegisterConfigurationTasksEvent event) {
        if (!(event.getListener() instanceof ServerConfigurationPacketListenerImpl listener)) {
            return;
        }
        if (!listener.hasChannel(WeaponManifestPayload.TYPE)) {
            // The client never declared the manifest channel, so nothing can be verified for this connection.
            Constants.LOG.warn("[ARG] The connecting client cannot receive the weapon manifest; its content stays "
                    + "unverified and weapon actions will be refused");
            return;
        }
        event.register(new CompatibilityTask(listener.getOwner().id()));
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        WeaponCompatibility.reportFor(player.getUUID()).ifPresentOrElse(
                report -> Constants.LOG.info("[ARG] {} joined with weapon content {}",
                        player.getName().getString(), report.status()),
                () -> Constants.LOG.warn("[ARG] {} joined without a weapon content check; weapon actions will be "
                        + "refused for this connection", player.getName().getString()));
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            WeaponCompatibility.clear(player.getUUID());
            WeaponFireRuntime.clear(player.getUUID());
            WeaponFireSessions.clear(player.getUUID());
            WeaponInspectRuntime.clear(player.getUUID());
            WeaponFireModeService.clear(player.getUUID());
            WeaponReloadRuntime.clear(player.getUUID());
        }
    }

    /**
     * Advances the reload sessions once per server tick, on the server thread.
     *
     * @param event the post-tick event
     */
    private static void onServerTick(ServerTickEvent.Post event) {
        WeaponReloadService.serverTick(event.getServer());
        WeaponFireSessions.serverTick(event.getServer());
        WeaponInspectService.serverTick(event.getServer());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        WeaponCompatibility.clearAll();
        WeaponFireRuntime.clearAll();
        WeaponFireSessions.clearAll();
        WeaponInspectRuntime.clearAll();
        WeaponFireModeService.clearAll();
        WeaponReloadRuntime.clearAll();
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        WeaponCompatibility.clearAll();
        WeaponFireRuntime.clearAll();
        WeaponFireSessions.clearAll();
        WeaponInspectRuntime.clearAll();
        WeaponFireModeService.clearAll();
        WeaponReloadRuntime.clearAll();
    }

    /**
     * Sends the manifest once and waits for the reply, at most
     * {@link WeaponCompatibilityProtocol#TIMEOUT_TICKS} server ticks.
     *
     * <p>NeoForge ticks the current configuration task and finishes it when {@link #tick()} returns
     * {@code true}; the reply normally finishes it earlier through
     * {@code IPayloadContext#finishCurrentTask}.</p>
     */
    private static final class CompatibilityTask implements ICustomConfigurationTask {

        private final UUID playerId;
        private int ticks;

        private CompatibilityTask(UUID playerId) {
            this.playerId = playerId;
            // A new connection attempt starts without a verdict: nothing from an earlier connection for this
            // profile may be inherited, and this also clears a previous attempt that was refused.
            WeaponCompatibility.clear(playerId);
        }

        @Override
        public ConfigurationTask.Type type() {
            return WeaponCompatibilityProtocol.taskType();
        }

        @Override
        public void run(Consumer<CustomPacketPayload> sender) {
            sender.accept(WeaponCompatibilityProtocol.manifestPayload());
        }

        @Override
        public boolean tick() {
            if (WeaponCompatibility.reportFor(playerId).isPresent()) {
                return true;
            }
            if (++ticks > WeaponCompatibilityProtocol.TIMEOUT_TICKS) {
                WeaponCompatibilityProtocol.onTimeout(playerId);
                return true;
            }
            return false;
        }
    }
}
