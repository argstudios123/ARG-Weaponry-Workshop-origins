package com.arg_studios.arg_weaponry.weapon.equip;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.network.INetworkService;
import net.minecraft.server.level.ServerPlayer;

/**
 * The play-phase glue for the draw/holster feature: one serverbound payload, no clientbound payload.
 *
 * <p>Shaped exactly like the existing protocols ({@code WeaponInspectProtocol}, {@code WeaponReloadProtocol}): the handler
 * unwraps nothing but an action, a target slot and a sequence, and hands them to {@link DrawHolsterManager}, which owns the
 * whole authoritative decision. The client learns nothing back, because it needs nothing back: it predicted the window
 * itself, the clips come from its own copy of the definition, and the first-draw flag travels on the synchronised stack
 * component.</p>
 */
public final class EquipProtocol {

    private EquipProtocol() {
    }

    /**
     * Declares the equip payload type through the networking service.
     *
     * @param network the loader's networking service
     */
    public static void declarePayloads(INetworkService network) {
        network.declareServerbound(INetworkService.Phase.PLAY, EquipActionPayload.TYPE, EquipActionPayload.CODEC,
                EquipProtocol::onAction);
    }

    /**
     * Server side: one equip event arrived.
     *
     * @param payload the request
     * @param context the connection it arrived on
     */
    private static void onAction(EquipActionPayload payload, INetworkService.ServerContext context) {
        ServerPlayer player = context.player();
        if (player == null) {
            return;
        }
        switch (payload.action()) {
            case EquipActionPayload.ACTION_START_HOLSTER ->
                    DrawHolsterManager.requestHolster(player, payload.targetSlot(), payload.sequence());
            case EquipActionPayload.ACTION_CANCEL_HOLSTER ->
                    DrawHolsterManager.cancelHolster(player, payload.sequence());
            default -> Constants.LOG.debug("[ARG] Ignored an equip request with unknown action {}", payload.action());
        }
    }
}
