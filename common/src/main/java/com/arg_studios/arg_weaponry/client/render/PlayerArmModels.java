package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.Constants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.world.entity.player.PlayerModelType;

/**
 * The private player models the first-person arms are drawn from.
 *
 * <h2>Why a private model and not the shared one</h2>
 * <p>Vanilla's {@code AvatarRenderer#renderRightHand} submits the <em>renderer's own</em> {@code ModelPart}, and
 * {@code AvatarRenderer#renderHand} poses that part when it submits it:</p>
 * <ul>
 *   <li>it sets {@code leftArm.zRot = -0.1} and {@code rightArm.zRot = 0.1} - a 5.7 degree lean that tilts the arm about
 *   its shoulder and moves its centre out of the box that was measured for the placement;</li>
 *   <li>it forces <em>both</em> sleeves' visibility from the submitted side's flag;</li>
 *   <li>it resets the arm pose, so any pose the caller set is overwritten.</li>
 * </ul>
 * <p>Worse, the submission is deferred: {@code submitModelPart} stores the {@code ModelPart} and the draw reads it at the
 * end of the frame, so any later code that poses the same part - including vanilla's own hand pass for the other hand, or
 * a third-person render of the local player - changes the arm this renderer already submitted. That is exactly the class of
 * "unwanted additional transformation" Milestone 10R has to remove: the arm must be drawn with the pose the placement was
 * solved for, and with nothing else.</p>
 *
 * <p>This class therefore bakes a dedicated {@link PlayerModel} per skin type from the vanilla layer definitions
 * ({@code ModelLayers.PLAYER} and {@code PLAYER_SLIM} - so classic and slim arms keep their real geometry, including the
 * one-pixel difference) and hands its arm parts to {@link PlayerArmRig}. Nobody else ever touches those parts, so the drawn
 * pose is exactly the authored pose: initial arm rotation, no lean, and the authored arm-bone animation applied by the bone
 * transform above it.</p>
 *
 * <h2>Lifecycle</h2>
 * <p>Baked lazily on first use, because {@code EntityModelSet} is only available once the client exists, and dropped by
 * {@code WeaponRenderer.invalidateCaches()} so a resource reload rebakes them from the new model definitions.</p>
 */
public final class PlayerArmModels {

    private static PlayerModel classic;
    private static PlayerModel slim;

    private PlayerArmModels() {
    }

    /**
     * @param type the skin's model type, may be {@code null} to mean classic
     * @return the private player model for that type, or {@code null} when it could not be baked
     */
    public static synchronized PlayerModel get(PlayerModelType type) {
        boolean slimSkin = type == PlayerModelType.SLIM;
        PlayerModel cached = slimSkin ? slim : classic;
        if (cached != null) {
            return cached;
        }
        Minecraft client = Minecraft.getInstance();
        EntityModelSet models = client.getEntityModels();
        if (models == null) {
            return null;
        }
        try {
            PlayerModel baked = new PlayerModel(
                    models.bakeLayer(slimSkin ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slimSkin);
            baked.resetPose();
            if (slimSkin) {
                slim = baked;
            } else {
                classic = baked;
            }
            Constants.LOG.debug("[ARG] Baked a private {} player model for the first-person arms",
                    slimSkin ? "slim" : "classic");
            return baked;
        } catch (RuntimeException e) {
            Constants.LOG.warn("[ARG] The private {} player model could not be baked ({}); the arm rig bones keep their "
                    + "placeholder cubes hidden", slimSkin ? "slim" : "classic", e.getMessage());
            return null;
        }
    }

    /** Drops both baked models; called when client resources are reloaded. */
    public static synchronized void invalidate() {
        classic = null;
        slim = null;
    }
}
