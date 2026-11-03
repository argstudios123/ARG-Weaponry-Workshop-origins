package com.arg_studios.arg_weaponry.weapon.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.animation.AnimationFile;
import com.arg_studios.arg_weaponry.weapon.animation.AnimationSampler;
import com.arg_studios.arg_weaponry.weapon.animation.BedrockAnimation;
import com.arg_studios.arg_weaponry.weapon.animation.BedrockAnimationParser;
import com.arg_studios.arg_weaponry.weapon.animation.ChannelKind;
import com.arg_studios.arg_weaponry.weapon.animation.SampledBone;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The shipped animation files, parsed and sampled exactly as the game does, transformed exactly like Blockbench.
 *
 * <p>The unit tests around the policy use values typed into a test. This one closes the remaining gap: it reads the real
 * M100 DMR geometry and animation documents from the test classpath through the real {@link BedrockGeometryParser} and
 * {@link BedrockAnimationParser}, samples the real reload and inspect clips through {@link AnimationSampler}, and compares
 * the resulting hand transforms with the independent Blockbench reference. A change to the parser, the sampler or the
 * transform policy that breaks the existing content is therefore caught here, not only in game.</p>
 */
class ShippedHandAnimationTransformTest {

    private static final String PACK = "assets/arg_weaponry/weapon_packs/arg_default_pack/assets/";
    private static final String MODEL_PATH = PACK + "arg_pack/models/weapons/dmrs/m100_dmr/m100_dmr.json";
    private static final String ANIMATION_PATH = PACK
            + "arg_pack/animations/weapons/dmrs/m100_dmr/m100_dmr.animation.json";

    private static final String LEFT_HAND = "left_hand";
    private static final String RIGHT_HAND = "right_hand";

    @Test
    void theShippedHandAnimationClipsTransformLikeBlockbench() throws Exception {
        WeaponModel model = shippedModel();
        AnimationFile animations = shippedAnimations();

        // anim.model.reload animates the support hand's rotation and position;
        // anim.model.inspect animates both hands strongly;
        // anim.model.idle keeps both hand rotations at zero and only carries offsets/scale.
        assertHandMatches(model, animations, "anim.model.reload", 0.24, LEFT_HAND);
        assertHandMatches(model, animations, "anim.model.inspect", 3.2083, LEFT_HAND);
        assertHandMatches(model, animations, "anim.model.inspect", 10.25, RIGHT_HAND);
        assertHandMatches(model, animations, "anim.model.idle", 0.0, LEFT_HAND);
        assertHandMatches(model, animations, "anim.model.idle", 0.0, RIGHT_HAND);
    }

    @Test
    void theShippedHandsAreActuallyAnimatedByTheClipsThatAreExpectedToMoveThem() throws Exception {
        AnimationFile animations = shippedAnimations();
        for (String clip : List.of("anim.model.reload", "anim.model.inspect")) {
            BedrockAnimation animation = animations.animation(clip);
            assertNotNull(animation, clip);
            assertTrue(animation.bones().containsKey(LEFT_HAND), clip + " must animate " + LEFT_HAND);
        }
        BedrockAnimation inspect = animations.animation("anim.model.inspect");
        assertTrue(inspect.bones().containsKey(RIGHT_HAND), "anim.model.inspect must animate " + RIGHT_HAND);

        WeaponPose reload = WeaponPose.fromSampledAnimation(AnimationSampler.sample(
                animations.animation("anim.model.reload"), 0.24));
        SampledBone leftHand = reload.bone(LEFT_HAND);
        assertNotNull(leftHand);
        assertNotNull(leftHand.rotation());
        assertTrue(animationMagnitude(leftHand.rotation()) > 1.0,
                "the reload keyframe used by the transform test must have a non-trivial rotation");
    }

    // ---------------------------------------------------------------- shipped resources

    private static WeaponModel shippedModel() throws Exception {
        JsonObject root = readJson(MODEL_PATH).getAsJsonObject();
        ValidationReport report = new ValidationReport();
        WeaponModel model = BedrockGeometryParser.parse("arg_default_pack",
                new ResourceId("arg_pack", "models/weapons/dmrs/m100_dmr/m100_dmr"), "m100_dmr.json", root, report);
        assertNotNull(model, "the shipped model did not parse");
        assertFalse(report.hasErrors(), "the shipped model must parse without errors");
        return model;
    }

    private static AnimationFile shippedAnimations() throws Exception {
        JsonObject root = readJson(ANIMATION_PATH).getAsJsonObject();
        ValidationReport report = new ValidationReport();
        AnimationFile file = BedrockAnimationParser.parse("arg_default_pack",
                new ResourceId("arg_pack", "animations/weapons/dmrs/m100_dmr/m100_dmr"),
                "m100_dmr.animation.json", root, report);
        assertNotNull(file, "the shipped animation document did not parse");
        assertFalse(report.hasErrors(), "the shipped animation document must parse without errors");
        return file;
    }

    private static JsonElement readJson(String resource) throws Exception {
        try (InputStream stream = ShippedHandAnimationTransformTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(stream, "the shipped resource " + resource + " is not on the test classpath");
            return JsoncReader.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    // ---------------------------------------------------------------- comparison

    private static void assertHandMatches(WeaponModel model, AnimationFile animations, String clip, double time,
                                          String boneName) {
        BedrockAnimation animation = animations.animation(clip);
        assertNotNull(animation, "the shipped document has no animation '" + clip + "'");
        WeaponPose pose = WeaponPose.fromSampledAnimation(AnimationSampler.sample(animation, time));
        SampledBone sampled = pose.bone(boneName);
        assertNotNull(sampled, "'" + clip + "' must animate '" + boneName + "'");

        List<ModelBone> chain = chain(model, boneName);
        List<Vec3> points = geometryPoints(model.bone(boneName));

        Map<String, Vec3> positions = channel(pose, chain, ChannelKind.POSITION);
        Map<String, Vec3> rotations = channel(pose, chain, ChannelKind.ROTATION);
        Map<String, Vec3> scales = channel(pose, chain, ChannelKind.SCALE);

        for (int index = 0; index < points.size(); index++) {
            Vec3 point = points.get(index);
            Vec3 expected = BlockbenchTransformReference.transformPoint(chain, point, positions, rotations, scales);
            Vec3 actual = transform(chain, point, pose);
            String where = clip + " at " + time + " s, " + boneName + " vertex " + index;
            assertEquals(expected.x(), actual.x(), 2.0E-3, where + " x");
            assertEquals(expected.y(), actual.y(), 2.0E-3, where + " y");
            assertEquals(expected.z(), actual.z(), 2.0E-3, where + " z");
        }
    }

    private static Map<String, Vec3> channel(WeaponPose pose, List<ModelBone> chain, ChannelKind kind) {
        Map<String, Vec3> values = new LinkedHashMap<>();
        for (ModelBone bone : chain) {
            SampledBone sampled = pose.bone(bone.name());
            if (sampled != null && sampled.channel(kind) != null) {
                values.put(bone.name(), sampled.channel(kind));
            }
        }
        return values;
    }

    private static List<ModelBone> chain(WeaponModel model, String boneName) {
        List<ModelBone> reversed = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        ModelBone current = model.bone(boneName);
        while (current != null && visited.add(current.name())) {
            reversed.add(current);
            current = current.parent() == null ? null : model.bone(current.parent());
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static List<Vec3> geometryPoints(ModelBone bone) {
        assertNotNull(bone, "the shipped model has no bone to test");
        assertFalse(bone.meshes().isEmpty(), "the arm rig bone must carry its reference cube");
        float[] positions = bone.meshes().get(0).positionsUnsafe();
        assertTrue(positions.length >= 3 && positions.length % 3 == 0,
                "the reference cube must expose whole vertices");
        List<Vec3> points = new ArrayList<>(positions.length / 3);
        for (int index = 0; index < positions.length; index += 3) {
            points.add(new Vec3(positions[index], positions[index + 1], positions[index + 2]));
        }
        return points;
    }

    private static Vec3 transform(List<ModelBone> chain, Vec3 point, WeaponPose pose) {
        double[][] total = BlockbenchTransformReference.identity();
        for (ModelBone bone : chain) {
            SampledBone sampled = pose.bone(bone.name());
            total = BlockbenchTransformReference.multiply(total, compose(BoneTransformPolicy.steps(bone,
                    sampled == null ? null : sampled.position(),
                    sampled == null ? null : sampled.rotation(),
                    sampled == null ? null : sampled.scale())));
        }
        double[] transformed = BlockbenchTransformReference.multiply(total,
                new double[] {point.x(), point.y(), point.z(), 1.0});
        return new Vec3((float) transformed[0], (float) transformed[1], (float) transformed[2]);
    }

    private static double[][] compose(List<BoneTransformPolicy.Step> steps) {
        double[][] result = BlockbenchTransformReference.identity();
        for (BoneTransformPolicy.Step step : steps) {
            Vec3 value = step.value();
            double[][] next = switch (step.kind()) {
                case TRANSLATE -> BlockbenchTransformReference.translation(value.x(), value.y(), value.z());
                case ROTATE_X -> BlockbenchTransformReference.rotationX(value.x());
                case ROTATE_Y -> BlockbenchTransformReference.rotationY(value.y());
                case ROTATE_Z -> BlockbenchTransformReference.rotationZ(value.z());
                case SCALE -> BlockbenchTransformReference.scale(value.x(), value.y(), value.z());
            };
            result = BlockbenchTransformReference.multiply(result, next);
        }
        return result;
    }

    private static float animationMagnitude(Vec3 rotation) {
        return Math.max(Math.abs(rotation.x()), Math.max(Math.abs(rotation.y()), Math.abs(rotation.z())));
    }
}
