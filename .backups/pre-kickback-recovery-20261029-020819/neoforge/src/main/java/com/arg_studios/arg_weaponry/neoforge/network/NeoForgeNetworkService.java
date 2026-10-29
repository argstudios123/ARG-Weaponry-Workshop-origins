package com.arg_studios.arg_weaponry.neoforge.network;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.compat.WeaponCompatibilityProtocol;
import com.arg_studios.arg_weaponry.network.INetworkService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;

/**
 * NeoForge implementation of {@link INetworkService}.
 *
 * <p>NeoForge collects payload declarations from {@code RegisterPayloadHandlersEvent}, so this class keeps the
 * {@link PayloadRegistrar} of the running event in a field while the shared protocol declares its types and
 * clears it afterwards. A declaration made outside that window is reported and ignored instead of throwing
 * during mod construction.</p>
 *
 * <p>Handlers are wrapped so they run on the game thread. NeoForge's registrar does that by default, but doing
 * it explicitly keeps the Fabric and NeoForge behaviour identical and makes the threading contract independent
 * of a loader setting.</p>
 *
 * <p>Everything client-only lives in {@link NeoForgeClientSide}, which is resolved only when a client actually
 * sends a request to a server.</p>
 */
public final class NeoForgeNetworkService implements INetworkService {

    /** Used when a connection has no resolvable profile id, so it can never match a real player. */
    private static final UUID UNKNOWN_SENDER = new UUID(0L, 0L);

    private static volatile PayloadRegistrar registrar;

    static void beginRegistration(PayloadRegistrar activeRegistrar) {
        registrar = activeRegistrar;
    }

    static void endRegistration() {
        registrar = null;
    }

    @Override
    public <T extends CustomPacketPayload> void declareClientbound(Phase phase, CustomPacketPayload.Type<T> type,
                                                                   StreamCodec<? super FriendlyByteBuf, T> codec,
                                                                   ClientboundHandler<T> handler) {
        PayloadRegistrar active = registrar;
        if (active == null) {
            Constants.LOG.warn("[ARG] The clientbound payload '{}' was declared outside the registration event",
                    type.id());
            return;
        }
        switch (phase) {
            case CONFIGURATION -> {
                if (handler == null) {
                    active.configurationToClient(type, codec);
                } else {
                    active.configurationToClient(type, codec, clientHandler(handler));
                }
            }
            case PLAY -> {
                if (handler == null) {
                    active.playToClient(type, codec);
                } else {
                    active.playToClient(type, codec, clientHandler(handler));
                }
            }
        }
    }

    @Override
    public <T extends CustomPacketPayload> void declareServerbound(Phase phase, CustomPacketPayload.Type<T> type,
                                                                   StreamCodec<? super FriendlyByteBuf, T> codec,
                                                                   ServerboundHandler<T> handler) {
        PayloadRegistrar active = registrar;
        if (active == null) {
            Constants.LOG.warn("[ARG] The serverbound payload '{}' was declared outside the registration event",
                    type.id());
            return;
        }
        switch (phase) {
            case CONFIGURATION -> active.configurationToServer(type, codec, serverHandler(handler));
            case PLAY -> active.playToServer(type, codec, serverHandler(handler));
        }
    }

    private static <T extends CustomPacketPayload> IPayloadHandler<T> clientHandler(ClientboundHandler<T> handler) {
        return (payload, context) -> context.enqueueWork(() -> handler.handle(payload, context::reply));
    }

    private static <T extends CustomPacketPayload> IPayloadHandler<T> serverHandler(ServerboundHandler<T> handler) {
        return (payload, context) -> context.enqueueWork(() ->
                handler.handle(payload, new NeoForgeServerContext(context)));
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        NeoForgeClientSide.sendToServer(payload);
    }

    @Override
    public boolean canSendToPlayer(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection instanceof ICommonPacketListener listener && listener.hasChannel(type);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** The connection a payload arrived on, in either phase. */
    private record NeoForgeServerContext(IPayloadContext context) implements ServerContext {

        @Override
        public ServerPlayer player() {
            return context.player() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        }

        @Override
        public UUID senderId() {
            if (context.listener() instanceof ServerConfigurationPacketListenerImpl configuration) {
                return configuration.getOwner().id();
            }
            ServerPlayer player = player();
            return player == null ? UNKNOWN_SENDER : player.getUUID();
        }

        @Override
        public void finishConfigurationTask() {
            // Valid exactly while our configuration task is the current one, which is when a configuration-phase
            // reply can arrive.
            context.finishCurrentTask(WeaponCompatibilityProtocol.taskType());
        }

        @Override
        public void disconnect(Component reason) {
            context.disconnect(reason);
        }
    }

    /** Client-only NeoForge API call, resolved lazily so a dedicated server never loads it. */
    private static final class NeoForgeClientSide {

        private NeoForgeClientSide() {
        }

        private static void sendToServer(CustomPacketPayload payload) {
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(payload);
        }
    }
}
