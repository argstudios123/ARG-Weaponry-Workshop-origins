package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.weapon.inspect.WeaponInspectService;
import net.minecraft.server.level.ServerPlayer;

/**
 * The play-phase glue for inspection: one serverbound request, one clientbound outcome.
 *
 * <p>Exactly the same shape as the firing and reloading protocols: the handler unwraps nothing but a hand and a sequence
 * and hands them to {@link WeaponInspectService}, which owns the whole authoritative decision including which clip and
 * which duration apply. The client handler only starts the clip and the sound the server approved.</p>
 */
public final class WeaponInspectProtocol {

    private WeaponInspectProtocol() {
    }

    /**
     * Declares both inspection payload types through the networking service.
     *
     * @param network the loader's networking service
     * @param client  {@code true} on a physical client, which also needs the outcome handler
     */
    public static void declarePayloads(INetworkService network, boolean client) {
        network.declareServerbound(INetworkService.Phase.PLAY, WeaponInspectPayload.TYPE, WeaponInspectPayload.CODEC,
                WeaponInspectProtocol::onRequest);
        network.declareClientbound(INetworkService.Phase.PLAY, WeaponInspectResultPayload.TYPE,
                WeaponInspectResultPayload.CODEC, client ? WeaponInspectProtocol::onResult : null);
    }

    /**
     * Server side: handles one inspection request.
     *
     * @param payload the request
     * @param context the connection it arrived on
     */
    private static void onRequest(WeaponInspectPayload payload, INetworkService.ServerContext context) {
        ServerPlayer player = context.player();
        if (player == null) {
            return;
        }
        var result = WeaponInspectService.request(player, payload.hand(), payload.sequence());
        if (result != com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult.OK) {
            Constants.LOG.debug("[ARG] The inspection request {} from {} was refused: {}", payload.sequence(),
                    player.getName().getString(), result);
        }
    }

    /**
     * Client side: one inspection outcome arrived.
     *
     * @param payload the outcome
     * @param reply   unused; an outcome needs no answer
     */
    private static void onResult(WeaponInspectResultPayload payload, INetworkService.ReplySender reply) {
        ClientSide.onResult(payload);
    }

    /**
     * The client-only call site, kept in its own class so a dedicated server can declare the types without resolving the
     * client input class and everything it touches.
     */
    private static final class ClientSide {

        private ClientSide() {
        }

        private static void onResult(WeaponInspectResultPayload payload) {
            com.arg_studios.arg_weaponry.client.input.WeaponInspectInput.onResult(payload);
        }
    }
}
