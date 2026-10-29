package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.compat.WeaponManifest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: the client's own weapon manifest, sent in answer to {@link WeaponManifestPayload}.
 *
 * <p>The client reports facts only — which item identifiers it has registered and what it computed for them.
 * It never reports a verdict: the server compares the two manifests itself, so a modified client cannot talk
 * its way into a connection, and the client never influences which weapon definition the server uses.</p>
 *
 * @param manifest the client's manifest
 */
public record WeaponManifestReplyPayload(WeaponManifest manifest) implements CustomPacketPayload {

    /** The payload identifier. */
    public static final Type<WeaponManifestReplyPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_manifest_reply"));

    /** The codec; it writes the manifest verbatim and enforces its bounds. */
    public static final StreamCodec<FriendlyByteBuf, WeaponManifestReplyPayload> CODEC =
            WeaponManifest.CODEC.map(WeaponManifestReplyPayload::new, WeaponManifestReplyPayload::manifest);

    public WeaponManifestReplyPayload {
        if (manifest == null) {
            throw new IllegalArgumentException("A weapon manifest reply must carry a manifest");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
