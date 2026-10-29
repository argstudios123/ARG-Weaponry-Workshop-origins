package com.arg_studios.arg_weaponry.client.audio;

import com.arg_studios.arg_weaponry.ArgWeaponry;
import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.PackSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The content packs presented to Minecraft as resource packs, so their sound files become playable.
 *
 * <h2>Why the client needs this</h2>
 * <p>Weapon sounds are declared by identifier in the weapon definition and the {@code .ogg} files live inside the
 * content pack. Minecraft's sound engine can only resolve an event whose namespace has a {@code sounds.json} in the
 * client's resource stack, so the pack has to participate in that stack. {@link ContentPackClientPack} does that per pack
 * and {@code PackRepositoryMixin} appends the result to the packs vanilla opens for a reload - the same list that
 * already contains vanilla and every resource pack the player enabled, which is why content packs come last and cannot
 * override vanilla resources.</p>
 *
 * <h2>Failure policy</h2>
 * <p>Building the list never throws: a pack whose folder cannot be read is skipped with one warning, because a missing
 * sound must never keep a weapon - or the game - from loading. The list is rebuilt from {@link ArgWeaponry#contentPacks()}
 * on every call, which keeps it in sync when a reload happens after the inventory changed, and it is cheap because a
 * pack is only wrapped, not scanned.</p>
 */
public final class ContentPackClientPacks {

    /** The pack source shown for these packs; no UI lists them, so the label is only for diagnostics. */
    private static final PackSource SOURCE = PackSource.BUILT_IN;

    private ContentPackClientPacks() {
    }

    /**
     * @return one resource pack per content pack that provides an {@code assets} directory, in inventory order; never
     *         {@code null}, empty when no pack qualifies
     */
    public static List<PackResources> packs() {
        List<ContentPack> contentPacks;
        try {
            contentPacks = ArgWeaponry.contentPacks();
        } catch (RuntimeException e) {
            Constants.LOG.warn("[ARG] The content pack inventory is not available ({}); content-pack sounds stay silent",
                    e.getMessage());
            return List.of();
        }

        List<PackResources> resources = new ArrayList<>(contentPacks.size());
        for (ContentPack pack : contentPacks) {
            Path root = pack.root();
            if (root == null || !Files.isDirectory(root.resolve("assets"))) {
                continue;
            }
            try {
                PackLocationInfo location = new PackLocationInfo("arg_weaponry/" + pack.id(),
                        Component.literal(pack.id()), SOURCE, Optional.empty());
                resources.add(new ContentPackClientPack(location, root, pack));
            } catch (RuntimeException e) {
                Constants.LOG.warn("[ARG] Content pack '{}' could not be exposed as a resource pack ({}); its sounds "
                        + "stay silent", pack.id(), e.getMessage());
            }
        }
        return List.copyOf(resources);
    }
}
