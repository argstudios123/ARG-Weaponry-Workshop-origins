package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.animation.AnimationTester;
import com.arg_studios.arg_weaponry.client.animation.WeaponViewControllers;
import com.arg_studios.arg_weaponry.client.audio.WeaponReloadSounds;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.client.model.AmmunitionItemModels;
import com.arg_studios.arg_weaponry.client.model.WeaponItemModels;
import com.arg_studios.arg_weaponry.client.model.WeaponStaticItemModel;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.animation.ReusableAnimations;
import com.arg_studios.arg_weaponry.weapon.animation.SampledBone;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationCache;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.model.BoneMesh;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames.ArmSide;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import com.arg_studios.arg_weaponry.weapon.muzzle.MuzzleFlashQuad;
import com.arg_studios.arg_weaponry.weapon.muzzle.MuzzleFlashSpriteSheet;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws parsed weapon geometry.
 *
 * <p>This class owns the one hierarchy walk: it composes each bone's transform, resolves the pipeline for that bone and
 * hands the bone to the two geometry renderers. It never builds geometry, never parses and never uploads anything
 * itself. Bones are drawn as a hierarchy: every bone applies its own pivot rotation and its children inherit that
 * transform.</p>
 *
 * <p>An optional {@link WeaponPose} may be supplied per draw call. Each animated channel is then applied
 * on top of that bone's authored transform, and every channel the pose does not animate keeps the authored
 * value, so a pose never destroys the model definition. A {@code null} pose is the static path and produces
 * exactly the transforms this class produced before animation support existed. The composition policy lives
 * in {@link AnimatedBoneTransform}, not here: this class only walks the hierarchy and draws.</p>
 *
 * <p>The pose stack handed to {@link #submitFirstPerson} must already be Minecraft's first-person
 * <em>camera space</em>; this class adds nothing to it except the model-to-view placement from
 * {@link ViewBoneTransform}, which is derived from the weapon's own {@code default_view} bone. It is
 * deliberately not called from inside the vanilla held-item pipeline, because that pipeline has already
 * applied the arm transforms, the damage tilt and the walking bob of the vanilla hand by the time it
 * submits an item.</p>
 *
 * <h2>Two geometry systems, one skeleton</h2>
 * <p>A weapon model may hold Bedrock <em>cube</em> geometry and {@code poly_mesh} geometry, in any mix, and both are
 * drawn here under the same animated bone hierarchy and with the same transform, so a model that mixes the two renders
 * completely. The work is split along the same line the geometry was built on:</p>
 * <ul>
 *   <li>{@link BedrockCubeRenderer} submits the cube meshes of a bone;</li>
 *   <li>{@link PolyMeshRenderer} submits the poly meshes of a bone;</li>
 *   <li>{@link MeshVertexWriter} is the one shared step below both, because a parsed mesh is a triangle list with
 *   per-corner position, normal and UV whichever system produced it.</li>
 * </ul>
 * <p>The two renderers are independent: neither refers to the other, so cube rendering does not depend on a poly mesh
 * being present and poly rendering does not depend on the cube path. What they share is data, not code - {@code
 * ModelBone} for the hierarchy and {@link BoneMesh} for the geometry - and the transform is applied once, by
 * {@link AnimatedBoneTransform} during this walk, never during geometry construction.</p>
 *
 * <p>The one exception to "every mesh of a bone is drawn" is an <em>arm rig bone</em>: a cube bone that names one of the
 * player's arms ({@code right_hand}, {@code left_arm}, ...). Its cubes are arm placeholders inside the weapon atlas, so
 * they are skipped and the player's own skin arm is drawn at that bone instead - see {@link PlayerArmRig},
 * {@link ArmPlacement} and {@code ModelBoneNames#armSideOf(String)}.</p>
 *
 * <p>Geometry is drawn with the flat face normals the parser produced, one normal per polygon, and with
 * the packed light the first-person pass supplies. Corners are wound for a mirrored draw because the
 * model-to-view transform mirrors Bedrock's X handedness into Minecraft's; see {@link MeshVertexWriter}.</p>
 *
 * <h2>Two optional effects ride on the same walk</h2>
 * <p>Both are decided once per draw call and cost one lookup per bone, so the hierarchy is never walked twice and no
 * geometry is submitted twice:</p>
 * <ul>
 *   <li><strong>Glowing parts</strong> - {@link GlowingWeaponParts} resolves the {@code glowing_weapon_parts} parents of
 *   the definition onto the model and remembers the whole descendant closure. A bone in that set draws its meshes with
 *   {@link #glowRenderType} instead of the weapon atlas pipeline: same geometry, same texture, emissive. Every mesh of
 *   the bone is affected, whatever kind it is, because the pipeline is chosen per bone and passed to both renderers.</li>
 *   <li><strong>Muzzle flash</strong> - one additive emissive quad, submitted when the hierarchy reaches the attachment
 *   bone of an accepted shot. It is built in that bone's own frame ({@link MuzzleFlashQuad}), so it stays clamped to the
 *   barrel and follows every animated transform instead of billboarding towards the camera. The frame comes from the sprite
 *   sheet's pixel size ({@link MuzzleFlashSpriteSheet}), and once the animation is over nothing is submitted.</li>
 * </ul>
 */
public final class WeaponRenderer {

    /**
     * Whether cube geometry is drawn.
     *
     * <p>It is: cube-only bones are ordinary model geometry and must not disappear just because the model
     * also contains {@code poly_mesh} parts. Arm rig bones are the single exception and are handled by
     * {@link PlayerArmRig}, not by this switch.</p>
     */
    public static final boolean DRAW_CUBE_GEOMETRY = true;

    /** Packed light for a fully lit vertex, used by the emissive flash geometry. */
    private static final int FULL_BRIGHT = 0xF000F0;

    private static boolean loggedFirstDraw;

    /** Arm-scale diagnostics already printed, keyed by {@code animation#bone}, so the log cannot flood. */
    private static final Set<String> reportedArmScales = ConcurrentHashMap.newKeySet();

    private WeaponRenderer() {
    }

    /**
     * Submits the first-person geometry of a weapon without animation.
     *
     * @param item        the weapon item being held
     * @param poseStack   the camera-space pose stack
     * @param collector   the submit node collector of the first-person pass
     * @param lightCoords the packed light coordinates of the player
     * @return {@code true} when geometry was submitted
     */
    public static boolean submitFirstPerson(WeaponItem item, PoseStack poseStack, SubmitNodeCollector collector,
                                            int lightCoords) {
        return submitFirstPerson(item, poseStack, collector, lightCoords, null);
    }

    /**
     * Submits the first-person geometry of a weapon with an optional animated pose.
     *
     * <p>Called from the first-person renderer hook with the pose stack in camera space. When the model,
     * texture or display configuration cannot be resolved, nothing is drawn; the reason has already been
     * reported as a diagnostic.</p>
     *
     * <p>A {@code null} pose renders the weapon statically. The pose is read-only here: nothing is written
     * back into the model, the display or the caches, so two draws with different poses cannot influence
     * each other.</p>
     *
     * @param item        the weapon item being held
     * @param poseStack   the camera-space pose stack
     * @param collector   the submit node collector of the first-person pass
     * @param lightCoords the packed light coordinates of the player
     * @param pose        the animation pose to apply, or {@code null} for static rendering
     * @return {@code true} when geometry was submitted
     */
    public static boolean submitFirstPerson(WeaponItem item, PoseStack poseStack, SubmitNodeCollector collector,
                                            int lightCoords, WeaponPose pose) {
        return submitFirstPerson(item, poseStack, collector, lightCoords, pose, null);
    }

    /**
     * Submits the first-person geometry of a weapon with an animated pose and the frame's arm rig.
     *
     * <p>Called from the first-person renderer hook with the pose stack in camera space and the arm rig resolved from
     * the frame's {@code AvatarRenderState}. When the model, texture or display configuration cannot be resolved,
     * nothing is drawn; the reason has already been reported as a diagnostic.</p>
     *
     * <p>A {@code null} pose renders the weapon statically, and a {@code null} rig means arm rig bones keep their
     * placeholder cubes hidden instead of drawing the player's arm - see {@link PlayerArmRig}. The pose is read-only
     * here: nothing is written back into the model, the display or the caches, so two draws with different poses
     * cannot influence each other.</p>
     *
     * @param item        the weapon item being held
     * @param poseStack   the camera-space pose stack
     * @param collector   the submit node collector of the first-person pass
     * @param lightCoords the packed light of the player
     * @param pose        the animation pose to apply, or {@code null} for static rendering
     * @param armRig      the frame's player-skin arm rig, or {@code null}
     * @return {@code true} when geometry was submitted
     */
    public static boolean submitFirstPerson(WeaponItem item, PoseStack poseStack, SubmitNodeCollector collector,
                                            int lightCoords, WeaponPose pose, PlayerArmRig.Rig armRig) {
        return submitFirstPerson(item, poseStack, collector, lightCoords, pose, armRig, null);
    }

    /**
     * Submits the first-person geometry with an explicit placement instead of the ordinary anchor.
     *
     * <p>Milestone 10S blends the {@code default_view} placement into the {@code aiming_view} placement while aiming, so the
     * renderer has to accept the resulting matrix rather than resolving the anchor itself. A {@code null} placement keeps
     * the established behaviour - the weapon's own {@code default_view} anchor - so every existing caller and every weapon
     * without an aiming bone is unaffected.</p>
     *
     * @param item        the weapon item being held
     * @param poseStack   the camera-space pose stack
     * @param collector   the submit node collector of the first-person pass
     * @param lightCoords the packed light of the player
     * @param pose        the animation pose to apply, or {@code null} for static rendering
     * @param armRig      the frame's player-skin arm rig, or {@code null}
     * @param placement   the placement to apply, or {@code null} for the weapon's {@code default_view} anchor
     * @return {@code true} when geometry was submitted
     */
    public static boolean submitFirstPerson(WeaponItem item, PoseStack poseStack, SubmitNodeCollector collector,
                                            int lightCoords, WeaponPose pose, PlayerArmRig.Rig armRig,
                                            org.joml.Matrix4f placement) {
        return submitFirstPerson(item, poseStack, collector, lightCoords, pose, armRig, placement, null);
    }

    /**
     * Submits the first-person geometry with an explicit placement and, optionally, the frame's muzzle flash.
     *
     * <p>The flashes are resolved by the caller from the hand that is being drawn ({@link MuzzleFlash#activeFor}), because
     * a weapon can be held in either hand and the renderer never inspects the player. An empty list is the ordinary case
     * of "no shot in the last few milliseconds" and changes nothing about the draw; several entries mean several shots
     * are still animating, and each is drawn with its own sprite.</p>
     *
     * @param item        the weapon item being held
     * @param poseStack   the camera-space pose stack
     * @param collector   the submit node collector of the pass
     * @param lightCoords the packed light of the player
     * @param pose        the animation pose to apply, or {@code null} for static rendering
     * @param armRig      the frame's player-skin arm rig, or {@code null}
     * @param placement   the placement to apply, or {@code null} for the weapon's {@code default_view} anchor
     * @param muzzleFlashes the flashes running on this hand, never {@code null} (empty when nothing is flashing)
     * @return {@code true} when geometry was submitted
     */
    public static boolean submitFirstPerson(WeaponItem item, PoseStack poseStack, SubmitNodeCollector collector,
                                            int lightCoords, WeaponPose pose, PlayerArmRig.Rig armRig,
                                            org.joml.Matrix4f placement, List<MuzzleFlash.Active> muzzleFlashes) {
        Resolved resolved = resolve(item);
        if (resolved == null) {
            return false;
        }
        WeaponModel model = resolved.model();
        Identifier texture = resolved.texture();

        // Development-only fixture: returns null unless -Darg_weaponry.dev_animation_pose=true and the
        // development weapons are enabled, so a normal client always takes the static path below.
        WeaponPose appliedPose = pose != null ? pose : DevelopmentAnimationPose.poseFor(item.pack(), resolved.display());

        Materials materials = materials(item, model, texture, muzzleFlashes);

        poseStack.pushPose();
        try {
            poseStack.mulPose(placement != null ? placement
                    : ViewBoneTransform.placement(model, ModelBoneNames.DEFAULT_VIEW));
            for (ModelBone root : model.roots()) {
                submitBone(root, poseStack, collector, materials, lightCoords, appliedPose, armRig);
            }
        } finally {
            poseStack.popPose();
        }

        if (!loggedFirstDraw) {
            loggedFirstDraw = true;
            Vec3 anchor = ViewBoneTransform.anchorPivot(model, ModelBoneNames.DEFAULT_VIEW);
            Vec3 viewAnchor = ViewBoneTransform.toMinecraftSpace(anchor);
            Constants.LOG.info("[ARG] Rendering weapon '{}' in first person: {} bone(s), {} triangle(s), texture {}, "
                            + "anchor '{}' at bedrock ({}, {}, {}), minecraft/blockbench ({}, {}, {})",
                    item.definition().name(), model.bonesInOrder().size(), model.triangleCount(), texture,
                    ModelBoneNames.DEFAULT_VIEW, anchor.x(), anchor.y(), anchor.z(),
                    viewAnchor.x(), viewAnchor.y(), viewAnchor.z());
            logGeometrySummary(model);
        }
        return true;
    }

    /**
     * Answers whether {@link #submitFirstPerson} would draw something for a weapon.
     *
     * <p>The vanilla hand pass asks this before it decides to skip its own arm/item rendering for a
     * held weapon, so the decision cannot depend on state carried between two hooks: both sides resolve
     * the same three caches and therefore always agree. When this returns {@code false} the weapon is
     * not drawn by the mod and vanilla keeps rendering the item model as a visible placeholder.</p>
     *
     * @param item the weapon item being held
     * @return {@code true} when the display, model and texture all resolve
     */
    public static boolean isFirstPersonRenderable(WeaponItem item) {
        return resolve(item) != null;
    }

    /**
     * Resolves the display configuration, geometry and texture of one weapon.
     *
     * @param item the weapon item being held
     * @return the three resources, or {@code null} when any of them is missing
     */
    private static Resolved resolve(WeaponItem item) {
        WeaponDisplay display = WeaponDisplayCache.instance().get(item.pack(), item.definition()).orElse(null);
        if (display == null || !display.isRenderable()) {
            return null;
        }

        WeaponModel model = WeaponModelCache.instance().get(item.pack(), display.model());
        if (model == null) {
            return null;
        }

        Identifier texture = WeaponTextureCache.instance().get(item.pack(), display.texture());
        if (texture == null) {
            return null;
        }
        return new Resolved(display, model, texture);
    }

    /** The three resources one first-person draw needs, resolved together so the checks cannot diverge. */
    private record Resolved(WeaponDisplay display, WeaponModel model, Identifier texture) {
    }

    /**
     * Reports what one model contributes to the draw, once per model: cube triangles, polygon triangles and the
     * bones whose cubes were replaced by the player's own arm.
     *
     * <p>A hybrid model is expected to show both kinds; a model that reports zero cube triangles while declaring
     * cube bones, or zero polygon triangles while declaring {@code poly_mesh} data, is telling the log that a
     * geometry path is not reaching the renderer.</p>
     *
     * @param model the model being drawn
     */
    private static void logGeometrySummary(WeaponModel model) {
        int cubeTriangles = 0;
        int polygonTriangles = 0;
        List<String> armBones = new ArrayList<>();
        for (ModelBone bone : model.bonesInOrder()) {
            ArmSide side = ModelBoneNames.armSideOf(bone.name());
            boolean hasCubes = carriesCubeGeometry(bone);
            if (side != ArmSide.NONE && hasCubes) {
                armBones.add(bone.name() + "=" + side);
            }
            for (BoneMesh mesh : bone.meshes()) {
                if (mesh.source() == BoneMesh.Source.CUBE) {
                    cubeTriangles += mesh.triangleCount();
                } else {
                    polygonTriangles += mesh.triangleCount();
                }
            }
        }
        Constants.LOG.info("[ARG] Geometry of '{}': {} cube triangle(s), {} polygon triangle(s), {} arm bone(s) {}",
                model.id(), cubeTriangles, polygonTriangles, armBones.size(), armBones);
    }

    /**
     * Drops every renderer-related cache: parsed models, display configurations, uploaded textures and parsed
     * animation documents, and resets the development playback fixtures, the Animation Tester and the
     * gameplay view controllers.
     *
     * <p>This is the single client-side entry point for invalidation. Both loaders' resource-reload listeners
     * call it, so Fabric and NeoForge cannot drift apart on which caches a reload clears.</p>
     *
     * <p>Resetting the view controllers matters as much as clearing the animation cache: a controller keeps
     * the resolved {@code BedrockAnimation} it is playing, so without this it would keep sampling a document
     * from the previous resource generation. The next frame binds it again through the fresh cache.</p>
     */
    public static void invalidateCaches() {
        WeaponDisplayCache.instance().clear();
        WeaponModelCache.instance().clear();
        WeaponTextureCache.instance().clear();
        WeaponAnimationCache.instance().clear();
        ReusableAnimations.resetDiagnostics();
        // The static world render caches a bounding box per model and resolves the model and texture per frame, so it has to
        // forget the boxes together with the models they belong to.
        WeaponStaticRenderer.clearExtents();
        // The item-model selection and the per-context static draw report themselves once; a reload re-reads every pack, so the
        // decisions have to be reportable again.
        WeaponItemModels.clearLog();
        WeaponStaticItemModel.clearLog();
        AmmunitionDisplayCache.instance().clear();
        AmmunitionItemModels.clearLog();
        WeaponReloadSounds.reset();
        PlayerArmModels.invalidate();
        com.arg_studios.arg_weaponry.client.camera.FirstPersonCamera.reset();
        DevelopmentAnimationPose.reset();
        DevelopmentAnimationPlayback.reset();
        AnimationTester.instance().reset();
        WeaponViewControllers.instance().reset();
        PlayerArmRig.resetDiagnostics();
        reportedArmScales.clear();
        // Glowing bone selections are a function of the parsed model and of the definition, so a reload that re-reads a
        // pack has to drop them together with the models they were resolved from. The flash render types are bound to the
        // uploaded sprite sheets, which are re-created by the same reload.
        GlowingWeaponParts.invalidate();
        MuzzleFlash.invalidate();
        // Glow and flash pipelines are bound to the uploaded textures, which are re-created by the same reload; dropping
        // them keeps one render type per texture instead of one per texture version.
        WeaponRenderTypes.invalidate();
        Constants.LOG.info("[ARG] Weapon render caches invalidated; content-pack resources are re-read on next use");
    }

    /**
     * Chooses the pipeline for weapon geometry.
     *
     * <p>Content-pack weapon textures are UV atlases with alpha padding: the M100 DMR's 512x512 PNG
     * is 79% fully transparent, and the unused area is black. {@code entitySolid} ignores alpha and
     * therefore painted that padding black onto the model, so the geometry uses the cutout family, which
     * discards those fragments.</p>
     *
     * <p><strong>Culling is off.</strong> Milestone 10M used {@code entityCutoutCull} on the assumption that
     * the mesh was closed and consistently wound. The shipped M100 DMR is neither: its polygons are an open
     * surface soup exported by a mesh generator, and 3018 of its 4501 polygons are wound <em>against</em> the
     * normals they carry (1483 agree). The parser reorders each triangle to agree with the shading normal it
     * derives from those authored normals, so with back-face culling a polygon whose authored normal points
     * the other way is thrown away and its surface simply disappears - which is what "missing or incomplete
     * texture" on the weapon looked like. The reference implementation makes the same choice: TaCZ renders its
     * {@code PolyMesh} through {@code RenderTypes.entityCutout}, without culling.</p>
     *
     * <p>What the pipeline does with the vertex data, from {@code RenderPipelines.ENTITY_CUTOUT} and
     * {@code core/entity.vsh} in this Minecraft version:</p>
     * <ul>
     *   <li>it binds the lightmap ({@code useLightmap()}) and the overlay texture, so the packed block and
     *       sky light this renderer supplies is sampled rather than ignored;</li>
     *   <li>it defines {@code ALPHA_CUTOUT} 0.1, which performs the cutout;</li>
     *   <li>it defines neither {@code NO_CARDINAL_LIGHTING} nor {@code PER_FACE_LIGHTING}, so the shader
     *       takes the default branch {@code vertexColor = minecraft_mix_light(Light0, Light1, Normal,
     *       Color)}: the supplied normal really does drive directional lighting, with vanilla's 0.4
     *       ambient and 0.6 directional split. Nothing unlit is being used;</li>
     *   <li>its primitive topology is {@code QUADS}, not triangles - see {@link MeshVertexWriter};</li>
     *   <li>the {@code NORMAL} vertex element is part of {@code DefaultVertexFormat.ENTITY}, and
     *       {@code VertexConsumer#setNormal(PoseStack.Pose, ...)} transforms it by the pose's normal matrix
     *       once (normalising it when the pose is not known to be orthonormal), so bone rotations tilt the
     *       lighting correctly and the uniform weapon scale does not change its magnitude.</li>
     * </ul>
     */
    private static RenderType renderTypeFor(Identifier texture) {
        return RenderTypes.entityCutout(texture);
    }

    /**
     * Submits a weapon as a <strong>static</strong> model: the authored geometry and texture, at the current pose stack, with no
     * animation, no first-person arms and no view anchor.
     *
     * <p>This is the world-space half of the renderer. The first-person pass places the model with
     * {@code ViewBoneTransform.placement(...)} and draws the player's own arms at the rig bones; a weapon seen in another
     * player's hand, on the ground, in an item frame or on a shelf must show the gun and nothing else, so this method</p>
     * <ul>
     *   <li>applies <em>no</em> placement - the caller decides where the item is, because that is a display-context transform
     *   that comes from the weapon's own {@code display_views};</li>
     *   <li>uses the authored transform of every bone ({@code pose == null}), so no animation channel is applied in world
     *   space;</li>
     *   <li>skips every arm-rig bone entirely instead of drawing the player's skin arm, which is what "only the 3D gun
     *   model" means.</li>
     * </ul>
     *
     * @param item        the weapon item being drawn
     * @param poseStack   the pose stack of the item, already transformed for the display context
     * @param collector   the submit node collector of the pass
     * @param lightCoords the packed light at the item
     * @return {@code true} when geometry was submitted
     */
    public static boolean submitStatic(WeaponItem item, PoseStack poseStack, SubmitNodeCollector collector,
                                       int lightCoords) {
        Resolved resolved = resolve(item);
        if (resolved == null) {
            return false;
        }
        // Glowing parts are part of the weapon, not of the first-person view, so the static draw uses the same
        // materials. A flash is deliberately absent: it belongs to a shot, and a shot only happens in first person.
        Materials materials = materials(item, resolved.model(), resolved.texture(), null);
        boolean submitted = false;
        for (ModelBone root : resolved.model().roots()) {
            submitted |= submitStaticBone(root, poseStack, collector, materials, lightCoords);
        }
        return submitted;
    }

    /**
     * Submits one bone and its children statically, skipping the arm rig.
     *
     * @return whether any geometry was submitted
     */
    private static boolean submitStaticBone(ModelBone bone, PoseStack poseStack, SubmitNodeCollector collector,
                                            Materials materials, int lightCoords) {
        boolean submitted = false;
        poseStack.pushPose();
        try {
            // The authored transform only: a world-space weapon carries no pose.
            AnimatedBoneTransform.apply(poseStack, bone, null);
            if (ModelBoneNames.armSideOf(bone.name()) == ArmSide.NONE) {
                submitted = submitMeshes(bone, poseStack, collector, materials, lightCoords);
            }
            for (ModelBone child : bone.children()) {
                submitted |= submitStaticBone(child, poseStack, collector, materials, lightCoords);
            }
        } finally {
            poseStack.popPose();
        }
        return submitted;
    }

    /** Submits one bone and, recursively, its children. */
    private static void submitBone(ModelBone bone, PoseStack poseStack, SubmitNodeCollector collector,
                                   Materials materials, int lightCoords, WeaponPose pose,
                                   PlayerArmRig.Rig armRig) {
        poseStack.pushPose();
        try {
            SampledBone animated = AnimatedBoneTransform.poseFor(bone, pose);
            AnimatedBoneTransform.apply(poseStack, bone, animated);
            // One set lookup per bone decides the pipeline: the glowing bone set is the whole descendant closure of the
            // configured parents, so no recursion happens here and every mesh of a glowing bone - cube, poly_mesh or
            // several of either - is drawn with the emissive pipeline.
            RenderType renderType = materials.renderTypeFor(bone.name());
            ArmSide armSide = ModelBoneNames.armSideOf(bone.name());
            boolean armPlaceholder = armSide != ArmSide.NONE && carriesCubeGeometry(bone);
            if (armPlaceholder) {
                reportArmScale(pose, bone, animated);
                // The cubes of this bone are the player's arm, drawn from the skin instead of the weapon atlas.
                // They are never drawn with the weapon texture and never duplicated: exactly one arm is submitted,
                // and a failure to submit is reported once by PlayerArmRig itself. The bone keeps its transform, so
                // the arm follows the authored (and animated) pose of this rig reference, and the arm is positioned
                // against this bone's own cube geometry by ArmPlacement.
                PlayerArmRig.submit(armRig, armSide, bone, poseStack, collector, lightCoords);
            } else {
                submitMeshes(bone, poseStack, collector, materials, lightCoords);
            }
            if (!materials.flashes().isEmpty()) {
                for (FlashDraw flash : materials.flashes()) {
                    if (flash.bone().equals(bone.name())) {
                        submitFlash(bone, poseStack, collector, flash);
                    }
                }
            }
            for (ModelBone child : bone.children()) {
                submitBone(child, poseStack, collector, materials, lightCoords, pose, armRig);
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * Reports, once per animation and bone, that an animation scales one of the player's arm rig bones.
     *
     * <p>This is a diagnostic, not a correction, and it exists because the two things it distinguishes are otherwise
     * indistinguishable from the outside. The arm rig bone of a Bedrock weapon carries a {@code 4x12x4} cube that stands for
     * the player's arm, and the cube is <em>not</em> drawn: the player's own arm is drawn in its place, scaled to the
     * reference cube. A {@code scale} channel authored on such a bone therefore does not resize a placeholder nobody sees -
     * it resizes the drawn arm, which is a change to the player's proportions rather than to the weapon.</p>
     *
     * <p>Whether that is intended is an authoring decision, so the channel is applied exactly as it was before this
     * diagnostic and the log only names the clip and the bone, which is what a creator needs to remove the channel if the
     * arm looks stretched.</p>
     *
     * @param pose     the pose being drawn, may be {@code null}
     * @param bone     the arm rig bone
     * @param animated its animated channels, may be {@code null}
     */
    private static void reportArmScale(WeaponPose pose, ModelBone bone, SampledBone animated) {
        if (animated == null || animated.scale() == null) {
            return;
        }
        String animation = pose == null ? "" : pose.animationName();
        if (!reportedArmScales.add(animation + "#" + bone.name())) {
            return;
        }
        Constants.LOG.info("[ARG] The animation '{}' scales the arm rig bone '{}'. That channel is applied to the drawn "
                        + "player arm, not to a placeholder cube; remove it from the bone if the arm looks stretched",
                animation, bone.name());
    }

    /**
     * @param bone a bone of the model
     * @return {@code true} when the bone carries at least one cube mesh
     */
    private static boolean carriesCubeGeometry(ModelBone bone) {
        for (BoneMesh mesh : bone.meshes()) {
            if (mesh.source() == BoneMesh.Source.CUBE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the pipelines and the flash of one draw call.
     *
     * <p>The glowing selection is resolved and remembered by {@link GlowingWeaponParts} - once per weapon and model, not
     * per frame - and the pipelines are vanilla ones, so nothing here creates a shader, a pass or a buffer of its own.</p>
     *
     * @param item        the weapon being drawn
     * @param model       its parsed model
     * @param texture     the weapon atlas
     * @param muzzleFlashes the flashes running on this hand, never {@code null}
     * @return the materials of this draw
     */
    private static Materials materials(WeaponItem item, WeaponModel model, Identifier texture,
                                       List<MuzzleFlash.Active> muzzleFlashes) {
        Set<String> glowingBones = GlowingWeaponParts.bonesOf(item.pack(), item.definition(), model);
        RenderType glow = glowingBones.isEmpty() ? null : glowRenderType(texture);
        return new Materials(renderTypeFor(texture), glow, glowingBones, flashDraws(item, muzzleFlashes));
    }

    /**
     * Resolves the frame of the muzzle flash this draw shows.
     *
     * <p>The frame is derived from the image the texture cache actually uploaded, so the sprite-sheet layout is never
     * assumed: {@link MuzzleFlashSpriteSheet} derives the frame count from the pixel size, and once the animation is over
     * nothing is returned and the flash simply stops.</p>
     *
     * <p>Every flash still running on the hand becomes one quad, each with the sprite its own seed selects, so two
     * shots in flight draw two independent flashes instead of one restarted animation.</p>
     *
     * @param item    the weapon being drawn
     * @param actives the flashes running on this hand, never {@code null}
     * @return the quads to draw, empty when there is nothing to draw
     */
    private static List<FlashDraw> flashDraws(WeaponItem item, List<MuzzleFlash.Active> actives) {
        if (actives == null || actives.isEmpty()) {
            return List.of();
        }
        List<FlashDraw> draws = new ArrayList<>(actives.size());
        for (MuzzleFlash.Active active : actives) {
            FlashDraw draw = flashDraw(item, active);
            if (draw != null) {
                draws.add(draw);
            }
        }
        return draws.isEmpty() ? List.of() : draws;
    }

    /**
     * Resolves the quad of one running flash.
     *
     * @param item   the weapon being drawn
     * @param active the running flash
     * @return the quad to draw, or {@code null} when its animation is over
     */
    private static FlashDraw flashDraw(WeaponItem item, MuzzleFlash.Active active) {
        if (active == null) {
            return null;
        }
        WeaponDefinition.MuzzleFlash configuration = item.definition().muzzleFlash();
        if (configuration == null || !configuration.isUsable()) {
            return null;
        }
        WeaponTextureCache.Uploaded sheet =
                WeaponTextureCache.instance().getUploaded(item.pack(), configuration.texture());
        if (sheet == null) {
            // The texture cache has already reported the missing or unreadable sheet once.
            return null;
        }
        // A declared grid wins: the sheet layout and its frame rate are what the pack says they are. Only a pack that
        // declares no grid falls back to deriving a horizontal strip from the uploaded texture's pixel size.
        // The authored duration, when there is one, decides how long the flash stays visible; the frame rate only
        // decides how fast the sprite advances. No duration means "the animation's own frames decide", as before.
        WeaponDefinition.MuzzleFlash.Sprites declared = configuration.sprites();
        double durationSeconds = configuration.durationSeconds();
        MuzzleFlashSpriteSheet.Frame frame = declared != null
                ? MuzzleFlashSpriteSheet.frameAt(active.elapsedSeconds(), declared, active.seed(), durationSeconds)
                : MuzzleFlashSpriteSheet.frameAt(active.elapsedSeconds(),
                        MuzzleFlashSpriteSheet.frameCount(sheet.width(), sheet.height()), durationSeconds);
        if (frame == null) {
            return null;
        }
        return new FlashDraw(active.bone(), frame, flashRenderType(sheet.texture()), (float) configuration.size());
    }

    /**
     * Chooses the pipeline for glowing geometry.
     *
     * <p>The pipeline itself, its emissive behaviour and the deliberate absence of any OpenGL call live in
     * {@link WeaponRenderTypes#glowing}, which also memoizes it per texture and degrades gracefully when a rendering mod
     * makes the emissive type unavailable. This method exists so the renderer has one named decision point.</p>
     *
     * @param texture the weapon atlas
     * @return the pipeline for glowing bones
     */
    private static RenderType glowRenderType(Identifier texture) {
        return WeaponRenderTypes.glowing(texture);
    }

    /**
     * Returns the additive emissive pipeline of one flash sprite sheet.
     *
     * <p>{@link WeaponRenderTypes#muzzleFlash} owns the pipeline choice, its per-sheet memoization and its graceful
     * degradation, so the renderer never builds a render type of its own.</p>
     *
     * @param texture the uploaded sprite sheet
     * @return the render type for the flash quad
     */
    private static RenderType flashRenderType(Identifier texture) {
        return WeaponRenderTypes.muzzleFlash(texture);
    }

    /**
     * Submits the muzzle-flash quad of one bone.
     *
     * <p>The quad is drawn <strong>in the bone's own frame</strong>, so the flash is attached to the barrel: it inherits the
     * authored and animated transform of the muzzle bone and of every bone above it - the weapon placement, the aim blend,
     * the recoil kickback, the sway and the reload/inspect clips all reach this pose stack as bone transforms - and it turns
     * with the weapon when the player looks around instead of turning with the camera.</p>
     *
     * <p>That is the difference from the previous implementation, which reset the pose to a pure translation before
     * submitting. Resetting the pose discarded the bone's rotation <em>and</em> the whole placement chain, so the quad ended
     * up axis-aligned in camera space: a billboard whose orientation changed with the viewing direction. The corners come
     * from {@link MuzzleFlashQuad}, which builds them around the bone's pivot in bone space, and the pose stack is used
     * unchanged.</p>
     *
     * <p>The quad's edge length is authored in blocks, while bone space is model units, so the accumulated uniform scale is
     * read from the pose matrix itself ({@code getScale}, the length of its basis columns). That keeps the size correct for
     * the first-person placement, the blended aiming placement and the static item transform without hardcoding a constant.</p>
     *
     * @param bone      the attachment bone
     * @param poseStack the pose stack, positioned at that bone
     * @param collector the submit node collector
     * @param flash     the flash to draw
     */
    private static void submitFlash(ModelBone bone, PoseStack poseStack, SubmitNodeCollector collector, FlashDraw flash) {
        org.joml.Matrix4f pose = poseStack.last().pose();
        Vector3f xAxis = pose.getColumn(0, new Vector3f());
        float blocksPerUnit = MuzzleFlashQuad.blocksPerUnit(xAxis.x, xAxis.y, xAxis.z);
        MuzzleFlashQuad.Corner[] corners =
                MuzzleFlashQuad.corners(bone.pivot(), flash.size(), blocksPerUnit, flash.frame());
        collector.submitCustomGeometry(poseStack, flash.renderType(),
                (captured, buffer) -> writeFlashQuad(corners, captured, buffer));
    }

    /**
     * Writes the four corners of a flash as one quad.
     *
     * <p>The frame is selected through the UVs, so one uploaded sprite sheet serves every frame and no texture matrix,
     * no per-frame render type and no second upload is needed. The local normal is {@code +Z} because the quad lies in the
     * bone's {@code X/Y} plane; the additive emissive pipeline does not light it, so the normal only keeps the vertex
     * complete for the format.</p>
     *
     * @param corners the quad's corners, in primitive order
     * @param pose    the pose the collector captured
     * @param buffer  the buffer of the custom geometry node
     */
    /**
     * Writes one muzzle-flash quad.
     *
     * <p>The flash is not weapon geometry: it is one additive emissive quad submitted in the attachment bone's frame, so
     * it keeps its own writer and full-bright light instead of going through {@link MeshVertexWriter} with the weapon's
     * pipeline.</p>
     */
    private static void writeFlashQuad(MuzzleFlashQuad.Corner[] corners, PoseStack.Pose pose, VertexConsumer buffer) {
        for (MuzzleFlashQuad.Corner corner : corners) {
            buffer.addVertex(pose, corner.x(), corner.y(), corner.z())
                    .setColor(255, 255, 255, 255)
                    .setUv(corner.u(), corner.v())
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(FULL_BRIGHT)
                    .setNormal(pose, 0.0F, 0.0F, 1.0F);
        }
    }

    /**
     * The pipelines and the flash of one draw call.
     *
     * @param body         the weapon atlas pipeline
     * @param glow         the emissive pipeline, or {@code null} when nothing glows
     * @param glowingBones the bones that use {@code glow}
     * @param flash        the flash to draw at its bone, or {@code null}
     */
    private record Materials(RenderType body, RenderType glow, Set<String> glowingBones, List<FlashDraw> flashes) {

        /**
         * @param boneName a bone of the model
         * @return the pipeline that bone's geometry must be drawn with
         */
        RenderType renderTypeFor(String boneName) {
            return glow != null && glowingBones.contains(boneName) ? glow : body;
        }
    }

    /**
     * One muzzle-flash quad.
     *
     * @param bone       the attachment bone it belongs to
     * @param frame      the sprite-sheet frame of this moment
     * @param renderType the additive emissive pipeline of the sheet
     * @param size       the edge length in blocks
     */
    private record FlashDraw(String bone, MuzzleFlashSpriteSheet.Frame frame, RenderType renderType, float size) {
    }

    /**
     * Draws the geometry of one bone through the two geometry renderers.
     *
     * <p>This is the single point where the geometry systems meet the bone hierarchy. Every mesh of the bone is offered
     * to the renderer that owns its kind - cubes to {@link BedrockCubeRenderer}, poly meshes to
     * {@link PolyMeshRenderer} - in the bone's own declaration order, so the submitted stream keeps the order it had
     * before the split. That order is not cosmetic: a glowing bone draws with a translucent emissive pipeline, where
     * overlapping fragments blend in submission order.</p>
     *
     * <p>Neither renderer knows the other exists; this method is the only place that knows both. Bone transforms are
     * already on the pose stack - applied once, by the caller - so nothing here can move geometry or apply a pose
     * twice.</p>
     *
     * @param bone        the bone being drawn
     * @param poseStack   the pose stack, already carrying this bone's transform
     * @param collector   the collector of the current frame
     * @param materials   the pipelines and effects of this draw
     * @param lightCoords the packed light of the draw
     * @return whether any geometry was submitted
     */
    private static boolean submitMeshes(ModelBone bone, PoseStack poseStack, SubmitNodeCollector collector,
                                        Materials materials, int lightCoords) {
        if (bone.meshes().isEmpty()) {
            return false;
        }
        RenderType renderType = materials.renderTypeFor(bone.name());
        boolean submitted = false;
        for (BoneMesh mesh : bone.meshes()) {
            submitted |= switch (mesh.source()) {
                case CUBE -> DRAW_CUBE_GEOMETRY
                        && BedrockCubeRenderer.submit(mesh, poseStack, collector, renderType, lightCoords);
                case POLY_MESH -> PolyMeshRenderer.submit(mesh, poseStack, collector, renderType, lightCoords);
            };
        }
        return submitted;
    }
}
