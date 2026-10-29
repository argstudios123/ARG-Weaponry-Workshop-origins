package com.arg_studios.arg_weaponry.weapon.animation;

import com.arg_studios.arg_weaponry.weapon.model.Vec3;

import java.util.List;

/**
 * One animated channel of one bone.
 *
 * <p>The Bedrock format writes a channel in two structurally different shapes and this model keeps
 * them apart instead of collapsing one into the other:</p>
 * <ul>
 *     <li>{@link Constant} — the property holds a single value ({@code "scale": 0} or
 *     {@code "position": [0, 0, 2.0625]}). The value applies for the whole animation; no keyframes are
 *     invented for it.</li>
 *     <li>{@link Keyframed} — the property holds a timestamp-keyed object such as
 *     {@code {"0.0": [0,0,0], "0.0167": [0,0,2.2]}}. The keyframes are stored in ascending time order.</li>
 * </ul>
 *
 * <p>Which channel of a bone this is (rotation, position or scale) is expressed by the slot it occupies
 * in {@link BoneAnimation}, not by the channel itself.</p>
 */
public sealed interface AnimationChannel permits AnimationChannel.Constant, AnimationChannel.Keyframed {

    /** @return {@code true} for a single value that applies throughout the animation. */
    boolean isConstant();

    /**
     * A single value applied for the whole animation.
     *
     * @param value            the value, with a scalar expanded uniformly to all three components
     * @param declaredAsScalar {@code true} when the JSON wrote a bare number instead of a vector
     */
    record Constant(Vec3 value, boolean declaredAsScalar) implements AnimationChannel {

        public Constant {
            if (value == null) {
                throw new IllegalArgumentException("A constant channel must have a value");
            }
        }

        /**
         * Creates a constant channel from a vector written in the JSON.
         *
         * @param value the vector as written
         * @return the channel
         */
        public static Constant ofVector(Vec3 value) {
            return new Constant(value, false);
        }

        /**
         * Creates a constant channel from a bare number written in the JSON.
         *
         * <p>The format defines a scalar channel value as a uniform scale on all three axes, so the
         * scalar is expanded to {@code (s, s, s)}. The {@code declaredAsScalar} flag keeps the original
         * shape observable.</p>
         *
         * @param scalar the scalar as written, for example {@code 0} for a hidden bone
         * @return the channel
         */
        public static Constant ofScalar(double scalar) {
            if (!Double.isFinite(scalar)) {
                throw new IllegalArgumentException("Non-finite scalar channel value: " + scalar);
            }
            float component = (float) scalar;
            return new Constant(new Vec3(component, component, component), true);
        }

        @Override
        public boolean isConstant() {
            return true;
        }
    }

    /**
     * A timestamp-keyed sequence of keyframes, ordered by ascending time.
     *
     * @param keyframes the keyframes; never empty, never modifiable
     */
    record Keyframed(List<Keyframe> keyframes) implements AnimationChannel {

        public Keyframed {
            if (keyframes == null || keyframes.isEmpty()) {
                throw new IllegalArgumentException("A keyframed channel must have at least one keyframe");
            }
            keyframes = List.copyOf(keyframes);
        }

        @Override
        public boolean isConstant() {
            return false;
        }

        /** @return the first keyframe, the value at the start of the animation. */
        public Keyframe first() {
            return keyframes.get(0);
        }

        /** @return the last keyframe, the value at the end of the animation. */
        public Keyframe last() {
            return keyframes.get(keyframes.size() - 1);
        }
    }
}
