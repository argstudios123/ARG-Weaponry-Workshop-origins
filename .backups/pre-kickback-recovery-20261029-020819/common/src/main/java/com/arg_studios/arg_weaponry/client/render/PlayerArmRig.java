package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames.ArmSide;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.EnumSet;

/**
 * Draws the player's own arm at an arm rig bone of a weapon model.
 *
 * <h2>Why the arm is not part of the weapon mesh</h2>
 * <p>A Bedrock weapon model meant for first person contains the arms as placeholder cubes: the shipped M100 DMR's
 * {@code right_hand} and {@code left_hand} bones each hold one {@code 4x12x4} cube, which is the size of a vanilla
 * arm, unwrapped into a 16x16 block of the weapon atlas. Those cubes are rig references, not weapon surfaces, so
 * they are not drawn with the weapon texture; the player's real arm is drawn at the same bone instead, which keeps
 * the authored pose, the authored animation (the reload clips animate exactly those two bones) and the authored
 * hierarchy intact.</p>
 *
 * <p>This is the behaviour of the reference implementation: TaCZ registers {@code RightHandRender} and
 * {@code LeftHandRender} functional submitters for its {@code righthand_pos}/{@code lefthand_pos} bones and they end
 * up in {@code RenderHelper#renderFirstPersonArm}, which resolves the player's {@code AvatarRenderer}, reads the skin
 * texture from the player's skin and calls {@code renderRightHand}/{@code renderLeftHand}. This class does the same
 * with this project's APIs: the {@link Rig} it receives is built from the frame's {@link AvatarRenderState}, which is
 * the exact source vanilla itself uses in {@code FirstPersonHandsAndItemsRenderer#renderPlayerHand}, so the skin, the
 * <em>skin model type</em> (classic or slim) and the sleeve settings are the real ones - nothing is inferred from the
 * texture size or rebuilt as a cube.</p>
 *
 * <h2>Placement</h2>
 * <p>{@code renderRightHand} submits the vanilla player model's arm part where the player model itself puts it: a
 * cube at {@code x in [-3, 1], y in [-2, 10], z in [-2, 2]} in the arm's own space (Y down) plus the part offset
 * {@code (-5, 2, 0)}, so it occupies {@code x in [-8, -4], y in [0, 12], z in [-2, 2]} pixels - and, because
 * {@code ModelPart} divides its geometry by 16, {@code x in [-0.5, -0.25], y in [0, 0.75], z in [-0.125, 0.125]}
 * blocks underneath the pose stack. A slim skin's arm is one pixel narrower ({@code x in [-7, -4]}), which the same
 * code places without any width constant.</p>
 *
 * <p>The placement is not a set of offsets: {@link ArmPlacement} measures that box from the live part, finds the
 * reference cube of the arm rig bone ({@code right_hand}/{@code left_hand}), and derives the translation that centres
 * the arm on it plus the {@code 1/16} scale that converts blocks into the weapon model's units. Milestones up to 10O
 * used a hand-written {@code translate(+-6, +2, 0)} and no unit conversion at all; as a result the arm was drawn at
 * {@code 1/16} of its intended size and the constant translation then dominated it - the "tiny arm near the top
 * right of the screen". See {@link ArmPlacement} for the derivation, including why the {@code diag(1, -1, 1)} flip is
 * required for both the axis convention and the winding. The bone's parent chain, pivot, rest rotation and animated
 * rotation/offset are applied by {@code WeaponRenderer} exactly once, as the frame the arm is submitted in, so they
 * do not appear here at all.</p>
 *
 * <h2>State safety, and why the submitted arm is left in its placed state</h2>
 * <p>{@code AvatarRenderer#renderHand} mutates the shared player model: it resets the submitted arm's pose, sets its
 * {@code visible} flag and a {@code +-0.1} radian {@code zRot} lean, and sets both sleeves' {@code visible} flags.
 * Everything it touches is written deliberately before the submit:</p>
 * <ul>
 *   <li>the arm is reset to its initial pose and its lean is cleared, so the box that was measured is the box that is
 *   drawn (TaCZ's {@code RenderHelper} resets the same lean, {@code resetFirstPersonArmLean});</li>
 *   <li>the sleeve of the drawn side is set from the player's skin settings;</li>
 *   <li>the <em>other</em> arm's {@code visible}/lean and the <em>other</em> sleeve's {@code visible} - which
 *   {@code renderHand} also writes - are restored from the values captured before the submit.</li>
 * </ul>
 * <p>The submitted arm itself keeps the state the placement was solved for instead of being restored. That is not an
 * oversight: the submission is deferred ({@code SubmitNodeCollector#submitModelPart} stores a reference to the
 * {@code ModelPart}, and {@code ModelFeatureRenderer} reads it when the hand pass is drawn, after this method has
 * returned), so restoring the arm would make the drawn pose depend on whatever the model was left in by an earlier
 * draw. Leaving it in a defined state - initial pose, no lean, visible, sleeve per the skin - is what vanilla's own
 * {@code renderHand} does, minus the lean. The pose stack is pushed and popped around the submit.</p>
 */
public final class PlayerArmRig {

    /** Sides whose solved placement has already been written to the log since the last resource reload. */
    private static final EnumSet<ArmSide> reportedSubmits = EnumSet.noneOf(ArmSide.class);

    private static boolean reportedMissing;

    private PlayerArmRig() {
    }

    /**
     * Everything one arm submit needs, resolved once per rendered frame from the frame's render state.
     *
     * <p>These are exactly the values vanilla's own first-person pass uses
     * ({@code FirstPersonHandsAndItemsRenderer} reads {@code avatarRenderState.skin},
     * {@code showRightSleeve}/{@code showLeftSleeve} and the avatar renderer), which is what makes classic and slim
     * skins, custom skins and the sleeve settings work without any assumption about arm width.</p>
     *
     * @param skin        the player's skin, whose {@code model()} reports classic or slim
     * @param rightSleeve whether the right sleeve layer is shown
     * @param leftSleeve  whether the left sleeve layer is shown
     */
    public record Rig(PlayerSkin skin, boolean rightSleeve, boolean leftSleeve) {

        /**
         * Builds the rig of one frame, or {@code null} when the frame has no avatar to take a skin from.
         *
         * <p>Only the skin and the two sleeve flags are needed: the geometry comes from
         * {@link PlayerArmModels#get(net.minecraft.world.entity.player.PlayerModelType)}, which picks the classic or slim
         * layer from {@code skin.model()}. The frame's {@code AvatarRenderer} is deliberately no longer used - see
         * {@link PlayerArmModels} for why sharing its model parts was the source of the unwanted arm transformations.</p>
         *
         * @param state the avatar render state of the local player, may be {@code null}
         * @return the rig, or {@code null} when arms cannot be drawn this frame
         */
        public static Rig of(AvatarRenderState state) {
            if (state == null || state.skin == null || state.skin.body() == null) {
                return null;
            }
            return new Rig(state.skin, state.showRightSleeve, state.showLeftSleeve);
        }
    }

    /**
     * Draws the skin arm of one side at the current pose.
     *
     * <p>The caller has already applied the bone's transform, so this method only measures the native arm, solves the
     * placement against that bone's reference cube, applies it and submits the arm. The pose stack is popped and the
     * player-model values {@code renderHand} writes for the <em>other</em> limb are restored; the submitted arm keeps
     * the state the placement was solved for - see the class documentation for why that is required.</p>
     *
     * @param rig         the frame's arm rig, may be {@code null} when the frame has no avatar
     * @param side        the arm to draw, never {@link ArmSide#NONE}
     * @param bone        the arm rig bone whose cubes are the reference frame
     * @param poseStack   the pose stack positioned at the arm bone
     * @param collector   the submit node collector of the first-person pass
     * @param lightCoords the packed light of the player
     * @return {@code true} when an arm was submitted
     */
    public static boolean submit(Rig rig, ArmSide side, ModelBone bone, PoseStack poseStack,
                                 SubmitNodeCollector collector, int lightCoords) {
        if (side == null || side == ArmSide.NONE || poseStack == null || collector == null) {
            return false;
        }
        if (rig == null) {
            reportMissing("this frame has no avatar render state");
            return false;
        }
        if (rig.skin() == null || rig.skin().body() == null || rig.skin().body().texturePath() == null) {
            reportMissing("the player has no skin texture");
            return false;
        }
        PlayerModel model = PlayerArmModels.get(rig.skin().model());
        if (model == null) {
            reportMissing("a private player model for the " + rig.skin().model() + " skin type could not be baked");
            return false;
        }
        ArmPlacement.Box reference = ArmPlacement.referenceBox(bone);
        if (reference == null) {
            reportMissing("the arm rig bone '" + bone.name() + "' carries no cube geometry to align with");
            return false;
        }

        boolean rightArm = side == ArmSide.RIGHT;
        ModelPart arm = rightArm ? model.rightArm : model.leftArm;
        ModelPart sleeve = rightArm ? model.rightSleeve : model.leftSleeve;
        boolean showSleeve = rightArm ? rig.rightSleeve() : rig.leftSleeve();

        // The pose the placement is solved for is the pose that will be drawn. Nothing can change it afterwards,
        // because these model parts belong to this class alone (PlayerArmModels).
        resetToPlacedPose(arm);
        sleeve.visible = showSleeve;
        ArmPlacement.Box nativeBox = measuredBox(arm);
        ArmPlacement.Placement placement = ArmPlacement.solve(reference, nativeBox);
        if (placement == null) {
            reportMissing("the native arm geometry could not be measured");
            return false;
        }

        poseStack.pushPose();
        try {
            placement.apply(poseStack);
            arm.visible = true;
            // Submitted directly instead of through AvatarRenderer#renderRightHand, so vanilla's own hand posing (the
            // +-0.1 radian lean and the forced sleeve flags) never reaches this model part. The render type, the light
            // and the overlay match what renderHand uses, so the arm looks exactly like the vanilla first-person arm.
            collector.submitModelPart(arm, poseStack, RenderTypes.entityTranslucent(rig.skin().body().texturePath()),
                    lightCoords, OverlayTexture.NO_OVERLAY, null);
        } finally {
            poseStack.popPose();
        }
        reportPlacement(rig, side, bone, placement, showSleeve);
        return true;
    }

    /**
     * Puts one arm into the pose the placement is derived for: the model's initial arm pose with no lean.
     *
     * <p>{@code renderHand} resets the pose itself and then applies a {@code +-0.1} radian {@code zRot} lean. That lean
     * tilts the arm about the shoulder and would move its centre out of the reference cube, so it is cleared here -
     * the same thing TaCZ's {@code RenderHelper#resetFirstPersonArmLean} does - which keeps the measured box, the
     * solved placement and the drawn pose identical.</p>
     *
     * @param arm the arm part about to be submitted
     */
    private static void resetToPlacedPose(ModelPart arm) {
        arm.resetPose();
        arm.zRot = 0.0F;
    }

    /**
     * Measures the arm box Minecraft's own player model defines, in blocks, with the pose created by
     * {@link #resetToPlacedPose(ModelPart)}.
     *
     * <p>{@code ModelPart#getExtentsForGui} is the only public API that reports a part's geometry with the part's own
     * transform applied, and it reports it in the same unit Minecraft draws it in: {@code ModelPart.Vertex#worldX}
     * divides by 16 and {@code translateAndRotate} translates by {@code x / 16}. That is why the result is in blocks
     * and why {@link ArmPlacement} has to convert it. The sleeve child is included by that API, but it is inflated
     * symmetrically, so it cannot move the centre the placement uses.</p>
     *
     * @param arm the arm part
     * @return the box, or {@code null} when the part reports no geometry
     */
    private static ArmPlacement.Box measuredBox(ModelPart arm) {
        float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        arm.getExtentsForGui(new PoseStack(), corner -> {
            bounds[0] = Math.min(bounds[0], corner.x());
            bounds[1] = Math.min(bounds[1], corner.y());
            bounds[2] = Math.min(bounds[2], corner.z());
            bounds[3] = Math.max(bounds[3], corner.x());
            bounds[4] = Math.max(bounds[4], corner.y());
            bounds[5] = Math.max(bounds[5], corner.z());
        });
        if (!Float.isFinite(bounds[0]) || bounds[0] > bounds[3]) {
            return null;
        }
        return new ArmPlacement.Box(new Vec3(bounds[0], bounds[1], bounds[2]),
                new Vec3(bounds[3], bounds[4], bounds[5]));
    }

    /**
     * Reports the solved placement of one side once, so a log shows exactly which numbers the arm is drawn with.
     *
     * @param rig         the frame's arm rig
     * @param side        the arm that was submitted
     * @param bone        its rig bone
     * @param placement   the solved placement
     * @param showSleeve  whether the sleeve was submitted
     */
    private static void reportPlacement(Rig rig, ArmSide side, ModelBone bone, ArmPlacement.Placement placement,
                                        boolean showSleeve) {
        if (!reportedSubmits.add(side)) {
            return;
        }
        PlayerModelType type = rig.skin().model();
        Vec3 referenceCentre = placement.reference().centre();
        Vec3 referenceSize = placement.reference().size();
        Vec3 nativeCentre = placement.nativeBox().centre();
        Vec3 nativeSize = placement.nativeBox().size();
        Vec3 offset = placement.offset();
        Constants.LOG.info("[ARG] First-person {} arm '{}' is drawn from the player skin: skin model {}, sleeve {}, "
                        + "reference cube {} x {} x {} at ({}, {}, {}), native arm {} x {} x {} blocks at ({}, {}, {}), "
                        + "unit scale {}, offset ({}, {}, {})",
                side, bone.name(), type == null ? "unknown" : type, showSleeve,
                referenceSize.x(), referenceSize.y(), referenceSize.z(),
                referenceCentre.x(), referenceCentre.y(), referenceCentre.z(),
                nativeSize.x(), nativeSize.y(), nativeSize.z(),
                nativeCentre.x(), nativeCentre.y(), nativeCentre.z(),
                placement.scale(), offset.x(), offset.y(), offset.z());
    }

    private static void reportMissing(String reason) {
        if (!reportedMissing) {
            reportedMissing = true;
            Constants.LOG.warn("[ARG] Player-skin arms are unavailable because {}; the model's arm placeholder "
                    + "cubes stay hidden instead of being painted with the weapon texture", reason);
        }
    }

    /** Allows a resource reload to report the conditions again. */
    public static void resetDiagnostics() {
        reportedMissing = false;
        reportedSubmits.clear();
    }
}
