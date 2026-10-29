package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.audio.ContentPackClientPacks;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.PackRepository;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds the content packs to the resource stack Minecraft builds for a reload.
 *
 * <h2>What is injected, and why exactly there</h2>
 * <p>{@code PackRepository#openAllSelected()} is the single method that turns the selected packs into the
 * {@link PackResources} list the client's (and the server's) resource manager is built from. Appending the content packs
 * to that list is the least invasive way to make their sound files visible: nothing is added to the user's resource-pack
 * selection, {@code options.txt} is not touched, the resource-pack screen does not change, and vanilla's own packs keep
 * their priority because the appended packs come last.</p>
 *
 * <p>The injection is on the client mixin set only, so a dedicated server never mounts content-pack assets as client
 * resources.</p>
 *
 * <h2>Failure policy</h2>
 * <p>The whole body is defensive: if anything goes wrong the original list is returned unchanged, which is exactly the
 * behaviour before this milestone (content-pack sounds simply stay silent). A resource problem must never break the
 * resource reload itself.</p>
 */
@Mixin(PackRepository.class)
public abstract class PackRepositoryMixin {

    /**
     * Appends one resource pack per content pack to the opened pack list.
     *
     * @param callback the vanilla return value, replaced with the extended list
     */
    @Inject(method = "openAllSelected", at = @At("RETURN"), cancellable = true)
    private void argWeaponry$mountContentPacks(CallbackInfoReturnable<List<PackResources>> callback) {
        try {
            List<PackResources> original = callback.getReturnValue();
            List<PackResources> contentPacks = ContentPackClientPacks.packs();
            if (original == null || contentPacks.isEmpty()) {
                return;
            }
            List<PackResources> combined = new ArrayList<>(original.size() + contentPacks.size());
            combined.addAll(original);
            combined.addAll(contentPacks);
            callback.setReturnValue(List.copyOf(combined));
            Constants.LOG.info("[ARG] {} content pack(s) mounted as a resource stack for weapon sounds",
                    contentPacks.size());
        } catch (RuntimeException e) {
            Constants.LOG.warn("[ARG] Content packs could not be mounted as resources ({}); weapon sounds that live "
                    + "inside a pack stay silent", e.getMessage());
        }
    }
}
