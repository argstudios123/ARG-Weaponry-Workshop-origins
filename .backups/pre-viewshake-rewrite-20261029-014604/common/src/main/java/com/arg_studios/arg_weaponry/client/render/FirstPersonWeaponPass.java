package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.client.animation.AnimationTester;
import com.arg_studios.arg_weaponry.client.camera.FirstPersonCamera;
import com.arg_studios.arg_weaponry.client.animation.WeaponViewControllers;
import com.arg_studios.arg_weaponry.client.aiming.WeaponAiming;
import com.arg_studios.arg_weaponry.client.recoil.WeaponKickbackAxis;
import com.arg_studios.arg_weaponry.client.recoil.WeaponKickbackController;
import com.arg_studios.arg_weaponry.client.recoil.WeaponRecoil;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

/**
 * Submits the held ARG weapon(s) for the first-person pass, in clean camera space.
 *
 * <h2>Where this is called, and why exactly there</h2>
 * <p>{@code GameRenderer.renderItemInHand} builds the first-person pose in two stages. It starts a fresh
 * {@link PoseStack} and multiplies in the inverse of {@link CameraRenderState#viewRotationMatrix}, which
 * gives Minecraft's camera space; after that it applies the transformations that belong to the
 * <em>vanilla hand</em> only:</p>
 * <ul>
 *   <li>{@code bobHurt} - the damage tilt,</li>
 *   <li>{@code bobView} - the walking view bob, gated by the {@code View Bobbing} option, and</li>
 *   <li>the two {@code 0.1} view-lag rotations in
 *       {@code FirstPersonHandsAndItemsRenderer.submitHandsWithItems}.</li>
 * </ul>
 * <p>A weapon whose pose is entirely defined by its own {@code default_view} bone must receive none of
 * them, otherwise the model sways relative to the crosshair on top of its own animation. This class is
 * therefore called from {@code GameRendererFirstPersonMixin} at the point where the pose is still
 * camera space - immediately before {@code bobHurt} - and rebuilds that pose from the camera's view
 * rotation matrix, which is the same matrix vanilla multiplies in.</p>
 *
 * <p>The alternative - drawing inside {@code submitArmWithItem} and undoing the transforms that were
 * applied before it - would have to guess what the pose contains, so it is deliberately not used.
 * Nothing global is disabled: the {@code View Bobbing} option, the camera orientation and every
 * ordinary item keep the vanilla path untouched, and the vanilla hand pass is only cancelled for a
 * weapon stack that this pass can draw (see {@link WeaponRenderer#isFirstPersonRenderable}).</p>
 *
 * <h2>The weapon owns its placement; there is no vanilla hand transform</h2>
 * <p>Between this camera space and the first vertex, {@link ViewBoneTransform} applies exactly three
 * things, once each: the global model-to-view scale, the Bedrock-to-Minecraft X handedness conversion,
 * and the inverse of the {@code default_view} anchor frame. Nothing else is added.</p>
 *
 * <p>In particular the vanilla arm transform, the item's {@code firstperson_righthand} display
 * transform and the attack swing are <em>not</em> reproduced. {@code default_view} is authored as the
 * eye the weapon is seen from - the shipped M100 DMR stores it at the same pivot as its {@code camera}
 * animation bone, and its {@code aiming_view} sits behind the sights - so the anchor already expresses
 * "where the camera is relative to the gun". Adding a hand or display-context transform on top would
 * place the weapon a second time, which is precisely the class of duplicated transform this milestone
 * had to remove. That is also why the display definition needs no {@code firstperson_righthand} entry:
 * the per-context transforms in it describe how the <em>item</em> is held, and a model-driven view has
 * no item pose to describe. {@link com.arg_studios.arg_weaponry.client.display.WeaponDisplay} keeps
 * parsing them for the third-person and GUI work that will need them; this pass does not read them.</p>
 *
 * <p>Both hands go through the identical chain. The hand decides which stack is drawn and which
 * controller advances, not how the weapon is placed, so a weapon is never mirrored, rotated by 180
 * degrees or offset for the off hand, and switching hands cannot accumulate transforms: every
 * submission pushes its own pose and pops it again. Handedness lives in the model (the anchor the
 * author placed), so a future left-handed-specific view would be a model-side anchor rather than a
 * hidden renderer rule.</p>
 *
 * <h2>Once per hand per frame</h2>
 * <p>This is the only place that advances gameplay weapon playback, and it runs once per rendered hand
 * per frame, exactly as the previous hook in {@code submitArmWithItem} did. Pose precedence is
 * unchanged: the Animation Tester wins while it is running, then the development playback fixture, and
 * only then the held weapon's own {@link WeaponViewControllers} controller, which is still updated
 * before that choice so its clock stays current.</p>
 */
public final class FirstPersonWeaponPass {

    private FirstPersonWeaponPass() {
    }

    /**
     * Submits both selected hands' weapons into the first-person pass.
     *
     * @param playerState the player render state of this frame
     * @param cameraState the camera render state, the source of camera space
     * @param collector   the hand-and-screen submit node collector
     */
    public static void submit(PlayerRenderState playerState, CameraRenderState cameraState,
                              SubmitNodeCollector collector) {
        if (playerState == null || cameraState == null || collector == null) {
            return;
        }
        FirstPersonHandsAndItemsRenderState hands = playerState.firstPersonHandsAndItems;
        if (hands == null || hands.isScoping) {
            return;
        }
        // The hand selection is what keeps F1, the "hide hand" paths and the off-hand swap behaving the
        // way vanilla does; without it there would be no statement about which hands to draw at all.
        FirstPersonHandsAndItemsRenderState.HandRenderSelection selection = hands.handRenderSelection;
        if (selection == null || playerState.avatarRenderState == null) {
            return;
        }

        int lightCoords = playerState.avatarRenderState.lightCoords;
        PoseStack poseStack = cameraSpace(cameraState);

        // The arm rig is resolved once per frame from the same render state vanilla's own first-person pass uses,
        // so the skin, the skin model type (classic or slim) and the sleeve settings are the live ones.
        PlayerArmRig.Rig armRig = PlayerArmRig.Rig.of(playerState.avatarRenderState);

        if (selection.renderMainHand) {
            submitHand(InteractionHand.MAIN_HAND, hands.mainHandItem, poseStack, collector, lightCoords, armRig,
                    cameraState);
        }
        if (selection.renderOffHand) {
            submitHand(InteractionHand.OFF_HAND, hands.offHandItem, poseStack, collector, lightCoords, armRig,
                    cameraState);
        }
    }

    /**
     * Builds the pose vanilla holds right after its inverse view rotation.
     *
     * <p>{@link CameraRenderState#viewRotationMatrix} is the inverse of the camera's world rotation, so
     * inverting it yields camera space. This is the same matrix multiplication
     * {@code renderItemInHand} performs, minus everything that follows it.</p>
     *
     * @param cameraState the camera render state of this frame
     * @return a fresh pose stack in camera space
     */
    private static PoseStack cameraSpace(CameraRenderState cameraState) {
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(new Matrix4f(cameraState.viewRotationMatrix).invert());
        return poseStack;
    }

    /**
     * Evaluates the pose of one hand and submits its weapon.
     *
     * @param hand        the hand being rendered
     * @param stack       the exact stack held in that hand
     * @param poseStack   the camera-space pose stack, which the renderer pushes and pops
     * @param collector   the hand-and-screen submit node collector
     * @param lightCoords the packed light of the player
     * @param armRig      the frame's player-skin arm rig, passed through to the renderer for arm rig bones
     */
    private static void submitHand(InteractionHand hand, ItemStack stack, PoseStack poseStack,
                                   SubmitNodeCollector collector, int lightCoords, PlayerArmRig.Rig armRig,
                                   CameraRenderState cameraState) {
        if (!(stack.getItem() instanceof WeaponItem weaponItem)) {
            return;
        }

        // Development-only playback: advances one controller once per frame and hands the evaluated pose
        // to the renderer. Both are no-ops unless -Darg_weaponry.dev_weapon_playback=true.
        DevelopmentAnimationPlayback.beginFrame(weaponItem.pack(), weaponItem.definition());
        // Development-only tester: takes precedence over the fixed playback fixture when it is running.
        AnimationTester.onWeaponFrame(weaponItem.pack(), weaponItem.definition());

        // Gameplay path: the held weapon's own view controller owns idle playback. It is updated exactly
        // once per hand per frame, before the development poses are preferred below, so its clock stays
        // current and closing a development pose does not jump.
        WeaponPose gameplayPose = WeaponViewControllers.instance().poseFor(hand, weaponItem, stack,
                cameraState.cameraEntityPartialTicks);

        WeaponPose pose = AnimationTester.instance().poseFor(weaponItem.definition());
        if (pose == null) {
            pose = DevelopmentAnimationPlayback.currentPose();
        }
        if (pose == null) {
            pose = gameplayPose;
        }

        // The camera-bone animation is evaluated on the client tick (WeaponViewControllers#tickHeldWeapons) from the same
        // pose this frame draws, so it is already current here: pressing F5 cannot freeze it, and the level camera no
        // longer applies the previous frame's value.

        // The weapon is drawn in camera space built from the camera's *base* rotation, so the camera animation has to be
        // applied to this pose stack as well - once, before the anchor transform, and with the translation negated. The
        // level camera receives the same rotation, which is what keeps the weapon locked to the world while the view
        // moves.
        poseStack.pushPose();
        try {
            FirstPersonCamera.applyToViewModel(poseStack, cameraState.cameraEntityPartialTicks);

            // The placement this frame will use. It is resolved once and shared by the kickback axis and the submission,
            // so the direction the weapon recoils along is by construction the direction the weapon is actually drawn
            // with - including the ADS blend.
            org.joml.Matrix4f placement =
                    aimingPlacement(hand, weaponItem, cameraState.cameraEntityPartialTicks);
            if (placement == null) {
                WeaponModel model = WeaponAiming.modelFor(weaponItem);
                placement = model == null ? null
                        : ViewPlacement.placement(model, ModelBoneNames.DEFAULT_VIEW);
            }

            // Recoil kickback: a displacement backwards along the weapon's own longitudinal axis, composed on top of the
            // camera-bone rotation above and before the placement below, so it composes with ADS instead of replacing it
            // and recovers into whatever pose is current. It is sampled from elapsed time (never accumulated), so a
            // repeated sample of the same instant returns the same transform.
            WeaponKickbackController.Kickback kickback = WeaponRecoil.kickback(cameraState.cameraEntityPartialTicks);
            if (!kickback.isZero()) {
                Vector3f backward = WeaponKickbackAxis.offset(placement, kickback.backwardBlocks());
                poseStack.translate(backward.x, backward.y, backward.z);
            }

            // Aim-down-sights: the ordinary placement and the weapon's own aiming_view placement, blended by the eased
            // transition progress. Both come from the model geometry, so no sight offset is invented here, and the blended
            // matrix replaces the single placement the renderer would apply - nothing is applied twice.
            WeaponRenderer.submitFirstPerson(weaponItem, poseStack, collector, lightCoords, pose, armRig, placement);
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Resolves the placement of one hand: the ordinary anchor, or the blend towards {@code aiming_view} while aiming.
     *
     * <p>Only the main hand can aim, and only when the weapon both enables aiming and actually has the bone; every other
     * case returns {@code null}, which makes the renderer use the weapon's own {@code default_view} anchor exactly as
     * before.</p>
     *
     * @param hand        the hand being rendered
     * @param weaponItem  the weapon held there
     * @param partialTick the fraction of a client tick elapsed
     * @return the placement to apply, or {@code null} for the ordinary anchor
     */
    private static org.joml.Matrix4f aimingPlacement(InteractionHand hand, WeaponItem weaponItem, float partialTick) {
        if (hand != InteractionHand.MAIN_HAND) {
            return null;
        }
        float progress = WeaponAiming.easedProgress(partialTick);
        if (progress <= 0.0F) {
            return null;
        }
        WeaponModel model = WeaponAiming.modelFor(weaponItem);
        if (!WeaponAiming.hasAimingBone(model)) {
            return null;
        }
        return ViewPlacement.blend(
                ViewPlacement.placement(model, ModelBoneNames.DEFAULT_VIEW),
                ViewPlacement.placement(model, ModelBoneNames.AIMING_VIEW),
                progress);
    }
}
