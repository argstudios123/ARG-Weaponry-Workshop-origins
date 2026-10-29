package com.arg_studios.arg_weaponry.network;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.compat.WeaponManifest;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server to client: the authoritative weapon manifest of the server.
 *
 * <p>Sent once per connection from inside a configuration task, so the client still has the chance to answer
 * before it is told to join the world. The payload carries only the manifest (§
 * {@link WeaponManifest}); no content-pack file travels with it.</p>
 *
 * @param manifest the server's manifest
 */
public record WeaponManifestPayload(WeaponManifest manifest) implements CustomPacketPayload {

    /** The payload identifier. */
    public static final Type<WeaponManifestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "weapon_manifest"));

    /** The codec; it writes the manifest verbatim and enforces its bounds. */
    public static final StreamCodec<FriendlyByteBuf, WeaponManifestPayload> CODEC =
            WeaponManifest.CODEC.map(WeaponManifestPayload::new, WeaponManifestPayload::manifest);

    public WeaponManifestPayload {
        if (manifest == null) {
            throw new IllegalArgumentException("A weapon manifest payload must carry a manifest");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
