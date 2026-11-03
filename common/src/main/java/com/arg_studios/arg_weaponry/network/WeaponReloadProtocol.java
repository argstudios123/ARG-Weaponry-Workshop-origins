package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.weapon.reload.WeaponReloadService;
import net.minecraft.server.level.ServerPlayer;

/**
 * The play-phase glue for reloading: one serverbound request, one clientbound outcome.
 *
 * <p>All of the actual work lives in {@link WeaponReloadService}, which owns validation, the session, the
 * transfer and the outcome payloads. This class only declares the payload types and turns a request into a
 * service call, exactly like {@code WeaponFireProtocol} does for firing.</p>
 *
 * <p>The clientbound handler is registered from the client only, because {@code WeaponReloadInput} is
 * client-side code. On a dedicated server the handler argument is {@code null}, so no client class is resolved
 * there.</p>
 */
public final class WeaponReloadProtocol {

    private WeaponReloadProtocol() {
    }

    /**
     * Declares both reload payload types through the networking service.
     *
     * @param network the loader's networking service
     * @param client  {@code true} on a physical client, which also needs the outcome handler
     */
    public static void declarePayloads(INetworkService network, boolean client) {
        network.declareServerbound(INetworkService.Phase.PLAY, WeaponReloadPayload.TYPE, WeaponReloadPayload.CODEC,
                WeaponReloadProtocol::onRequest);
        network.declareClientbound(INetworkService.Phase.PLAY, WeaponReloadResultPayload.TYPE,
                WeaponReloadResultPayload.CODEC, client ? WeaponReloadProtocol::onResult : null);
    }

    /**
     * Server side: handles one reloading request.
     *
     * <p>Runs on the server thread, which is guaranteed by the networking service. The service validates the
     * request, starts or refuses a session and sends the outcome itself; a rejection is therefore reported to
     * the client instead of being swallowed.</p>
     *
     * @param payload the request
     * @param context the connection it arrived on
     */
    private static void onRequest(WeaponReloadPayload payload, INetworkService.ServerContext context) {
        ServerPlayer player = context.player();
        if (player == null) {
            return;
        }
        WeaponReloadService.ReloadOutcome outcome = WeaponReloadService.request(player, payload.hand(),
                payload.sequence());
        if (!outcome.started()) {
            Constants.LOG.debug("[ARG] Reload request {} from {} was refused: {}", payload.sequence(),
                    player.getName().getString(), outcome.result());
        }
    }

    /**
     * Client side: one reload outcome arrived.
     *
     * @param payload the outcome
     * @param reply   unused; an outcome needs no answer
     */
    private static void onResult(WeaponReloadResultPayload payload, INetworkService.ReplySender reply) {
        ClientSide.onResult(payload);
    }

    /**
     * The client-only call site of {@link #onResult}.
     *
     * <p>Kept in its own class so a dedicated server can register the clientbound handler without ever
     * resolving {@code WeaponReloadInput} and the client classes it uses.</p>
     */
    private static final class ClientSide {

        private ClientSide() {
        }

        private static void onResult(WeaponReloadResultPayload payload) {
            if (payload.result() == com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult.RELOAD_STARTED) {
                // A reload owns the weapon: any automatic or burst trigger state of this hand must end now, so a burst
                // can never resume after the reload just because the old trigger state is still down.
                com.arg_studios.arg_weaponry.client.input.WeaponFireInput.onReloadStarted(payload.hand());
            }
            com.arg_studios.arg_weaponry.client.input.WeaponReloadInput.onResult(payload);
        }
    }
}
