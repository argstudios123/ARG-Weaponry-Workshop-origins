package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.client.model.AmmunitionItemModels;
import com.arg_studios.arg_weaponry.client.model.WeaponItemModels;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Gives every dynamically registered weapon and ammunition item an inventory model.
 *
 * <p>Weapon and ammunition item ids come from content packs, so Minecraft finds no
 * {@code assets/<namespace>/items/<path>.json} for them and would return the missing-model placeholder.
 * Content packs are deliberately not mounted as resource packs, and Fabric offers no hook for item ids that
 * have no definition at all (its item-model modifiers only run for ids that do), so the fallback is applied at
 * the single point where Minecraft resolves an item id to a model.</p>
 *
 * <p>This is loader-independent: Fabric and NeoForge both use {@link ModelManager}, so one shared hook covers
 * both without duplicating model-selection logic. A model that a pack or resource pack does provide for an id
 * always wins, because both selections are only consulted when no model was baked for the id. The first-person
 * weapon renderer never goes through this path.</p>
 */
@Mixin(ModelManager.class)
public abstract class ModelManagerMixin {

    @Shadow
    private Map<Identifier, ItemModel> bakedItemStackModels;

    @Inject(method = "getItemModel", at = @At("HEAD"), cancellable = true)
    private void argWeaponry$provideDynamicItemModel(Identifier id, CallbackInfoReturnable<ItemModel> callbackInfo) {
        if (bakedItemStackModels == null || id == null) {
            return;
        }
        if (WeaponItemModels.isWeaponId(id)) {
            ItemModel fallback = WeaponItemModels.fallbackFor(bakedItemStackModels, id);
            if (fallback != null) {
                callbackInfo.setReturnValue(fallback);
            }
            return;
        }

        // Ammunition is a separate id set with its own display definitions, so it is resolved by its own
        // selector; weapons never reach it and ammunition never reaches the weapon placeholder logic.
        ItemModel ammunition = AmmunitionItemModels.modelFor(bakedItemStackModels, id);
        if (ammunition != null) {
            callbackInfo.setReturnValue(ammunition);
        }
    }
}
