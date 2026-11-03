package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.weapon.fire.WeaponFireModeService;
import com.arg_studios.arg_weaponry.weapon.fire.WeaponFireSessions;
import net.minecraft.server.level.ServerPlayer;

/**
 * The play-phase glue for trigger sessions and firing-mode selection.
 *
 * <p>Introduced by Milestone 10Q. Both requests are pure intent: the trigger payload names a hand, an edge and a press
 * sequence, the mode payload names a hand and a request sequence. Neither carries a weapon identifier, a mode, a rate,
 * a damage value or a burst length - {@link WeaponFireSessions} and {@link WeaponFireModeService} resolve all of that
 * from the server's own definition and the held stack, exactly like the semi-automatic path.</p>
 *
 * <p>The two clientbound handlers only update client-side bookkeeping and feedback, so they are registered with
 * handlers only on a physical client; a dedicated server still declares the types because it sends them.</p>
 */
public final class WeaponTriggerProtocol {

    private WeaponTriggerProtocol() {
    }

    /**
     * Declares the four trigger/mode payload types through the networking service.
     *
     * @param network the loader's networking service
     * @param client  {@code true} on a physical client, which also needs the two result handlers
     */
    public static void declarePayloads(INetworkService network, boolean client) {
        network.declareServerbound(INetworkService.Phase.PLAY, WeaponTriggerPayload.TYPE, WeaponTriggerPayload.CODEC,
                WeaponTriggerProtocol::onTrigger);
        network.declareServerbound(INetworkService.Phase.PLAY, WeaponFireModePayload.TYPE, WeaponFireModePayload.CODEC,
                WeaponTriggerProtocol::onCycleMode);
        network.declareClientbound(INetworkService.Phase.PLAY, WeaponFireSessionPayload.TYPE,
                WeaponFireSessionPayload.CODEC, client ? WeaponTriggerProtocol::onSessionUpdate : null);
        network.declareClientbound(INetworkService.Phase.PLAY, WeaponFireModeResultPayload.TYPE,
                WeaponFireModeResultPayload.CODEC, client ? WeaponTriggerProtocol::onModeResult : null);
    }

    /**
     * Server side: one trigger edge or heartbeat.
     *
     * <p>The session code answers presses, heartbeats and releases itself and reports back through
     * {@link WeaponFireSessionPayload}; a refusal is therefore visible to the client instead of being swallowed, which
     * is what lets the client stop a pointless heartbeat loop.</p>
     *
     * @param payload the payload
     * @param context the connection it arrived on
     */
    private static void onTrigger(WeaponTriggerPayload payload, INetworkService.ServerContext context) {
        ServerPlayer player = context.player();
        if (player == null) {
            return;
        }
        if (payload.pressed()) {
            WeaponFireSessions.start(player, payload.hand(), payload.sequence());
        } else {
            WeaponFireSessions.release(player, payload.hand(), payload.sequence());
        }
    }

    /**
     * Server side: one firing-mode selection request.
     *
     * @param payload the payload
     * @param context the connection it arrived on
     */
    private static void onCycleMode(WeaponFireModePayload payload, INetworkService.ServerContext context) {
        ServerPlayer player = context.player();
        if (player == null) {
            return;
        }
        WeaponFireModeService.cycle(player, payload.hand(), payload.sequence());
    }

    /**
     * Client side: the state of a trigger session changed.
     *
     * @param payload the update
     * @param reply   unused
     */
    private static void onSessionUpdate(WeaponFireSessionPayload payload, INetworkService.ReplySender reply) {
        ClientSide.onSessionUpdate(payload);
    }

    /**
     * Client side: a firing-mode request was answered.
     *
     * @param payload the answer
     * @param reply   unused
     */
    private static void onModeResult(WeaponFireModeResultPayload payload, INetworkService.ReplySender reply) {
        ClientSide.onModeResult(payload);
    }

    /**
     * The client-only call sites, kept in their own class so a dedicated server can declare the types without ever
     * resolving {@code WeaponFireInput} or {@code WeaponFireModeInput} and the client classes they use.
     */
    private static final class ClientSide {

        private ClientSide() {
        }

        private static void onSessionUpdate(WeaponFireSessionPayload payload) {
            com.arg_studios.arg_weaponry.client.input.WeaponFireInput.onSessionUpdate(payload.hand(),
                    payload.sequence(), payload.result(), payload.active());
        }

        private static void onModeResult(WeaponFireModeResultPayload payload) {
            com.arg_studios.arg_weaponry.client.input.WeaponFireModeInput.onResult(payload.hand(), payload.sequence(),
                    payload.result(), payload.mode());
        }
    }
}
