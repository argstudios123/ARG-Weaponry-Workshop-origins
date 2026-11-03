package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;


public final class WeaponPoseBlend {

    /** The identity delta of a scale channel: "the authored scale, unchanged". */
    private static final Vec3 UNIT_SCALE = new Vec3(1.0F, 1.0F, 1.0F);

    /** Below this cosine the slerp's angular form is numerically unstable, so a normalised lerp is used instead. */
    private static final double SLERP_LINEAR_THRESHOLD = 0.9995;

    private WeaponPoseBlend() {
    }

    /**
     * Blends two poses.
     *
     * @param from   the pose being faded out, may be {@code null} or empty (nothing to fade from)
     * @param to     the pose being faded in, may be {@code null} or empty (fading to the authored pose)
     * @param weight the blend position: {@code 0} is {@code from}, {@code 1} is {@code to}, values outside that range
     *               are clamped
     * @return the blended pose, never {@code null}; {@code from} or {@code to} themselves when the result is exactly one
     *         of them
     */
    public static WeaponPose blend(WeaponPose from, WeaponPose to, float weight) {
        float clamped = Float.isFinite(weight) ? Math.max(0.0F, Math.min(1.0F, weight)) : 1.0F;
        if (clamped >= 1.0F) {
            return to == null ? WeaponPose.NONE : to;
        }
        WeaponPose target = to == null ? WeaponPose.NONE : to;
        if (from == null || from.isEmpty()) {
            return target;
        }
        if (clamped <= 0.0F) {
            return from;
        }

        Map<String, SampledBone> bones = new LinkedHashMap<>(Math.max(4, target.boneCount() + from.boneCount()));
        // The target's order first, so the common case - the clip being entered - keeps its own bone order.
        for (Map.Entry<String, SampledBone> entry : target.bones().entrySet()) {
            bones.put(entry.getKey(), blendBone(from.bone(entry.getKey()), entry.getValue(), entry.getKey(), clamped));
        }
        for (Map.Entry<String, SampledBone> entry : from.bones().entrySet()) {
            if (!bones.containsKey(entry.getKey())) {
                bones.put(entry.getKey(), blendBone(entry.getValue(), null, entry.getKey(), clamped));
            }
        }
        return new WeaponPose(target.animationName(), target.timeSeconds(), bones, target.issues());
    }

    /**
     * Blends one bone.
     *
     * @param from     the outgoing bone, or {@code null} when the outgoing pose does not animate it
     * @param to       the incoming bone, or {@code null} when the incoming pose does not animate it
     * @param boneName the bone name the result must carry
     * @param weight   the blend position in {@code [0, 1]}
     * @return the blended bone, never {@code null}
     */
    private static SampledBone blendBone(SampledBone from, SampledBone to, String boneName, float weight) {
        Vec3 fromRotation = from == null ? null : from.rotation();
        Vec3 toRotation = to == null ? null : to.rotation();
        Vec3 fromPosition = from == null ? null : from.position();
        Vec3 toPosition = to == null ? null : to.position();
        Vec3 fromScale = from == null ? null : from.scale();
        Vec3 toScale = to == null ? null : to.scale();

        return new SampledBone(boneName,
                blendRotation(fromRotation, toRotation, weight),
                blendLinear(fromPosition == null ? Vec3.ZERO : fromPosition,
                        toPosition == null ? Vec3.ZERO : toPosition, weight),
                blendLinear(fromScale == null ? UNIT_SCALE : fromScale,
                        toScale == null ? UNIT_SCALE : toScale, weight));
    }

    /**
     * Interpolates one rotation triple along the shortest arc.
     *
     * <p>This is the rotation rule of the whole framework: {@link WeaponPoseBlend#blend} uses it for a full pose
     * cross-fade and {@link WeaponPoseLayers#overlay} uses it for the movement and aiming layers, so an animated rotation
     * always takes the geodesic between two values instead of a component-wise path through Euler space.</p>
     *
     * @param from   the outgoing rotation in authored degrees, or {@code null} for the identity delta
     * @param to     the incoming rotation in authored degrees, or {@code null} for the identity delta
     * @param weight the blend position in {@code [0, 1]}
     * @return the interpolated rotation in authored degrees, never {@code null}
     */
    public static Vec3 blendRotation(Vec3 from, Vec3 to, float weight) {
        Vec3 start = from == null ? Vec3.ZERO : from;
        Vec3 end = to == null ? Vec3.ZERO : to;
        if (weight <= 0.0F || end.equals(start)) {
            return start;
        }
        if (weight >= 1.0F) {
            return end;
        }

        double[] a = quaternion(start);
        double[] b = quaternion(end);

        // Shortest path: a quaternion and its negation describe the same rotation.
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        if (dot < 0.0) {
            b[0] = -b[0];
            b[1] = -b[1];
            b[2] = -b[2];
            b[3] = -b[3];
            dot = -dot;
        }

        double weight0;
        double weight1;
        if (dot > SLERP_LINEAR_THRESHOLD) {
            // Nearly identical: a normalised lerp is both stable and indistinguishable from the arc.
            weight0 = 1.0 - weight;
            weight1 = weight;
        } else {
            double angle = Math.acos(Math.min(1.0, dot));
            double sin = Math.sin(angle);
            weight0 = Math.sin((1.0 - weight) * angle) / sin;
            weight1 = Math.sin(weight * angle) / sin;
        }
        double qw = weight0 * a[0] + weight1 * b[0];
        double qx = weight0 * a[1] + weight1 * b[1];
        double qy = weight0 * a[2] + weight1 * b[2];
        double qz = weight0 * a[3] + weight1 * b[3];
        double norm = Math.sqrt(qw * qw + qx * qx + qy * qy + qz * qz);
        if (!(norm > 0.0)) {
            return start;
        }
        return euler(qw / norm, qx / norm, qy / norm, qz / norm);
    }

    /**
     * Converts an authored Euler triple to a quaternion using the renderer's {@code Rz * Ry * Rx} order.
     *
     * @param euler the rotation in authored degrees
     * @return {@code {w, x, y, z}}
     */
    private static double[] quaternion(Vec3 euler) {
        double halfX = Math.toRadians(euler.x()) * 0.5;
        double halfY = Math.toRadians(euler.y()) * 0.5;
        double halfZ = Math.toRadians(euler.z()) * 0.5;
        double cx = Math.cos(halfX);
        double sx = Math.sin(halfX);
        double cy = Math.cos(halfY);
        double sy = Math.sin(halfY);
        double cz = Math.cos(halfZ);
        double sz = Math.sin(halfZ);
        // q = qz * qy * qx, i.e. the matrix Rz * Ry * Rx the renderer composes.
        return new double[] {
                cz * cy * cx + sz * sy * sx,
                cz * cy * sx - sz * sy * cx,
                cz * sy * cx + sz * cy * sx,
                sz * cy * cx - cz * sy * sx
        };
    }

    /**
     * Converts a unit quaternion back to the authored Euler triple.
     *
     * @param qw the scalar part
     * @param qx the first vector component
     * @param qy the second vector component
     * @param qz the third vector component
     * @return the rotation in authored degrees
     */
    private static Vec3 euler(double qw, double qx, double qy, double qz) {
        double m00 = 1.0 - 2.0 * (qy * qy + qz * qz);
        double m01 = 2.0 * (qx * qy - qw * qz);
        double m10 = 2.0 * (qx * qy + qw * qz);
        double m11 = 1.0 - 2.0 * (qx * qx + qz * qz);
        double m20 = 2.0 * (qx * qz - qw * qy);
        double m21 = 2.0 * (qy * qz + qw * qx);
        double m22 = 1.0 - 2.0 * (qx * qx + qy * qy);

        double sinY = Math.max(-1.0, Math.min(1.0, -m20));
        double y = Math.asin(sinY);
        double x;
        double z;
        if (Math.abs(sinY) > 1.0 - 1.0E-6) {
            // Gimbal lock: the X and Z axes coincide, so the remaining rotation is folded into Z.
            x = 0.0;
            z = Math.atan2(-m01, m11);
        } else {
            x = Math.atan2(m21, m22);
            z = Math.atan2(m10, m00);
        }
        return new Vec3((float) Math.toDegrees(x), (float) Math.toDegrees(y), (float) Math.toDegrees(z));
    }

    /**
     * @param from   the outgoing triple
     * @param to     the incoming triple
     * @param weight the blend position in {@code [0, 1]}
     * @return the component-wise interpolation
     */
    static Vec3 blendLinear(Vec3 from, Vec3 to, float weight) {
        if (weight <= 0.0F) {
            return from;
        }
        if (weight >= 1.0F) {
            return to;
        }
        return new Vec3(from.x() + (to.x() - from.x()) * weight,
                from.y() + (to.y() - from.y()) * weight,
                from.z() + (to.z() - from.z()) * weight);
    }

    /**
     * The angle between two authored rotations, in degrees, ignoring wrap-around.
     *
     * <p>Used by diagnostics and tests: comparing the three Euler components directly would report a 340 degree
     * difference for two rotations that are 20 degrees apart across the {@code +-180} wrap.</p>
     *
     * @param first  the first rotation, or {@code null} for the identity delta
     * @param second the second rotation, or {@code null} for the identity delta
     * @return the angle in {@code [0, 180]} degrees
     */
    public static double angleDegrees(Vec3 first, Vec3 second) {
        double[] a = quaternion(first == null ? Vec3.ZERO : first);
        double[] b = quaternion(second == null ? Vec3.ZERO : second);
        double dot = Math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]);
        return Math.toDegrees(2.0 * Math.acos(Math.min(1.0, dot)));
    }
}
