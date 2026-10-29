package com.arg_studios.arg_weaponry.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * The loader-independent view of custom-payload networking.
 *
 * <p>The common module cannot reference Fabric or NeoForge networking types, so each loader provides an
 * implementation of this interface through {@link com.arg_studios.arg_weaponry.platform.Services} and the
 * shared protocol only ever talks to this seam. The interface is deliberately small: declare a payload type,
 * register a handler, send. It is not a networking framework and does not abstract packet construction or
 * channel negotiation.</p>
 *
 * <h2>Phases</h2>
 * <p>Minecraft has two phases for mod payloads: {@link Phase#CONFIGURATION}, which ends before the player joins
 * a world and is therefore the earliest point a content-pack handshake can be checked, and {@link Phase#PLAY}.
 * Both loaders negotiate them separately, so a payload type is declared once per phase it is used in.</p>
 *
 * <h2>Threading</h2>
 * <p>Payloads arrive on the network thread. Every handler registered here is invoked on the game thread
 * instead — the server thread on the server, the client thread on the client — because handlers inspect
 * players, disconnect connections and finish configuration tasks.</p>
 *
 * <h2>Sides</h2>
 * <p>{@link #declareServerbound} is safe on either physical side: a client needs the type to encode a request,
 * it just never receives one. {@link #declareClientbound} is safe on either side too; the handler is optional
 * because a dedicated server only needs the type (it sends, it never receives) while a client needs the type
 * <em>and</em> the handler.</p>
 */
public interface INetworkService {

    /** The connection phase a payload belongs to. */
    enum Phase {
        /** Before the player joins a world; the earliest phase a handshake can run in. */
        CONFIGURATION,
        /** After the player joined a world. */
        PLAY
    }

    /**
     * Declares a payload type that travels from the server to the client.
     *
     * @param phase   the phase the payload belongs to
     * @param type    the payload type
     * @param codec   the codec, bound to the buffer type the loader uses for this phase
     * @param handler the handler to run on the client, or {@code null} when this side only sends the payload
     * @param <T>     the payload type
     */
    <T extends CustomPacketPayload> void declareClientbound(Phase phase, CustomPacketPayload.Type<T> type,
                                                            StreamCodec<? super FriendlyByteBuf, T> codec,
                                                            ClientboundHandler<T> handler);

    /**
     * Declares a payload type that travels from the client to the server, and the handler that validates it.
     *
     * @param phase   the phase the payload belongs to
     * @param type    the payload type
     * @param codec   the codec, bound to the buffer type the loader uses for this phase
     * @param handler the handler to run on the server
     * @param <T>     the payload type
     */
    <T extends CustomPacketPayload> void declareServerbound(Phase phase, CustomPacketPayload.Type<T> type,
                                                            StreamCodec<? super FriendlyByteBuf, T> codec,
                                                            ServerboundHandler<T> handler);

    /**
     * Sends a payload from the client to the server.
     *
     * <p>This is the seam the next milestone's gameplay requests use. Nothing sends this way yet: the
     * compatibility reply leaves through the loader's own configuration-phase reply channel, which is the
     * mechanism that exists in that phase.</p>
     *
     * <p><strong>Rule for every future serverbound payload:</strong> the server must resolve the held
     * {@code ItemStack}, the {@code WeaponDefinition} and the {@code WeaponState} from its own player and
     * registry, through the shared action-validation layer. A payload may carry an intent (which hand, which
     * action) and nothing that decides an outcome — no damage, capacity, cadence or ammunition value is ever
     * taken from the client, and a client-supplied weapon identifier must never select the definition the
     * server executes a shot with.</p>
     *
     * @param payload the payload to send
     */
    void sendToServer(CustomPacketPayload payload);

    /**
     * @param player the player whose connection to inspect
     * @param type   the payload type
     * @return whether the player's client declared that it can receive this payload type
     */
    boolean canSendToPlayer(ServerPlayer player, CustomPacketPayload.Type<?> type);

    /**
     * Sends a payload to one connected player.
     *
     * @param player  the recipient
     * @param payload the payload to send
     */
    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /**
     * Handles one clientbound payload on the game (client) thread.
     *
     * @param <T> the payload type
     */
    @FunctionalInterface
    interface ClientboundHandler<T extends CustomPacketPayload> {

        /**
         * @param payload the received payload
         * @param reply   the channel a reply has to be sent through, bound to the phase it arrived in
         */
        void handle(T payload, ReplySender reply);
    }

    /**
     * Handles one serverbound payload on the game (server) thread.
     *
     * @param <T> the payload type
     */
    @FunctionalInterface
    interface ServerboundHandler<T extends CustomPacketPayload> {

        /**
         * @param payload the received payload
         * @param context the connection the payload arrived on
         */
        void handle(T payload, ServerContext context);
    }

    /** Sends a payload back over the phase it arrived in. */
    @FunctionalInterface
    interface ReplySender {

        /**
         * @param payload the payload to send
         */
        void send(CustomPacketPayload payload);
    }

    /**
     * The connection a serverbound payload arrived on.
     *
     * <p>Deliberately narrow: a payload may need to know who sent it, may need to finish the configuration
     * task it belongs to, and may need to close the connection. Nothing here exposes a loader's context type,
     * so the protocol logic stays loader-independent.</p>
     */
    interface ServerContext {

        /** @return the sending player, or {@code null} during configuration, where no player exists yet */
        ServerPlayer player();

        /** @return the profile identifier of the sender, stable across the configuration-to-play transition */
        UUID senderId();

        /**
         * Marks the configuration task this payload belongs to as finished, so the connection may proceed.
         *
         * <p>No-op outside the configuration phase.</p>
         */
        void finishConfigurationTask();

        /**
         * Closes the connection with the given reason.
         *
         * @param reason the message the client displays
         */
        void disconnect(Component reason);
    }
}
