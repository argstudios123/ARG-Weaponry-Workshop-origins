package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.weapon.model.BoneMesh;
import com.arg_studios.arg_weaponry.weapon.model.ModelBone;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * The mapping from a native player arm into an arm rig bone's reference cube.
 *
 * <h2>The four coordinate frames involved</h2>
 * <p>An arm is submitted while the pose stack is already inside the arm rig bone (see
 * {@link PlayerArmRig}), so four different frames meet at that one point:</p>
 * <ol>
 *     <li><strong>The bone frame</strong> - the pose stack inside an arm rig bone. {@code AnimatedBoneTransform}
 *     composes {@code T(pivot) * R * T(-pivot)}, a <em>model-to-model</em> transform, so points submitted there are
 *     expressed in the same model-space coordinates as the bone's own cube vertices: one unit is {@code 1/16} block
 *     and the global {@code SCALE/16} factor and the {@code diag(-1, 1, 1)} handedness conversion are already above
 *     it ({@link ViewBoneTransform}). The reference the arm has to match is therefore the arm rig cube's own
 *     model-space box, {@link #referenceBox}; the bone's pivot and rotation need no separate treatment because the
 *     cube and the arm are submitted inside the same frame and are transformed by it together.</li>
 *     <li><strong>The native player model</strong> - {@code PlayerModel}'s parts are authored in pixels
 *     and every one of them is divided by 16 on the way out: {@code ModelPart#translateAndRotate}
 *     translates by {@code x / 16} and {@code ModelPart.Vertex#worldX()} divides the cube corners by
 *     {@code ModelPart.Vertex.SCALE_FACTOR}. By the time a submission reaches the pose stack the arm is
 *     therefore <em>in blocks</em> while the weapon model above it is in {@code 1/16} block units. That
 *     missing factor of 16 is {@link #MODEL_UNITS_PER_BLOCK}.</li>
 *     <li><strong>Native handedness</strong> - the player model is drawn under
 *     {@code PoseStack.scale(-1, -1, 1)} by {@code LivingEntityRenderer}, so its {@code +X} points the
 *     same way as Bedrock's {@code +X} but its {@code +Y} points <em>down</em>. Converting the arm into
 *     the bone frame therefore negates Y, i.e. {@code diag(1, -1, 1)}. That matrix also has determinant
 *     {@code -1}, which cancels the determinant of the global {@code diag(-1, 1, 1)} and keeps the arm's
 *     winding the vanilla one - an otherwise identical transform with a positive determinant would be
 *     back-face culled and the arm would be invisible.</li>
 *     <li><strong>Where the arm starts inside that frame</strong> - {@code renderRightHand} /
 *     {@code renderLeftHand} draw the arm <em>where the player model puts it</em>: the right arm's box is
 *     {@code x [4, 8], y [-12, 0], z [-2, 2]} in bone-frame units for a classic skin (one unit narrower
 *     for a slim skin), not at the bone origin. This class measures that box (in production from the live
 *     {@code ModelPart}, see {@link PlayerArmRig}) and translates it onto the reference cube.</li>
 * </ol>
 *
 * <h2>The mapping</h2>
 * <pre>
 *   boneFramePoint = offset + MODEL_UNITS_PER_BLOCK * diag(1, -1, 1) * nativePoint
 *   offset         = referenceCubeCentre - MODEL_UNITS_PER_BLOCK * diag(1, -1, 1) * nativeArmCentre
 * </pre>
 * <p>In other words the arm's <em>centre</em> is mapped onto the reference cube's <em>centre</em>, which
 * is the technique PointBlank's {@code GunHandsItemLayer#applyArmRefTransforms} uses (it translates by
 * {@code refCubeCentre - sourceCubeCentre}, computed from the actual cube geometry, and never by a
 * constant). Anchoring on the centre instead of on the origin is what makes this work for both skin
 * models: a slim arm is one pixel narrower, so its centre is half a unit closer to the body, and it stays
 * centred in the same reference cube instead of drifting to one side.</p>
 *
 * <h2>Why nothing here is a tuned number</h2>
 * <p>The only constant is {@link #MODEL_UNITS_PER_BLOCK}, and it is not a correction factor: it is the unit
 * conversion between two spaces that differ by exactly that factor. Everything else comes from the model
 * ({@link #referenceBox}, derived from the bone's {@code 4x12x4} cube) and from Minecraft's own player
 * model (the box {@link PlayerArmRig} measures). The M100 DMR's numbers follow from those two alone: the
 * {@code right_hand} cube is centred at model space {@code (-0.59233, -2.39537, 9.84152)}, the classic
 * right arm at {@code (-0.375, 0.375, 0)} blocks -&gt; {@code (-6, -6, 0)} after the unit conversion and the
 * flip -&gt; offset {@code (5.40767, 3.60463, 9.84152)}. The {@code 9.84152} is the authored Z of the cube,
 * not a chosen offset, and it differs between the two hands ({@code -5.60726} for the left) exactly as the
 * author's rig does.</p>
 *
 * <p>Nothing is scaled to fit the cube. A vanilla arm is exactly {@code 4x12x4} pixels - the same box the
 * reference cube declares - so after the unit conversion the two boxes coincide for a classic skin, and
 * the slim arm is exactly one unit narrower inside the same cube. Fitting the arm to the cube would
 * stretch a slim arm to four units, which is a decision the weapon model is not allowed to make.</p>
 *
 * <h2>What the reference implementations do differently</h2>
 * <p>TaCZ ({@code RenderHelper#renderFirstPersonArm}) hands the vanilla arm straight to
 * {@code renderRightHand} because its {@code BedrockPart} transforms divide every part position by 16 as
 * well ({@code translateAndRotateAndScale}), so its gun and its arm are already in the same block-sized
 * space and no unit conversion is needed. This renderer keeps bone transforms in model units and applies
 * the single {@code SCALE/16} above them, so the unit conversion is needed exactly once, here, at the
 * native-arm boundary.</p>
 *
 * <p>TaCZ also rotates the arm by 180 degrees about Z ({@code poseStack.rotate(Axis.ZP, 180)} in
 * {@code RightHandRender}/{@code FirstPersonRenderGunEvent}), which in matrix terms is
 * {@code diag(-1, -1, 1)}. This class does not copy that call, and it must not: the project's global
 * {@code M = diag(-1, 1, 1)} is already above the arm, so the local flip only has to supply the missing Y
 * negation. The composition of the two is {@code M * diag(1, -1, 1) = diag(-1, -1, 1)} - exactly TaCZ's
 * 180 degree rotation. The reference behaviour is reproduced by the pair, and repeating the 180 here would
 * flip the arm a second time.</p>
 */
public final class ArmPlacement {

    /**
     * How many weapon-model units one block contains, i.e. the factor that converts the native player
     * model's block-sized geometry into the bone frame.
     *
     * <p>The value is the same 16 as {@link ViewBoneTransform#MODEL_UNITS_PER_BLOCK}; it is declared here
     * separately because it describes the <em>native</em> side of the boundary (Minecraft divides player
     * geometry by {@code ModelPart.Vertex.SCALE_FACTOR}) and because this class must stay usable without
     * the render pipeline. The two constants are asserted equal by the arm-placement tests.</p>
     */
    public static final float MODEL_UNITS_PER_BLOCK = 16.0F;

    private ArmPlacement() {
    }

    /**
     * An axis-aligned box. Used for the reference cube (bone frame) and for the native arm (blocks).
     *
     * @param min the componentwise minimum corner
     * @param max the componentwise maximum corner
     */
    public record Box(Vec3 min, Vec3 max) {

        public Box {
            // Normalise, so a caller cannot build an inverted box from two arbitrary corners.
            Vec3 low = new Vec3(Math.min(min.x(), max.x()), Math.min(min.y(), max.y()), Math.min(min.z(), max.z()));
            Vec3 high = new Vec3(Math.max(min.x(), max.x()), Math.max(min.y(), max.y()), Math.max(min.z(), max.z()));
            min = low;
            max = high;
        }

        /** @return the centre of the box */
        public Vec3 centre() {
            return new Vec3((min.x() + max.x()) * 0.5F, (min.y() + max.y()) * 0.5F, (min.z() + max.z()) * 0.5F);
        }

        /** @return the size of the box along each axis */
        public Vec3 size() {
            return new Vec3(max.x() - min.x(), max.y() - min.y(), max.z() - min.z());
        }

        /**
         * @param dx the X translation
         * @param dy the Y translation
         * @param dz the Z translation
         * @return this box moved by the given vector
         */
        public Box translated(float dx, float dy, float dz) {
            return new Box(new Vec3(min.x() + dx, min.y() + dy, min.z() + dz),
                    new Vec3(max.x() + dx, max.y() + dy, max.z() + dz));
        }

        /**
         * @param factor the uniform factor
         * @return this box scaled about the origin
         */
        public Box scaled(float factor) {
            return new Box(new Vec3(min.x() * factor, min.y() * factor, min.z() * factor),
                    new Vec3(max.x() * factor, max.y() * factor, max.z() * factor));
        }

        /** @return this box negated in Y, i.e. {@code diag(1, -1, 1)} applied to it */
        public Box flippedY() {
            return new Box(new Vec3(min.x(), -max.y(), min.z()), new Vec3(max.x(), -min.y(), max.z()));
        }
    }

    /**
     * One solved placement: the pose-stack operations that put the native arm into the bone frame.
     *
     * @param scale     the factor that converts the native block-sized arm into model units
     * @param offset    the bone-frame translation that centres the arm on the reference cube
     * @param reference the reference cube in the bone frame (cube corners minus the bone pivot)
     * @param nativeBox the native arm box as Minecraft reports it, in blocks
     */
    public record Placement(float scale, Vec3 offset, Box reference, Box nativeBox) {

        /**
         * Applies the placement to a pose stack that is already inside the arm rig bone.
         *
         * <p>Order matters: the offset is expressed in model units, so it is applied <em>before</em> the
         * unit scale, and the Y flip is innermost because it converts the arm's own axis convention. The
         * resulting matrix is {@code translate(offset) * scale(scale) * diag(1, -1, 1)}.</p>
         *
         * @param poseStack the pose stack at the arm rig bone
         */
        public void apply(PoseStack poseStack) {
            poseStack.translate(offset.x(), offset.y(), offset.z());
            poseStack.scale(scale, scale, scale);
            poseStack.scale(1.0F, -1.0F, 1.0F);
        }

        /**
         * @param nativePoint a point of the native arm, in blocks
         * @return that point in the bone frame
         */
        public Vec3 map(Vec3 nativePoint) {
            return new Vec3(offset.x() + scale * nativePoint.x(),
                    offset.y() - scale * nativePoint.y(),
                    offset.z() + scale * nativePoint.z());
        }

        /**
         * @param box a box of the native arm, in blocks
         * @return that box in the bone frame
         */
        public Box map(Box box) {
            return box.scaled(scale).flippedY().translated(offset.x(), offset.y(), offset.z());
        }
    }

    /**
     * Resolves the arm-reference cube of a bone: the bounding box of the bone's {@code cubes} geometry, in the space the
     * arm is submitted in.
     *
     * <p>That space is <strong>model space</strong>, not "model space relative to the pivot". The pose stack inside a
     * bone is a model-to-model transform: {@code AnimatedBoneTransform} composes
     * {@code T(pivot) * R * T(-pivot)}, so whatever is submitted inside that frame is expressed in the same coordinates
     * the bone's own cube vertices are written in. The arm therefore has to be mapped onto the cube's own model-space
     * box, and the pivot needs no special handling: it is already part of the authored cube coordinates, and the bone's
     * rotation moves the cube and the arm identically because both are submitted inside the same frame.</p>
     *
     * <p>The box is the union of every cube mesh of the bone, so a rig made of several cubes is handled without a
     * special case.</p>
     *
     * @param bone the arm rig bone
     * @return the reference box in model space, or {@code null} when the bone carries no cube geometry
     */
    public static Box referenceBox(ModelBone bone) {
        if (bone == null) {
            return null;
        }
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        boolean any = false;
        for (BoneMesh mesh : bone.meshes()) {
            if (mesh.source() != BoneMesh.Source.CUBE) {
                continue;
            }
            float[] positions = mesh.positionsUnsafe();
            for (int index = 0; index < positions.length; index += 3) {
                minX = Math.min(minX, positions[index]);
                minY = Math.min(minY, positions[index + 1]);
                minZ = Math.min(minZ, positions[index + 2]);
                maxX = Math.max(maxX, positions[index]);
                maxY = Math.max(maxY, positions[index + 1]);
                maxZ = Math.max(maxZ, positions[index + 2]);
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        return new Box(new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ));
    }

    /**
     * Solves the placement that centres a native arm box on a reference cube.
     *
     * @param referenceBox the arm-reference cube in the bone frame, see {@link #referenceBox(ModelBone)}
     * @param nativeBox    the native arm box in blocks, see {@link PlayerArmRig}
     * @return the placement, or {@code null} when either box is missing
     */
    public static Placement solve(Box referenceBox, Box nativeBox) {
        if (referenceBox == null || nativeBox == null) {
            return null;
        }
        Vec3 nativeCentre = nativeBox.centre();
        Vec3 targetCentre = referenceBox.centre();
        float scale = MODEL_UNITS_PER_BLOCK;
        // The native box mapped into the bone frame with no translation at all:
        // scale * diag(1, -1, 1) * centre. The offset is whatever is left to reach the reference centre.
        Vec3 offset = new Vec3(targetCentre.x() - scale * nativeCentre.x(),
                targetCentre.y() + scale * nativeCentre.y(),
                targetCentre.z() - scale * nativeCentre.z());
        return new Placement(scale, offset, referenceBox, nativeBox);
    }
}
