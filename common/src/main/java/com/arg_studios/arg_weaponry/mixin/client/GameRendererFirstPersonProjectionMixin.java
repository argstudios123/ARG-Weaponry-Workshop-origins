package com.arg_studios.arg_weaponry.mixin.client;

import com.arg_studios.arg_weaponry.client.render.FirstPersonProjection;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Tightens the near plane of the first-person hand/screen projection.
 *
 * <p>{@code GameRenderer#render3dHud} is the single place vanilla prepares the projection used by
 * {@code renderItemInHand} and the screen-effect pass. Its near plane is {@code 0.05} blocks, which is larger than the
 * distance from the eye to some legitimately authored weapon parts and therefore clips them. Changing only this argument
 * leaves the world camera projection, the hand pass's depth attachment and the depth test untouched.</p>
 *
 * <p>The value comes from {@link FirstPersonProjection}, which also preserves a near plane that is already tighter and
 * ignores a malformed one, so this hook can never make the projection invalid.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFirstPersonProjectionMixin {

    /**
     * Replaces the near-plane argument of the hand pass's {@code Projection#setupPerspective} call.
     *
     * @param nearPlane the vanilla near plane
     * @return the tightened first-person near plane
     */
    @ModifyArg(method = "render3dHud",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/Projection;setupPerspective(FFFFF)V"),
            index = 0)
    private float argWeaponry$tightenFirstPersonNearPlane(float nearPlane) {
        return FirstPersonProjection.nearPlane(nearPlane);
    }
}
