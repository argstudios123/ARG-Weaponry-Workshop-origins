package com.arg_studios.arg_weaponry.fabric.network;

import com.arg_studios.arg_weaponry.compat.WeaponCompatibilityProtocol;
import com.arg_studios.arg_weaponry.network.INetworkService;
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric implementation of {@link INetworkService}.
 *
 * <p>Fabric's payload types live in four global registries (configuration/play × serverbound/clientbound) and
 * its receivers are global too, so this class only forwards each declaration to the matching Fabric API and
 * remembers what it already declared: the shared protocol declares the same types from both the main and the
 * client entry point, and Fabric rejects a second registration of one identifier.</p>
 *
 * <p>Everything client-only is isolated in {@link FabricClientSide}. A dedicated server never loads
 * {@code net.fabricmc.fabric.api.client.networking.v1}, because that nested class is resolved only when one of
 * its methods is first called, and a dedicated server never registers a clientbound handler and never sends a
 * request to itself.</p>
 *
 * <p>Serverbound handlers always run on the server thread: Fabric delivers payloads on the network thread and
 * this class hops to {@code context.server().execute(...)} before touching any game state.</p>
 */
public final class FabricNetworkService implements INetworkService {

    /** Declarations already forwarded, keyed by direction, phase and payload id. */
    private static final Set<String> declaredTypes = ConcurrentHashMap.newKeySet();

    /** Receivers already registered, keyed by phase and payload id. */
    private static final Set<String> registeredReceivers = ConcurrentHashMap.newKeySet();

    @Override
    public <T extends CustomPacketPayload> void declareClientbound(Phase phase, CustomPacketPayload.Type<T> type,
                                                                   StreamCodec<? super FriendlyByteBuf, T> codec,
                                                                   ClientboundHandler<T> handler) {
        if (declaredTypes.add("clientbound|" + phase + "|" + type.id())) {
            switch (phase) {
                case CONFIGURATION -> PayloadTypeRegistry.clientboundConfiguration().register(type, codec);
                case PLAY -> PayloadTypeRegistry.clientboundPlay().register(type, codec);
            }
        }
        if (handler != null) {
            FabricClientSide.registerClientboundHandler(phase, type, handler);
        }
    }

    @Override
    public <T extends CustomPacketPayload> void declareServerbound(Phase phase, CustomPacketPayload.Type<T> type,
                                                                   StreamCodec<? super FriendlyByteBuf, T> codec,
                                                                   ServerboundHandler<T> handler) {
        if (declaredTypes.add("serverbound|" + phase + "|" + type.id())) {
            switch (phase) {
                case CONFIGURATION -> PayloadTypeRegistry.serverboundConfiguration().register(type, codec);
                case PLAY -> PayloadTypeRegistry.serverboundPlay().register(type, codec);
            }
        }
        if (!registeredReceivers.add(phase + "|" + type.id())) {
            return;
        }
        switch (phase) {
            case CONFIGURATION -> ServerConfigurationNetworking.registerGlobalReceiver(type, (payload, context) ->
                    context.server().execute(() -> handler.handle(payload,
                            new ConfigurationContext(context.packetListener(),
                                    WeaponCompatibilityProtocol.taskType()))));
            case PLAY -> ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) ->
                    context.server().execute(() -> handler.handle(payload, new PlayContext(context.player()))));
        }
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        FabricClientSide.sendToServer(payload);
    }

    @Override
    public boolean canSendToPlayer(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return ServerPlayNetworking.canSend(player, type);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    /** The configuration-phase connection: no player exists yet, but the profile id does. */
    private record ConfigurationContext(ServerConfigurationPacketListenerImpl listener, ConfigurationTask.Type taskType)
            implements ServerContext {

        @Override
        public ServerPlayer player() {
            return null;
        }

        @Override
        public UUID senderId() {
            return listener.getOwner().id();
        }

        @Override
        public void finishConfigurationTask() {
            if (listener instanceof FabricServerConfigurationPacketListenerImpl fabricListener) {
                fabricListener.completeTask(taskType);
            }
        }

        @Override
        public void disconnect(Component reason) {
            listener.disconnect(reason);
        }
    }

    /** The play-phase connection. */
    private record PlayContext(ServerPlayer player) implements ServerContext {

        @Override
        public UUID senderId() {
            return player.getUUID();
        }

        @Override
        public void finishConfigurationTask() {
            // A play-phase payload belongs to no configuration task.
        }

        @Override
        public void disconnect(Component reason) {
            player.connection.disconnect(reason);
        }
    }

    /** Client-only Fabric API calls, resolved lazily so a dedicated server never loads them. */
    private static final class FabricClientSide {

        private FabricClientSide() {
        }

        private static <T extends CustomPacketPayload> void registerClientboundHandler(
                Phase phase, CustomPacketPayload.Type<T> type, ClientboundHandler<T> handler) {
            switch (phase) {
                case CONFIGURATION -> net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
                        .registerGlobalReceiver(type, (payload, context) -> context.client().execute(() ->
                                handler.handle(payload, net.fabricmc.fabric.api.client.networking.v1
                                        .ClientConfigurationNetworking::send)));
                case PLAY -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
                        .registerGlobalReceiver(type, (payload, context) -> context.client().execute(() ->
                                handler.handle(payload, net.fabricmc.fabric.api.client.networking.v1
                                        .ClientPlayNetworking::send)));
            }
        }

        private static void sendToServer(CustomPacketPayload payload) {
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(payload);
        }
    }
}
