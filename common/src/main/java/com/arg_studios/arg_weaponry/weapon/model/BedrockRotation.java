package com.arg_studios.arg_weaponry.weapon.model;

/**
 * Rotates a point by a Bedrock-authored Euler triple, with the exact composition order the format and the authoring tool
 * use.
 *
 * <h2>Why this is a shared, pure type</h2>
 * <p>Two places rotate geometry by an authored triple: the parser, when it bakes a <em>cube</em> rotation into the
 * vertices around the cube's pivot, and the view placement, when it resolves an anchor pivot through its ancestors. Both
 * have to agree with the <em>bone</em> composition, which is {@code Rz * Ry * Rx} - Blockbench's {@code ZYX} Euler order,
 * the order TaCZ's reference implementation uses, and the order {@link BoneTransformPolicy} publishes for the renderer.</p>
 *
 * <p>Composing the same angles in a different order is <em>not</em> the same rotation: {@code Rx * Ry * Rz} differs from
 * {@code Rz * Ry * Rx} as soon as more than one axis is involved, by tens of degrees on this content. There is therefore
 * exactly one implementation of the arithmetic here, and every caller uses it.</p>
 *
 * <h2>Which triple to feed</h2>
 * <p>The triple must already be in the space the renderer composes in, i.e. it must have been passed through
 * {@link RotationConvention#toRenderSpace(Vec3)}. This class only does the rotation; it deliberately knows nothing about
 * the Bedrock-to-Minecraft handedness conversion, so a caller cannot accidentally apply one without the other half.</p>
 */
public final class BedrockRotation {

    private BedrockRotation() {
    }

    /**
     * Rotates one vector about the origin.
     *
     * <p>The angles are applied X first, then Y, then Z, which produces the matrix {@code Rz * Ry * Rx}. That is the same
     * composition the bone transform performs, so a cube rotation and a bone rotation authored with the same triple
     * produce the same orientation.</p>
     *
     * @param vector  the vector to rotate, in model units
     * @param degrees the rotation triple in degrees, already converted for the render space
     * @return the rotated vector, or the input when there is nothing to rotate
     */
    public static Vec3 rotate(Vec3 vector, Vec3 degrees) {
        if (vector == null) {
            return Vec3.ZERO;
        }
        if (degrees == null || degrees.isZero() || vector.isZero()) {
            return vector;
        }

        double x = vector.x();
        double y = vector.y();
        double z = vector.z();

        // X first: the rightmost factor of Rz * Ry * Rx.
        if (degrees.x() != 0.0F) {
            double radians = Math.toRadians(degrees.x());
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double rotatedY = y * cos - z * sin;
            double rotatedZ = y * sin + z * cos;
            y = rotatedY;
            z = rotatedZ;
        }
        // Then Y.
        if (degrees.y() != 0.0F) {
            double radians = Math.toRadians(degrees.y());
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double rotatedX = x * cos + z * sin;
            double rotatedZ = -x * sin + z * cos;
            x = rotatedX;
            z = rotatedZ;
        }
        // Then Z, the leftmost factor.
        if (degrees.z() != 0.0F) {
            double radians = Math.toRadians(degrees.z());
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double rotatedX = x * cos - y * sin;
            double rotatedY = x * sin + y * cos;
            x = rotatedX;
            y = rotatedY;
        }

        return new Vec3((float) x, (float) y, (float) z);
    }

    /**
     * Rotates one point around a pivot.
     *
     * @param point   the point to rotate, in model space
     * @param pivot   the pivot to rotate around, in the same space
     * @param degrees the rotation triple in degrees, already converted for the render space
     * @return the rotated point, or the input when there is nothing to rotate
     */
    public static Vec3 rotateAround(Vec3 point, Vec3 pivot, Vec3 degrees) {
        if (point == null) {
            return Vec3.ZERO;
        }
        if (degrees == null || degrees.isZero() || pivot == null) {
            return point;
        }
        Vec3 relative = new Vec3(point.x() - pivot.x(), point.y() - pivot.y(), point.z() - pivot.z());
        Vec3 rotated = rotate(relative, degrees);
        return new Vec3(rotated.x() + pivot.x(), rotated.y() + pivot.y(), rotated.z() + pivot.z());
    }
}
