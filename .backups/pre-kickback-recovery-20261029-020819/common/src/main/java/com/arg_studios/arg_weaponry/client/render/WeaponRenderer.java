package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.animation.AnimationTester;
import com.arg_studios.arg_weaponry.client.animation.WeaponViewControllers;
import com.arg_studios.arg_weaponry.client.audio.WeaponReloadSounds;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.client.model.AmmunitionItemModels;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationCache;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.model.BoneMesh;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames;
import com.arg_studios.arg_weaponry.weapon.model.ModelBoneNames.ArmSide;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws parsed weapon geometry.
 *
 * <p>This is the only class that turns {@link WeaponModel} data into vertices; it never parses or
 * uploads anything itself. Bones are drawn as a hierarchy: every bone applies its own pivot rotation
 * and its children inherit that transform.</p>
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
 * <p>Cube geometry and {@code poly_mesh} geometry are both drawn, in the same bone hierarchy and with the
 * same transform, so a model that mixes the two renders completely. The one exception is an <em>arm rig
 * bone</em>: a cube bone that names one of the player's arms ({@code right_hand}, {@code left_arm}, ...).
 * Its cubes are arm placeholders inside the weapon atlas, so they are skipped and the player's own skin arm
 * is drawn at that bone instead - see {@link PlayerArmRig}, {@link ArmPlacement} and
 * {@code ModelBoneNames#armSideOf(String)}.</p>
 *
 * <p>Geometry is drawn with the flat face normals the parser produced, one normal per polygon, and with
 * the packed light the first-person pass supplies. The vertex stream is expanded to four corners per
 * primitive because every entity pipeline in this Minecraft version is declared with a {@code QUADS}
 * topology, and those corners are wound for a mirrored draw because the model-to-view transform mirrors
 * Bedrock's X handedness into Minecraft's; see {@link #writeQuads} for both.</p>
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

    /**
     * Corner order within one submitted primitive.
     *
     * <p>{@code 0, 2, 1, 1} is the mirrored form of the default {@code 0, 1, 2, 2}: the live triangle
     * stays {@code (first, third, second)} and the fourth corner repeats the third so the quad stays
     * degenerate. See {@link #writeQuads} for why the winding is oriented this way.</p>
     */
    private static final int[] MIRRORED_QUAD_ORDER = {0, 2, 1, 1};

    private static boolean loggedFirstDraw;

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
        Resolved resolved = resolve(item);
        if (resolved == null) {
            return false;
        }
        WeaponModel model = resolved.model();
        Identifier texture = resolved.texture();

        // Development-only fixture: returns null unless -Darg_weaponry.dev_animation_pose=true and the
        // development weapons are enabled, so a normal client always takes the static path below.
        WeaponPose appliedPose = pose != null ? pose : DevelopmentAnimationPose.poseFor(item.pack(), resolved.display());

        RenderType renderType = renderTypeFor(texture);

        poseStack.pushPose();
        try {
            poseStack.mulPose(placement != null ? placement
                    : ViewBoneTransform.placement(model, ModelBoneNames.DEFAULT_VIEW));
            for (ModelBone root : model.roots()) {
                submitBone(root, poseStack, collector, renderType, lightCoords, appliedPose, armRig);
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
     *   <li>its primitive topology is {@code QUADS}, not triangles - see {@link #writeQuads};</li>
     *   <li>the {@code NORMAL} vertex element is part of {@code DefaultVertexFormat.ENTITY}, and
     *       {@code VertexConsumer#setNormal(PoseStack.Pose, ...)} transforms it by the pose's normal matrix
     *       once (normalising it when the pose is not known to be orthonormal), so bone rotations tilt the
     *       lighting correctly and the uniform weapon scale does not change its magnitude.</li>
     * </ul>
     */
    private static RenderType renderTypeFor(Identifier texture) {
        return RenderTypes.entityCutout(texture);
    }

    /** Submits one bone and, recursively, its children. */
    private static void submitBone(ModelBone bone, PoseStack poseStack, SubmitNodeCollector collector,
                                   RenderType renderType, int lightCoords, WeaponPose pose,
                                   PlayerArmRig.Rig armRig) {
        poseStack.pushPose();
        try {
            AnimatedBoneTransform.apply(poseStack, bone, AnimatedBoneTransform.poseFor(bone, pose));
            ArmSide armSide = ModelBoneNames.armSideOf(bone.name());
            boolean armPlaceholder = armSide != ArmSide.NONE && carriesCubeGeometry(bone);
            if (armPlaceholder) {
                // The cubes of this bone are the player's arm, drawn from the skin instead of the weapon atlas.
                // They are never drawn with the weapon texture and never duplicated: exactly one arm is submitted,
                // and a failure to submit is reported once by PlayerArmRig itself. The bone keeps its transform, so
                // the arm follows the authored (and animated) pose of this rig reference, and the arm is positioned
                // against this bone's own cube geometry by ArmPlacement.
                PlayerArmRig.submit(armRig, armSide, bone, poseStack, collector, lightCoords);
            } else {
                for (BoneMesh mesh : bone.meshes()) {
                    if (mesh.source() == BoneMesh.Source.CUBE && !DRAW_CUBE_GEOMETRY) {
                        continue;
                    }
                    submitMesh(mesh, poseStack, collector, renderType, lightCoords);
                }
            }
            for (ModelBone child : bone.children()) {
                submitBone(child, poseStack, collector, renderType, lightCoords, pose, armRig);
            }
        } finally {
            poseStack.popPose();
        }
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

    /** Submits one mesh through the custom-geometry node, which keeps vanilla's batching intact. */
    private static void submitMesh(BoneMesh mesh, PoseStack poseStack, SubmitNodeCollector collector,
                                   RenderType renderType, int lightCoords) {
        collector.submitCustomGeometry(poseStack, renderType,
                (pose, buffer) -> writeQuads(mesh, pose, buffer, lightCoords));
    }

    /**
     * Writes one mesh's corners into a vertex consumer whose primitive topology is {@code QUADS}.
     *
     * <p>The topology is not a detail that can be ignored. This Minecraft version builds a custom-geometry
     * buffer from the render type ({@code StagedVertexBuffer#getVertexBuilder} passes
     * {@code renderType.primitiveTopology()} into the {@code BufferBuilder}) and derives the index count
     * from the vertex count alone: {@code PrimitiveTopology.QUADS#indexCount(vertices)} is
     * {@code vertices / 4 * 6}. Every entity pipeline is declared with {@code PrimitiveTopology.QUADS}, so
     * a stream of three-vertex triangles is consumed in groups of four — a triangle's remaining corners
     * become corners of the next quad, every primitive mixes corners of different triangles, the last
     * {@code vertices mod 4} corners are never indexed — and the surface, its UVs and its normals all come
     * out scrambled. That is exactly what a "broken texture" looks like.</p>
     *
     * <p>The mesh therefore emits four corners per primitive: {@code a, b, c, c}. The second triangle of
     * that quad ({@code a, c, c}) is degenerate and rasterises nothing, so the visible geometry is exactly
     * the parsed triangle list, with each corner keeping the UV and the flat face normal the parser gave it.
     * TaCZ's {@code PolyMesh} does the same thing for its triangles, which is the confirmation that
     * degenerate quads are the intended way to feed this topology.</p>
     *
     * <p><strong>Mirrored winding.</strong> The model-to-view transform mirrors the model in X, because
     * Bedrock geometry is authored in the mirror of Minecraft's space (see {@link ViewBoneTransform}).
     * A mirror reverses the apparent winding of every triangle, so with back-face culling enabled the
     * outside of the weapon would be discarded and its interior drawn instead. Each primitive is
     * therefore emitted as {@code a, c, b, b} rather than {@code a, b, c, c}: the live triangle keeps
     * the same three corners, the same UVs and the same normals, but is wound the other way, so it faces
     * the way the parsed face normal says it should. The fourth corner stays a repeat of the third, so
     * the quad is still degenerate, the primitive count is unchanged and every corner is still written
     * exactly once per primitive.</p>
     */
    private static void writeQuads(BoneMesh mesh, PoseStack.Pose pose, VertexConsumer buffer, int lightCoords) {
        float[] positions = mesh.positionsUnsafe();
        float[] normals = mesh.normalsUnsafe();
        float[] uvs = mesh.uvsUnsafe();
        int[] corners = mesh.quadCornerIndicesUnsafe();

        for (int base = 0; base < corners.length; base += 4) {
            for (int offset : MIRRORED_QUAD_ORDER) {
                int corner = corners[base + offset];
                buffer.addVertex(pose, positions[corner * 3], positions[corner * 3 + 1], positions[corner * 3 + 2])
                        .setColor(255, 255, 255, 255)
                        .setUv(uvs[corner * 2], uvs[corner * 2 + 1])
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(lightCoords)
                        .setNormal(pose, normals[corner * 3], normals[corner * 3 + 1], normals[corner * 3 + 2]);
            }
        }
    }
}
