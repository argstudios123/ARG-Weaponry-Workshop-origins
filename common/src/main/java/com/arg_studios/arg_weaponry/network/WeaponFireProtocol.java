package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.fire.WeaponFireService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/**
 * The play-phase glue for weapon firing: one serverbound request, one clientbound acceptance.
 *
 * <p>All of the actual work lives elsewhere. The server handler only unwraps the request and hands it to
 * {@link WeaponFireService}, which owns the whole authoritative sequence; nothing about damage, cadence,
 * ammunition or targeting is decided here, and the client handler only reports an accepted shot to the local
 * animation controller.</p>
 *
 * <p>The clientbound handler is registered from the client only, because {@code WeaponFireInput} is client-side
 * code. On a dedicated server the handler argument is {@code null}, so no client class is ever resolved
 * there.</p>
 */
public final class WeaponFireProtocol {

    private WeaponFireProtocol() {
    }

    /**
     * Declares both firing payload types through the networking service.
     *
     * @param network the loader's networking service
     * @param client  {@code true} on a physical client, which also needs the acceptance handler
     */
    public static void declarePayloads(INetworkService network, boolean client) {
        network.declareServerbound(INetworkService.Phase.PLAY, WeaponFirePayload.TYPE, WeaponFirePayload.CODEC,
                WeaponFireProtocol::onRequest);
        network.declareClientbound(INetworkService.Phase.PLAY, WeaponFireResultPayload.TYPE,
                WeaponFireResultPayload.CODEC, client ? WeaponFireProtocol::onAcceptedShot : null);
    }

    /**
     * Server side: executes one firing request.
     *
     * <p>Runs on the server thread, which is guaranteed by the networking service. A rejected request produces
     * no reply at all — the client never predicted a shot, so there is nothing to roll back — and the reason is
     * logged at debug level only, because a player clicking faster than the weapon can fire is not an error.</p>
     *
     * @param payload the request
     * @param context the connection it arrived on
     */
    private static void onRequest(WeaponFirePayload payload, INetworkService.ServerContext context) {
        ServerPlayer player = context.player();
        if (player == null) {
            return;
        }

        WeaponFireService.FireResult result = WeaponFireService.fire(player, payload.hand(), payload.sequence());
        if (!result.fired()) {
            Constants.LOG.debug("[ARG] Firing request {} from {} was refused: {}", payload.sequence(),
                    player.getName().getString(), result.result());
            return;
        }

        if (Services.NETWORK.canSendToPlayer(player, WeaponFireResultPayload.TYPE)) {
            // The client is told the server-assigned shot id, not the request sequence: automatic and burst shots are
            // scheduled by the server and have no request of their own, so one monotonically increasing id space keeps
            // the client's duplicate suppression a single "is this newer?" test for every firing mode.
            Services.NETWORK.sendToPlayer(player, new WeaponFireResultPayload(payload.hand(), result.shotId(),
                    result.hit() != null, result.headshot(), result.killed()));
        }
    }

    /**
     * Client side: one shot was accepted and executed by the server.
     *
     * @param payload the acceptance
     * @param reply   unused; the acceptance needs no answer
     */
    private static void onAcceptedShot(WeaponFireResultPayload payload, INetworkService.ReplySender reply) {
        ClientSide.onAcceptedShot(payload);
    }

    /**
     * The client-only call site of {@link #onAcceptedShot}.
     *
     * <p>Kept in its own class so a dedicated server can register the clientbound handler without ever resolving
     * {@code WeaponFireInput} and the client classes it uses: the handler is registered on both sides (the server
     * must know the payload to send it) but is only ever invoked on a client.</p>
     */
    private static final class ClientSide {

        private ClientSide() {
        }

        private static void onAcceptedShot(WeaponFireResultPayload payload) {
            com.arg_studios.arg_weaponry.client.input.WeaponFireInput.onAcceptedShot(payload.hand(),
                    payload.sequence(), payload.hit(), payload.headshot(), payload.killed());
        }
    }
}
