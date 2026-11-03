package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.animation.AnimationTester;
import com.arg_studios.arg_weaponry.client.animation.VisualWeapons;
import com.arg_studios.arg_weaponry.client.animation.WeaponHandoffManager;
import com.arg_studios.arg_weaponry.client.camera.FirstPersonCamera;
import com.arg_studios.arg_weaponry.client.animation.FirstPersonVisualWeapons;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemDisplayContext;
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
 * only then the visual weapon's own pose, which is still updated
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

        // Before anything is sampled for this frame, let the handoff observe the selection: a scroll applies a slot change
        // between two ticks, and the frame that follows it must already draw the weapon that became held.
        WeaponHandoffManager.instance().beforeFirstPersonFrame();

        // The arm rig is resolved once per frame from the same render state vanilla's own first-person pass uses,
        // so the skin, the skin model type (classic or slim) and the sleeve settings are the live ones.
        PlayerArmRig.Rig armRig = PlayerArmRig.Rig.of(playerState.avatarRenderState);

        // Render locking, the way GWO's GunDrawHolsterHandler drives its ItemInHandRenderer: the stacks vanilla's own pass
        // is about to draw are replaced with the stacks the hands really hold - the visual weapon while a handoff runs, the
        // live stack otherwise, and nothing at all for a weapon the offhand may not hold. This version renders from a render
        // state rather than from renderer fields, so the lock is written there. Without it, vanilla keeps displaying the
        // *previous* item for the few ticks of its equip-lowering animation, which is what made a holstered gun come back for
        // two frames after the handoff had already released it.
        ItemStack main = FirstPersonVisualWeapons.renderStack(InteractionHand.MAIN_HAND);
        ItemStack off = FirstPersonVisualWeapons.renderStack(InteractionHand.OFF_HAND);
        // What vanilla baked this frame's item render state from, before the lock below overwrites it.
        ItemStack bakedMain = hands.mainHandItem;
        ItemStack bakedOff = hands.offHandItem;
        // [Vanilla transition] This mod only takes a hand over when it is involved in what that hand shows: either vanilla's
        // visible stack or the stack this frame really draws is an ARG weapon. A swap between two ordinary items (and the
        // empty hand on either side of it) is left entirely to vanilla, whose own equip animation lowers the outgoing item
        // and raises the incoming one. Rewriting such a frame made the incoming item pop in at the outgoing item's height -
        // the reported snap of empty hand -> slimeball, slimeball -> empty hand and item -> item.
        if (takesOverHand(bakedMain, main)) {
            hands.mainHandItem = main;
            // [Vanilla transition] A rewritten stack alone is not enough: vanilla draws the *baked* item render state and the
            // equip height it extracted, not the stack field this pass just replaced. Switching from a weapon to an empty slot
            // or to a vanilla item therefore drew the weapon's own item model for the few ticks of the equip-lowering animation
            // - the two-frame snap at the end of a holster. Rebuilding the baked state from the stack that is really held makes
            // the frame self-consistent, and itemUsed drops vanilla's equip animation immediately so the next tick extracts the
            // new item as well.
            reconcileHand(InteractionHand.MAIN_HAND, bakedMain, main, hands);
        }
        if (takesOverHand(bakedOff, off)) {
            hands.offHandItem = off;
            reconcileHand(InteractionHand.OFF_HAND, bakedOff, off, hands);
        }
        // [Vanilla transition] While a hand displays something other than what it holds - a handoff in progress, or a weapon
        // the offhand may not show - vanilla's equip animation must not keep claiming the weapon is fully raised.
        holdVanillaEquipHeight(hands, InteractionHand.MAIN_HAND, main);
        holdVanillaEquipHeight(hands, InteractionHand.OFF_HAND, off);

        if (selection.renderMainHand) {
            // The weapon is really being drawn now, so an armed draw clip starts on this frame - the main hand is the one the
            // handoff owns.
            if (main.getItem() instanceof WeaponItem) {
                WeaponHandoffManager.instance().onWeaponRendered(
                        VisualWeapons.instance().drawnIdentity(InteractionHand.MAIN_HAND));
            }
            submitHand(InteractionHand.MAIN_HAND, main, poseStack, collector, lightCoords, armRig, cameraState);
        }
        if (selection.renderOffHand) {
            submitHand(InteractionHand.OFF_HAND, off, poseStack, collector, lightCoords, armRig, cameraState);
        }
    }

    /**
     * Keeps vanilla's equipment height at the lowered end while a hand shows something other than what it holds.
     *
     * <h2>The bug this closes</h2>
     * <p>Vanilla's {@code FirstPersonHandsAndItems} tracks one visible stack and one equipment height. A slot change makes it
     * lower the old stack and raise the new one, but this mod draws its own transition and swaps nothing in vanilla's holder:
     * the holder therefore keeps its height at {@code 1} for the whole holster, believing the weapon it started with is still
     * fully raised. When the handoff ends, the incoming item or the empty hand is substituted at that same height and simply
     * <em>appears</em> - the two-frame pop after a holster into an empty slot or into a vanilla item.</p>
     *
     * <p>Dropping the height to zero for as long as the drawn stack is not the held stack makes the substituted item rise from
     * the lowered position instead, which is exactly how vanilla raises any item it swaps in. The call is
     * {@code FirstPersonHandsAndItems#itemUsed}, the public operation vanilla itself uses when an item is used, and it is
     * repeated per frame because vanilla's own tick raises the height again while the handoff runs.</p>
     *
     * @param hands the first-person render state of this frame
     * @param hand  the hand to keep lowered
     * @param drawn the stack this frame actually draws
     */
    private static final boolean[] HEIGHT_HELD = new boolean[2];

    /** Last render-state transition reported per hand, so the diagnostic prints once per transition, not once per frame. */
    private static final String[] RECONCILED = new String[2];

    private static void holdVanillaEquipHeight(FirstPersonHandsAndItemsRenderState hands, InteractionHand hand,
                                               ItemStack drawn) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        int slot = hand == InteractionHand.MAIN_HAND ? 0 : 1;
        ItemStack live = player.getItemInHand(hand);
        if (drawn != null && live != null && ItemStack.isSameItem(drawn, live)) {
            // The drawn stack is the held stack: vanilla's equip height already describes it correctly.
            if (HEIGHT_HELD[slot]) {
                HEIGHT_HELD[slot] = false;
                Constants.LOG.debug("[ARG] Vanilla equip height released in {}: '{}' is drawn and held again", hand,
                        describe(live));
            }
            return;
        }
        if (!HEIGHT_HELD[slot]) {
            HEIGHT_HELD[slot] = true;
            Constants.LOG.debug("[ARG] Vanilla equip height held at 0 in {}: drawing '{}' while holding '{}'", hand,
                    describe(drawn), describe(live));
        }
        player.firstPersonHandsAndItems().itemUsed(hand);
        if (hand == InteractionHand.MAIN_HAND) {
            hands.mainHandHeight = 0.0F;
            hands.oldMainHandHeight = 0.0F;
        } else {
            hands.offHandHeight = 0.0F;
            hands.oldOffHandHeight = 0.0F;
        }
    }

    /**
     * Makes this frame's vanilla hand render state describe the stack the hand really draws.
     *
     * <p>Vanilla extracts a first-person frame once per tick: it bakes the held stack's item model
     * ({@code mainHandRenderState} / {@code offHandRenderState}) and the equip height that goes with it, and the hand pass
     * later draws exactly that baked state. This mod has to replace the visible stack (the visual weapon outlives the slot
     * change during a handoff, and a weapon the offhand may not hold must draw nothing), which leaves the baked state
     * describing the previous item. Two consequences are visible without this method:</p>
     * <ul>
     *   <li>a weapon that was just holstered into an empty slot or into a vanilla item kept its <em>item model</em> on screen
     *   while the equip height was still lowering - the reported two-frame snap;</li>
     *   <li>the equip height and the swap scale belonged to that previous stack, so the incoming item appeared at the wrong
     *   point of its raise.</li>
     * </ul>
     *
     * <p>So the baked state is rebuilt from the stack that is really drawn, and {@code itemUsed} is called when a weapon
     * leaves the hand: it drops the equip height to zero in vanilla's own holder, which makes the very next tick extract the
     * new item, exactly the way vanilla reacts to using an item. Nothing is rebuilt when the two stacks already match, so the
     * ordinary frames - the ones this mod does not rewrite - keep vanilla's state untouched.</p>
     *
     * @param hand    the hand being reconciled
     * @param baked   the stack vanilla extracted this frame
     * @param live    the stack this frame will really draw
     * @param hands   the first-person render state to repair
     */
    private static void reconcileHand(InteractionHand hand, ItemStack baked, ItemStack live,
                                      FirstPersonHandsAndItemsRenderState hands) {
        if (baked == live || (baked != null && baked.is(live.getItem()) && baked.getCount() == live.getCount())) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        boolean mainHand = hand == InteractionHand.MAIN_HAND;
        int handIndex = mainHand ? 0 : 1;
        String transition = describe(baked) + " -> " + describe(live);
        if (!transition.equals(RECONCILED[handIndex])) {
            RECONCILED[handIndex] = transition;
            Constants.LOG.debug("[ARG] First-person render state reconciled in {}: {} (baked={})", hand, transition,
                    baked != null && baked.getItem() instanceof WeaponItem ? "weapon" : "item");
        }
        boolean mainIsRight = player.getMainArm() == HumanoidArm.RIGHT;
        ItemDisplayContext context = mainHand == mainIsRight
                ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        minecraft.getItemModelResolver().updateForTopItem(
                mainHand ? hands.mainHandRenderState : hands.offHandRenderState,
                live, context, player.level(), player, player.getId() + context.ordinal());

        float swapScale = minecraft.getItemModelResolver().swapAnimationScale(live);
        if (mainHand) {
            hands.mainHandSwapScale = swapScale;
            hands.mainHandUseDuration = live.getUseDuration(player);
            hands.mainHandChargeDuration = CrossbowItem.getChargeDuration(live, player);
        } else {
            hands.offHandSwapScale = swapScale;
            hands.offHandUseDuration = live.getUseDuration(player);
            hands.offHandChargeDuration = CrossbowItem.getChargeDuration(live, player);
        }

        boolean wasWeapon = baked != null && baked.getItem() instanceof WeaponItem;
        boolean isWeapon = live != null && live.getItem() instanceof WeaponItem;
        if (!wasWeapon || isWeapon) {
            return;
        }
        // A weapon just left this hand: end vanilla's equip animation for it now instead of letting it lower the old stack for
        // another two or three ticks.
        player.firstPersonHandsAndItems().itemUsed(hand);
        if (mainHand) {
            hands.mainHandHeight = 0.0F;
            hands.oldMainHandHeight = 0.0F;
        } else {
            hands.offHandHeight = 0.0F;
            hands.oldOffHandHeight = 0.0F;
        }
    }

    /**
     * @param stack a stack, may be {@code null}
     * @return a short name for diagnostics
     */
    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }

    /**
     * Whether this mod has to take one hand's vanilla render state over this frame.
     *
     * <p>The first-person pass rewrites vanilla's visible stack, its baked item render state and its equip height only for
     * a hand this mod is involved in. That is exactly the case when vanilla's visible stack is an ARG weapon (it is being
     * lowered after a holster, or it is still shown while the mod draws the incoming item) or when the stack this frame
     * really draws is one (a weapon is held, drawn, or is outliving its slot change during a handoff).</p>
     *
     * <p>Every other combination is an ordinary Minecraft swap - empty hand to item, item to empty hand, item to item -
     * and vanilla's own equip animation already lowers the outgoing item and raises the incoming one. Taking such a frame
     * over is what made the incoming item appear at the outgoing item's height, so it is deliberately left alone.</p>
     *
     * @param baked the stack vanilla extracted as its visible stack this frame, may be {@code null}
     * @param drawn the stack this frame really draws, may be {@code null}
     * @return {@code true} when the mod owns the hand this frame
     */
    private static boolean takesOverHand(ItemStack baked, ItemStack drawn) {
        return isWeapon(baked) || isWeapon(drawn);
    }

    /**
     * @param stack a stack, may be {@code null}
     * @return whether it is an ARG weapon stack
     */
    private static boolean isWeapon(ItemStack stack) {
        return stack != null && stack.getItem() instanceof WeaponItem;
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
        WeaponPose gameplayPose = FirstPersonVisualWeapons.poseFor(hand, cameraState.cameraEntityPartialTicks);

        WeaponPose pose = AnimationTester.instance().poseFor(weaponItem.definition());
        if (pose == null) {
            pose = DevelopmentAnimationPlayback.currentPose();
        }
        if (pose == null) {
            pose = gameplayPose;
        }
        // ADS constraint: only the gameplay pose of the aiming main hand, and only when the weapon declares the feature.
        // Development playback and the Animation Tester deliberately stay raw.
        if (pose == gameplayPose && hand == InteractionHand.MAIN_HAND) {
            pose = WeaponAiming.constrainPose(pose, WeaponAiming.modelFor(weaponItem), weaponItem.definition(),
                    cameraState.cameraEntityPartialTicks);
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

            // Weapon sway: an additive view transform that follows the camera's mouse movement, applied after the kickback
            // and before the placement, so it composes with every pose, with ADS (through its own multiplier) and with the
            // recoil instead of replacing any of them.
            WeaponSway.apply(poseStack, hand, weaponItem, cameraState.cameraEntityPartialTicks);

            // Aim-down-sights: the ordinary placement and the weapon's own aiming_view placement, blended by the eased
            // transition progress. Both come from the model geometry, so no sight offset is invented here, and the blended
            // matrix replaces the single placement the renderer would apply - nothing is applied twice.
            // The muzzle flash of this hand, if a shot was accepted a few milliseconds ago: it is resolved here because
            // this is where the hand is known, so the renderer never has to inspect the player.
            WeaponRenderer.submitFirstPerson(weaponItem, poseStack, collector, lightCoords, pose, armRig, placement,
                    MuzzleFlash.activeFor(hand, weaponItem));
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
