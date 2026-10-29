package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.animation.AnimationSampler;
import com.arg_studios.arg_weaponry.weapon.animation.BedrockAnimation;
import com.arg_studios.arg_weaponry.weapon.animation.ChannelKind;
import com.arg_studios.arg_weaponry.weapon.animation.SampledBone;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationCache;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.dev.DevelopmentWeapons;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;

import java.util.Optional;

/**
 * Development-only fixed pose used to see the pose pipeline in game.
 *
 * <p>This is <strong>not</strong> playback: it samples one real animation of the held weapon at one fixed
 * timestamp and hands the result to the renderer, so the sampling to pose to bone-transform path can be
 * inspected visually. It never runs on its own, never advances a time, never triggers on firing, reloading,
 * inspecting or walking, and cannot be reached in a production environment without forcing both switches.</p>
 *
 * <h2>Enabling it</h2>
 * <pre>
 * -Darg_weaponry.dev_weapons=true -Darg_weaponry.dev_animation_pose=true
 * </pre>
 * <p>The second property alone is not enough: {@link DevelopmentWeapons#isEnabled()} must also be true, so a
 * normal production client stays untouched. Without the properties the renderer receives no pose at all and
 * draws the weapon exactly as before.</p>
 *
 * <h2>What it applies</h2>
 * <ul>
 *     <li>animation {@value #ANIMATION_NAME}, which exists in the M100 DMR animation file,</li>
 *     <li>sampled at {@value #SAMPLE_TIME_SECONDS} seconds, which is an authored keyframe time,</li>
 *     <li>the whole sampled pose, not a filtered subset of it,</li>
 *     <li>the easy to see change is {@code bolt2.position}: the bolt carrier slides along its own Z axis by
 *     4.41 model units instead of keeping its authored zero offset.</li>
 * </ul>
 *
 * <p>The parsed pose is remembered for the animation resource it came from and rebuilt when that resource
 * changes, purely so the 121 KB animation file is not re-read on every frame. This is a development fixture,
 * not the animation cache of the next milestone: it is keyed by a single resource, is only ever built when
 * the switch above is on, and is discarded together with the render caches.</p>
 */
public final class DevelopmentAnimationPose {

    /** System property that turns the fixed development pose on. Off unless explicitly set to {@code true}. */
    public static final String ENABLE_PROPERTY = "arg_weaponry.dev_animation_pose";

    /** The animation sampled by the development pose. */
    public static final String ANIMATION_NAME = "anim.model.fire";

    /** The fixed, documented sample time, which is an authored keyframe time of {@link #ANIMATION_NAME}. */
    public static final double SAMPLE_TIME_SECONDS = 0.0333;

    /** The bone whose change is easiest to see. */
    public static final String ANIMATED_BONE = "bolt2";

    /** The channel that changes on {@link #ANIMATED_BONE}. */
    public static final ChannelKind ANIMATED_CHANNEL = ChannelKind.POSITION;

    /** The expected sampled value of {@link #ANIMATED_CHANNEL} on {@link #ANIMATED_BONE}. */
    public static final Vec3 EXPECTED_VALUE = new Vec3(0.0F, 0.0F, 4.41F);

    private static volatile ResourceId builtFor;
    private static volatile WeaponPose fixedPose;

    private DevelopmentAnimationPose() {
    }

    /** @return {@code true} when both the pose switch and the development weapon switch are on. */
    public static boolean isEnabled() {
        String override = System.getProperty(ENABLE_PROPERTY);
        if (override == null || !Boolean.parseBoolean(override.trim())) {
            return false;
        }
        return DevelopmentWeapons.isEnabled();
    }

    /**
     * Returns the fixed development pose for a weapon, or {@code null} when the fixture is disabled or
     * cannot be built.
     *
     * @param pack    the content pack that owns the weapon
     * @param display the resolved display configuration of the held weapon
     * @return the fixed pose, or {@code null} to render statically
     */
    public static WeaponPose poseFor(ContentPack pack, WeaponDisplay display) {
        if (!isEnabled() || pack == null || display == null || display.animations() == null) {
            return null;
        }
        ResourceId animations = display.animations();
        if (animations.equals(builtFor)) {
            return fixedPose;
        }
        synchronized (DevelopmentAnimationPose.class) {
            if (!animations.equals(builtFor)) {
                fixedPose = build(pack, animations);
                builtFor = animations;
            }
            return fixedPose;
        }
    }

    /** Forgets the built pose so the next render re-reads the animation; used when content reloads. */
    public static void reset() {
        synchronized (DevelopmentAnimationPose.class) {
            fixedPose = null;
            builtFor = null;
        }
    }

    private static WeaponPose build(ContentPack pack, ResourceId animations) {
        // Parsing and resource resolution are delegated to the shared animation cache, so the fixture follows
        // exactly the same load and invalidation path the rest of the animation pipeline will use.
        Optional<BedrockAnimation> animation = WeaponAnimationCache.instance().animation(pack, animations,
                ANIMATION_NAME);
        if (animation.isEmpty()) {
            Constants.LOG.warn("[ARG] Development animation pose: '{}' could not be loaded from '{}'; the weapon "
                    + "renders statically", ANIMATION_NAME, animations.asString());
            return null;
        }

        WeaponPose pose = WeaponPose.fromSampledAnimation(
                AnimationSampler.sample(animation.get(), SAMPLE_TIME_SECONDS));
        SampledBone bone = pose.bone(ANIMATED_BONE);
        Vec3 sampled = bone == null ? null : bone.channel(ANIMATED_CHANNEL);
        Constants.LOG.info("[ARG] Development animation pose: '{}' at {}s -> {} animated bone(s); {}.{} = {} "
                        + "(documented expectation {})", ANIMATION_NAME, SAMPLE_TIME_SECONDS, pose.boneCount(),
                ANIMATED_BONE, ANIMATED_CHANNEL.jsonName(), sampled, EXPECTED_VALUE);
        return pose;
    }
}
