package com.arg_studios.arg_weaponry.weapon.model;

import java.util.List;
import java.util.Map;

/**
 * An independent reimplementation of what Blockbench does to a Bedrock bone, used as the expected side of the
 * transform tests.
 *
 * <p>Nothing here is derived from {@link BoneTransformPolicy}. The rules are taken from the authoring tool itself:</p>
 * <ul>
 *   <li>a Bedrock file is displayed with its {@code pivot[0]} and its {@code rotation[0]}/{@code rotation[1]} negated
 *   (its {@code bedrock} codec negates exactly those on load);</li>
 *   <li>an animation keyframe has its {@code position[0]} and its {@code rotation[0]}/{@code rotation[1]} negated on
 *   load, so an animated offset is authored in the same mirrored space as the rest pose;</li>
 *   <li>the animated rotation is <em>added</em> to the rest Euler angles
 *   ({@code BoneAnimator#displayRotation} does {@code bone.rotation.x += arr[0]} and so on), and one
 *   {@code ZYX} rotation is built from the sum;</li>
 *   <li>the animated position is <em>added</em> to the bone's pivot ({@code bone.position += arr});</li>
 *   <li>a bone is {@code T(pivot + offset) * Rz * Ry * Rx * S}, and its geometry is relative to its pivot.</li>
 * </ul>
 *
 * <p>Blockbench's space is the mirror of the Bedrock model space this renderer composes in, so a single-bone matrix is
 * converted with {@code M * B * M}, where {@code M = diag(-1, 1, 1)}. A hierarchy is evaluated in Blockbench space and
 * only the resulting point is mirrored back, which keeps the parent-relative pivots in the form the authoring tool
 * actually uses.</p>
 */
final class BlockbenchTransformReference {

    private static final double[][] MIRROR = scale(-1.0, 1.0, 1.0);

    private BlockbenchTransformReference() {
    }

    /**
     * @param basis the Bedrock vector as written in the file
     * @return the same vector in Blockbench's displayed space (X negated)
     */
    static Vec3 displayedPosition(Vec3 basis) {
        return new Vec3(-basis.x(), basis.y(), basis.z());
    }

    /**
     * @param basis the Bedrock Euler triple as written in the file
     * @return the same triple in Blockbench's displayed space (X and Y negated)
     */
    static Vec3 displayedRotation(Vec3 basis) {
        return new Vec3(-basis.x(), -basis.y(), basis.z());
    }

    /**
     * The single-bone transform in Blockbench space.
     *
     * @param bone              the bone
     * @param animatedPosition  the keyframed position offset, or {@code null}
     * @param animatedRotation  the keyframed rotation delta, or {@code null}
     * @param animatedScale     the keyframed scale, or {@code null}
     * @return {@code T(pivot + offset) * Rz * Ry * Rx(authored + animated) * S * T(-pivot)}
     */
    static double[][] boneMatrix(ModelBone bone, Vec3 animatedPosition, Vec3 animatedRotation, Vec3 animatedScale) {
        Vec3 pivot = displayedPosition(bone.pivot());
        Vec3 offset = animatedPosition == null ? Vec3.ZERO : displayedPosition(animatedPosition);
        Vec3 authored = displayedRotation(bone.rotation());
        Vec3 animated = animatedRotation == null ? Vec3.ZERO : displayedRotation(animatedRotation);
        Vec3 rotation = add(authored, animated);

        double[][] matrix = translation(pivot.x() + offset.x(), pivot.y() + offset.y(), pivot.z() + offset.z());
        matrix = multiply(matrix, zyx(rotation));
        if (animatedScale != null) {
            matrix = multiply(matrix, scale(animatedScale.x(), animatedScale.y(), animatedScale.z()));
        }
        return multiply(matrix, translation(-pivot.x(), -pivot.y(), -pivot.z()));
    }

    /**
     * @return the same single-bone transform expressed in the model space the renderer composes in
     */
    static double[][] modelMatrix(ModelBone bone, Vec3 animatedPosition, Vec3 animatedRotation, Vec3 animatedScale) {
        return mirrorConjugate(boneMatrix(bone, animatedPosition, animatedRotation, animatedScale));
    }

    static Vec3 add(Vec3 first, Vec3 second) {
        return new Vec3(first.x() + second.x(), first.y() + second.y(), first.z() + second.z());
    }

    /**
     * Composes a root-to-leaf chain in Blockbench space, exactly the way nested groups work there: each bone's local
     * position is {@code pivot - parentPivot + animatedOffset}, and its local matrix is
     * {@code T(localPosition) * R(authored + animated) * S}.
     *
     * @param chain     the chain, root first
     * @param positions animated position channels keyed by bone name
     * @param rotations animated rotation channels keyed by bone name
     * @param scales    animated scale channels keyed by bone name
     * @return the world matrix of the leaf bone in Blockbench space
     */
    static double[][] chainWorldMatrix(List<ModelBone> chain, Map<String, Vec3> positions,
                                       Map<String, Vec3> rotations, Map<String, Vec3> scales) {
        double[][] world = identity();
        Vec3 previousPivot = Vec3.ZERO;
        for (ModelBone bone : chain) {
            Vec3 pivot = displayedPosition(bone.pivot());
            Vec3 offset = positions.containsKey(bone.name()) ? displayedPosition(positions.get(bone.name())) : Vec3.ZERO;
            Vec3 localPosition = new Vec3(pivot.x() - previousPivot.x() + offset.x(),
                    pivot.y() - previousPivot.y() + offset.y(),
                    pivot.z() - previousPivot.z() + offset.z());

            Vec3 authored = displayedRotation(bone.rotation());
            Vec3 animated = rotations.containsKey(bone.name()) ? displayedRotation(rotations.get(bone.name())) : Vec3.ZERO;
            Vec3 rotation = add(authored, animated);
            Vec3 animatedScale = scales.get(bone.name());

            double[][] local = translation(localPosition.x(), localPosition.y(), localPosition.z());
            local = multiply(local, zyx(rotation));
            if (animatedScale != null) {
                local = multiply(local, scale(animatedScale.x(), animatedScale.y(), animatedScale.z()));
            }
            world = multiply(world, local);
            previousPivot = pivot;
        }
        return world;
    }

    /**
     * Transforms one geometry point through a root-to-leaf chain.
     *
     * @param chain    the chain, root first
     * @param point    the point in renderer model space (absolute Bedrock coordinates)
     * @param positions animated position channels keyed by bone name
     * @param rotations animated rotation channels keyed by bone name
     * @param scales   animated scale channels keyed by bone name
     * @return the transformed point, back in renderer model space
     */
    static Vec3 transformPoint(List<ModelBone> chain, Vec3 point, Map<String, Vec3> positions,
                               Map<String, Vec3> rotations, Map<String, Vec3> scales) {
        ModelBone leaf = chain.get(chain.size() - 1);
        Vec3 pointInBlockbenchSpace = displayedPosition(point);
        Vec3 leafPivot = displayedPosition(leaf.pivot());
        double[][] world = chainWorldMatrix(chain, positions, rotations, scales);
        double[] transformed = multiply(world, new double[] {
                pointInBlockbenchSpace.x() - leafPivot.x(),
                pointInBlockbenchSpace.y() - leafPivot.y(),
                pointInBlockbenchSpace.z() - leafPivot.z(),
                1.0
        });
        return displayedPosition(new Vec3((float) transformed[0], (float) transformed[1], (float) transformed[2]));
    }

    // ---------------------------------------------------------------- matrix helpers

    static double[][] identity() {
        return new double[][] {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}};
    }

    static double[][] translation(double x, double y, double z) {
        return new double[][] {{1, 0, 0, x}, {0, 1, 0, y}, {0, 0, 1, z}, {0, 0, 0, 1}};
    }

    static double[][] scale(double x, double y, double z) {
        return new double[][] {{x, 0, 0, 0}, {0, y, 0, 0}, {0, 0, z, 0}, {0, 0, 0, 1}};
    }

    static double[][] rotationX(double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new double[][] {{1, 0, 0, 0}, {0, cos, -sin, 0}, {0, sin, cos, 0}, {0, 0, 0, 1}};
    }

    static double[][] rotationY(double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new double[][] {{cos, 0, sin, 0}, {0, 1, 0, 0}, {-sin, 0, cos, 0}, {0, 0, 0, 1}};
    }

    static double[][] rotationZ(double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new double[][] {{cos, -sin, 0, 0}, {sin, cos, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}};
    }

    /** @return {@code Rz * Ry * Rx}, the Blockbench {@code ZYX} Euler order. */
    static double[][] zyx(Vec3 degrees) {
        return multiply(multiply(rotationZ(degrees.z()), rotationY(degrees.y())), rotationX(degrees.x()));
    }

    /** @return {@code M * matrix * M}, with {@code M = diag(-1, 1, 1)}. */
    static double[][] mirrorConjugate(double[][] matrix) {
        return multiply(MIRROR, multiply(matrix, MIRROR));
    }

    static double[][] multiply(double[][] left, double[][] right) {
        double[][] result = new double[4][4];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                double sum = 0.0;
                for (int index = 0; index < 4; index++) {
                    sum += left[row][index] * right[index][column];
                }
                result[row][column] = sum;
            }
        }
        return result;
    }

    /** @return {@code matrix * vector}, with the vector treated as a point ({@code w = 1}). */
    static double[] multiply(double[][] matrix, double[] vector) {
        double[] result = new double[4];
        for (int row = 0; row < 4; row++) {
            double sum = 0.0;
            for (int column = 0; column < 4; column++) {
                sum += matrix[row][column] * vector[column];
            }
            result[row] = sum;
        }
        return result;
    }
}
