package com.arg_studios.arg_weaponry.weapon.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The exact ordered transform a bone contributes, as a value rather than as a sequence of {@code PoseStack} calls.
 *
 * <h2>Why it is a separate, pure type</h2>
 * <p>{@link com.arg_studios.arg_weaponry.client.render.AnimatedBoneTransform} used to decide the composition inline, next to
 * the {@code PoseStack} calls. That made the one thing that must not drift - the <em>order</em> and the <em>space</em> of the
 * steps - impossible to test without a running game. This class publishes the same decision as a list of steps, so a plain
 * unit test can assert it, and the renderer only executes the list.</p>
 *
 * <h2>The composition (Bedrock / Blockbench semantics)</h2>
 * <pre>
 * T(animatedPosition) T(pivot) Rz Ry Rx(authored + animated) S(animatedScale) T(-pivot)
 * </pre>
 * <ul>
 *   <li>the animated position is an <em>additive offset in the parent's space</em> and is applied before the pivot, so it
 *   moves the whole bone instead of the pivot (Bedrock: {@code translate} then {@code translate(pivot)});</li>
 *   <li>the animated rotation is <strong>added to the authored rotation, axis by axis, before the single Z, Y, X rotation
 *   is formed</strong>. That is exactly what Blockbench does when it previews a Bedrock animation: its
 *   {@code BoneAnimator#displayRotation} adds the keyframe degrees to the bone's Euler components
 *   ({@code bone.rotation.x += arr[0]} and so on) and three.js then builds one {@code ZYX} matrix from the sum. Composing
 *   two separate matrices instead is <em>not</em> the same operation: {@code R(a) * R(b)} equals {@code R(a + b)} only
 *   when the rotations commute, and on the hand bones - whose authored rotations are around {@code -90} to {@code -180}
 *   degrees - the two results can differ by more than a hundred degrees;</li>
 *   <li>the scale is a per-axis multiplier about the pivot;</li>
 *   <li>every rotation is passed through {@link RotationConvention#toRenderSpace(Vec3)}, which is the mirror correction the
 *   whole model needs, after the two triples have been added. The conversion is linear per component, so adding first or
 *   converting first gives the same result; adding first is what keeps the arithmetic identical to the authoring tool.</li>
 * </ul>
 *
 * <p>Geometry stays in model space, so a bone's cube vertices are rotated about the authored pivot exactly as Blockbench
 * shows them.</p>
 */
public final class BoneTransformPolicy {

    /** What one step does. Rotation steps carry the angle in degrees about that axis. */
    public enum Kind {
        /** Move by the value, in model units. */
        TRANSLATE,
        /** Rotate about X by the value, in degrees. */
        ROTATE_X,
        /** Rotate about Y by the value, in degrees. */
        ROTATE_Y,
        /** Rotate about Z by the value, in degrees. */
        ROTATE_Z,
        /** Scale by the value, per axis. */
        SCALE
    }

    /**
     * @param kind  what the step does
     * @param value the step's value: a translation, a per-axis scale, or an angle (in the X, Y or Z component of a triple
     *              whose other two components are zero)
     */
    public record Step(Kind kind, Vec3 value) {
    }

    private BoneTransformPolicy() {
    }

    /**
     * Builds the ordered steps of one bone.
     *
     * @param bone              the bone, never {@code null}
     * @param animatedPosition  the animated offset, or {@code null} for none
     * @param animatedRotation  the animated rotation delta in authored degrees, or {@code null} for none
     * @param animatedScale     the animated scale, or {@code null} for none
     * @return the steps in composition order, never {@code null}
     */
    public static List<Step> steps(ModelBone bone, Vec3 animatedPosition, Vec3 animatedRotation, Vec3 animatedScale) {
        List<Step> steps = new ArrayList<>(9);
        Vec3 pivot = bone.pivot();

        if (animatedPosition != null && !animatedPosition.isZero()) {
            steps.add(new Step(Kind.TRANSLATE, animatedPosition));
        }
        if (!pivot.isZero()) {
            steps.add(new Step(Kind.TRANSLATE, pivot));
        }

        // One rotation, built from the sum of the authored and the animated Euler angles - not the product of two
        // rotations. See the class documentation for why the distinction is the whole difference on the hand bones.
        Vec3 rotation = authoredPlusAnimated(bone.rotation(), animatedRotation);
        if (!rotation.isZero()) {
            Vec3 converted = RotationConvention.toRenderSpace(rotation);
            if (converted.z() != 0.0F) {
                steps.add(new Step(Kind.ROTATE_Z, new Vec3(0.0F, 0.0F, converted.z())));
            }
            if (converted.y() != 0.0F) {
                steps.add(new Step(Kind.ROTATE_Y, new Vec3(0.0F, converted.y(), 0.0F)));
            }
            if (converted.x() != 0.0F) {
                steps.add(new Step(Kind.ROTATE_X, new Vec3(converted.x(), 0.0F, 0.0F)));
            }
        }

        if (animatedScale != null) {
            steps.add(new Step(Kind.SCALE, animatedScale));
        }
        if (!pivot.isZero()) {
            steps.add(new Step(Kind.TRANSLATE, new Vec3(-pivot.x(), -pivot.y(), -pivot.z())));
        }
        return steps;
    }

    /**
     * Adds the animated Euler triple to the authored one, component by component.
     *
     * <p>This is the operation Blockbench performs before it builds a bone matrix: the animation channel is a delta on
     * the authored angles, not a second rotation to append. Both triples are in the same Bedrock-space degrees here; the
     * mirror conversion happens once, afterwards, in {@link RotationConvention#toRenderSpace(Vec3)}.</p>
     *
     * @param authored the authored rotation, never {@code null}
     * @param animated the animated rotation, may be {@code null}
     * @return the sum, or the authored rotation itself when the animation does not animate rotation
     */
    public static Vec3 authoredPlusAnimated(Vec3 authored, Vec3 animated) {
        if (animated == null) {
            return authored;
        }
        return new Vec3(authored.x() + animated.x(), authored.y() + animated.y(), authored.z() + animated.z());
    }
}
