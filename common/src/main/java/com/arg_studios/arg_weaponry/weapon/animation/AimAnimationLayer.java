package com.arg_studios.arg_weaponry.weapon.animation;

import java.util.Map;

/**
 * The aiming animation layer: the optional authored {@code aim_in} and {@code aim_out} clips of one weapon.
 *
 * <h2>Why a layer</h2>
 * <p>Aiming is not a clip the weapon plays instead of its idle clip - the weapon keeps idling while the player aims, and an
 * authored aim animation is an <em>additional</em> pose that describes how the weapon is raised. This class therefore
 * produces an overlay that {@link WeaponPoseLayers#overlay} merges into the base pose, exactly like {@link MovementLayer}
 * does. The controller applies it <em>after</em> the movement overlay, so an aiming animation wins on every channel it
 * animates while movement still drives the rest.</p>
 *
 * <pre>
 *   idle --aim--&gt; IN (aim_in plays once) --clip end--&gt; HOLD (last frame held)
 *        --aim released--&gt; OUT (aim_out plays once) --clip end--&gt; idle (fade out)
 * </pre>
 *
 * <p>Either clip may be missing; with neither, the layer is inactive and aiming is expressed purely by the placement and
 * zoom transition. See {@link #update(boolean, boolean, double)} for the fade and priority rules.</p>
 */
public final class AimAnimationLayer {

    /** How long the layer takes to fade in, in seconds. */
    public static final double ENTER_SECONDS = 0.10;

    /** How long the layer takes to fade back out, in seconds. */
    public static final double EXIT_SECONDS = 0.12;

    /** How long a cross-fade between {@code aim_in} and {@code aim_out} takes, in seconds. */
    public static final double CLIP_CROSSFADE_SECONDS = 0.08;

    /** How long the layer takes to fade out for, and back in after, a higher-priority action, in seconds. */
    public static final double OVERLAY_SECONDS = 0.08;

    /** Longest delta one update may apply, so a stall cannot jump a clip. */
    private static final double MAX_DELTA_SECONDS = 0.1;

    /** The aiming layer's playback state. */
    public enum Phase {
        /** No aiming clip is contributing. */
        NONE,
        /** {@code aim_in} is playing. */
        IN,
        /** {@code aim_in} reached its end and its last frame is held. */
        HOLD,
        /** {@code aim_out} is playing. */
        OUT
    }

    private BedrockAnimation aimIn;
    private BedrockAnimation aimOut;

    private Phase phase = Phase.NONE;
    private double clipTime;
    private double weight;
    private double overlayWeight = 1.0;

    /** The last pose the layer produced, kept while the layer fades out so it can blend back from it. */
    private WeaponPose heldPose;

    private WeaponPose crossfadeFrom;
    private double crossfadeElapsed;
    private double crossfadeDuration;

    /**
     * Resolves the aiming clips of one animation resource.
     *
     * <p>An exact animation name is used first; a short name such as {@code aim_in} also matches a clip called
     * {@code anim.model.aim_in}, the same convenience the movement layer offers. A blank or missing name means "the weapon
     * does not author that clip", which is a valid configuration, not an error.</p>
     *
     * @param file   the weapon's animation document, may be {@code null}
     * @param aimIn  the configured {@code aiming.aim_in}, may be {@code null}
     * @param aimOut the configured {@code aiming.aim_out}, may be {@code null}
     * @return whether at least one aiming clip was resolved
     */
    public boolean bind(AnimationFile file, String aimIn, String aimOut) {
        Map<String, BedrockAnimation> animations = file == null ? null : file.animations();
        this.aimIn = asOneShot(resolve(animations, aimIn));
        this.aimOut = asOneShot(resolve(animations, aimOut));
        resetTimeline();
        return this.aimIn != null || this.aimOut != null;
    }

    /**
     * Treats an aiming clip as a one-shot, whatever the file declared.
     *
     * <p>{@code aim_in} is a transition that ends in a pose to hold, and {@code aim_out} is a transition back, so looping
     * one would either flicker or restart itself. The parsed animation is immutable and shared, so the one-shot variant is
     * a new value that reuses the same bones; the document itself is never rewritten.</p>
     *
     * @param clip the resolved clip, may be {@code null}
     * @return the clip with {@link LoopMode#ONCE} semantics, or {@code null}
     */
    private static BedrockAnimation asOneShot(BedrockAnimation clip) {
        if (clip == null || clip.loopMode() == LoopMode.ONCE) {
            return clip;
        }
        return new BedrockAnimation(clip.name(), clip.lengthSeconds(), LoopMode.ONCE, clip.bones());
    }

    /**
     * @param animations the document's clips, may be {@code null}
     * @param name       the configured animation name, may be {@code null}
     * @return the resolved clip, or {@code null} when the name is blank or nothing matches
     */
    private static BedrockAnimation resolve(Map<String, BedrockAnimation> animations, String name) {
        if (animations == null || name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        BedrockAnimation exact = animations.get(trimmed);
        if (exact != null) {
            return exact;
        }
        BedrockAnimation best = null;
        String bestName = null;
        for (Map.Entry<String, BedrockAnimation> entry : animations.entrySet()) {
            String candidate = entry.getKey();
            if (candidate == null || !candidate.endsWith("." + trimmed)) {
                continue;
            }
            if (bestName == null || candidate.length() < bestName.length()) {
                best = entry.getValue();
                bestName = candidate;
            }
        }
        return best;
    }

    /** @return whether the bound resource declares at least one aiming clip. */
    public boolean isEmpty() {
        return aimIn == null && aimOut == null;
    }

    /** @return the current phase, for diagnostics and tests. */
    public Phase phase() {
        return phase;
    }

    /** @return the authored {@code aim_in} clip, or {@code null}. */
    public BedrockAnimation aimIn() {
        return aimIn;
    }

    /** @return the authored {@code aim_out} clip, or {@code null}. */
    public BedrockAnimation aimOut() {
        return aimOut;
    }

    /** Forgets every timeline, e.g. when the weapon or its animation resource changes. */
    public void reset() {
        aimIn = null;
        aimOut = null;
        resetTimeline();
    }

    private void resetTimeline() {
        phase = Phase.NONE;
        clipTime = 0.0;
        weight = 0.0;
        overlayWeight = 1.0;
        heldPose = null;
        crossfadeFrom = null;
        crossfadeElapsed = 0.0;
        crossfadeDuration = 0.0;
    }

    /**
     * Advances the aiming layer by one client tick.
     *
     * <h4>Fades</h4>
     * <p>The layer fades in over {@value #ENTER_SECONDS} seconds when aiming begins and out over {@value #EXIT_SECONDS}
     * seconds after {@code aim_out} ends (or immediately after {@code aim_in} when the pack authors no {@code aim_out}).
     * Every clip change cross-fades from the pose that was on screen over {@value #CLIP_CROSSFADE_SECONDS} seconds. All
     * three use {@link AnimationBlending#moveTowards} / {@link AnimationBlending#smoothstep}, so they are frame-rate
     * independent and start and end with zero velocity.</p>
     *
     * <h4>Priority</h4>
     * <p>{@code overlayAllowed = false} is passed while a firing, reloading, inspecting or equip clip owns the pose. The
     * layer is then removed from the pose on that very tick - an action is never modified by aiming - while it keeps its
     * phase and its clip clock, and it fades back in over {@value #OVERLAY_SECONDS} seconds when the action ends. A held aim
     * therefore never replays {@code aim_in} because a shot was fired.</p>
     *
     * @param aiming         whether the player is holding aim right now
     * @param overlayAllowed whether the layer may contribute right now
     * @param deltaSeconds   the time the tick covered
     */
    public void update(boolean aiming, boolean overlayAllowed, double deltaSeconds) {
        if (isEmpty()) {
            return;
        }
        double delta = Double.isFinite(deltaSeconds)
                ? Math.max(0.0, Math.min(MAX_DELTA_SECONDS, deltaSeconds)) : 0.0;

        if (aiming) {
            if (phase == Phase.NONE || phase == Phase.OUT) {
                if (aimIn != null) {
                    enter(Phase.IN);
                }
            }
        } else if (phase == Phase.IN || phase == Phase.HOLD) {
            if (aimOut != null) {
                enter(Phase.OUT);
            } else {
                // No aim_out clip: the held aim pose simply fades back into the base pose.
                phase = Phase.NONE;
                clipTime = 0.0;
            }
        }

        switch (phase) {
            case IN -> {
                clipTime += delta;
                if (aimIn != null && clipTime >= aimIn.lengthSeconds()) {
                    phase = Phase.HOLD;
                    clipTime = aimIn.lengthSeconds();
                }
            }
            case OUT -> {
                clipTime += delta;
                if (aimOut != null && clipTime >= aimOut.lengthSeconds()) {
                    phase = Phase.NONE;
                    clipTime = 0.0;
                }
            }
            case HOLD -> {
                // The last frame of aim_in stays on screen; nothing is restarted.
            }
            default -> {
                // Nothing contributing.
            }
        }

        if (phase != Phase.NONE) {
            // Remember what the layer is showing, so a later fade-out can start from it even after the clip ended.
            heldPose = sampleCurrent(0.0);
        }

        double weightTarget = switch (phase) {
            case IN, HOLD, OUT -> 1.0;
            case NONE -> 0.0;
        };
        double weightDuration = weightTarget > weight ? ENTER_SECONDS : EXIT_SECONDS;
        weight = AnimationBlending.moveTowards(weight, weightTarget, delta, weightDuration);

        double overlayTarget = overlayAllowed ? 1.0 : 0.0;
        if (!overlayAllowed) {
            // A higher-priority action owns the pose from this tick on: the overlay is removed immediately instead of fading
            // out over the action's first frames. The action clip itself cross-fades in, so nothing snaps, and a reload or an
            // inspection can never be modified by the aiming animation.
            overlayWeight = 0.0;
        } else {
            overlayWeight = AnimationBlending.moveTowards(overlayWeight, overlayTarget, delta, OVERLAY_SECONDS);
        }

        advanceCrossfade(delta);
    }

    /**
     * Starts one phase from its first frame, fading from what is currently on screen.
     *
     * @param next the phase to enter
     */
    private void enter(Phase next) {
        WeaponPose from = pose(0.0);
        phase = next;
        clipTime = 0.0;
        if (from == null || from.isEmpty()) {
            crossfadeFrom = null;
            crossfadeElapsed = 0.0;
            crossfadeDuration = 0.0;
        } else {
            crossfadeFrom = from;
            crossfadeElapsed = 0.0;
            crossfadeDuration = CLIP_CROSSFADE_SECONDS;
        }
    }

    /** Advances the clip cross-fade and clears it once it finished. */
    private void advanceCrossfade(double delta) {
        if (crossfadeFrom == null) {
            return;
        }
        crossfadeElapsed += delta;
        if (crossfadeDuration <= 0.0 || crossfadeElapsed >= crossfadeDuration) {
            crossfadeFrom = null;
            crossfadeElapsed = 0.0;
            crossfadeDuration = 0.0;
        }
    }

    /**
     * @return the layer's current contribution, in {@code [0, 1]}: its own fade multiplied by the overlay gate that a
     *         higher-priority action lowers.
     */
    public float weight() {
        return (float) AnimationBlending.clamp01(AnimationBlending.smoothstep(weight) * overlayWeight);
    }

    /**
     * Samples the layer for one frame.
     *
     * @param offsetSeconds the frame's sub-tick offset, added to the clip time
     * @return the overlay pose, or {@code null} when the layer contributes nothing
     */
    public WeaponPose pose(double offsetSeconds) {
        if (isEmpty() || weight <= 0.0) {
            return null;
        }
        double offset = Double.isFinite(offsetSeconds) ? offsetSeconds : 0.0;
        WeaponPose sampled = sampleCurrent(offset);
        if (sampled == null) {
            return null;
        }
        if (crossfadeFrom == null || crossfadeDuration <= 0.0) {
            return sampled;
        }
        double elapsed = Math.max(0.0, crossfadeElapsed + offset);
        float blend = (float) AnimationBlending.smoothstep(elapsed / crossfadeDuration);
        return WeaponPoseBlend.blend(crossfadeFrom, sampled, blend);
    }

    /**
     * @param offsetSeconds the sub-tick offset
     * @return the pose of the current phase, or the frozen last pose while the layer fades out
     */
    private WeaponPose sampleCurrent(double offsetSeconds) {
        return switch (phase) {
            case IN -> sampleAt(aimIn, clipTime + offsetSeconds);
            case HOLD -> sampleAt(aimIn, aimIn == null ? 0.0 : aimIn.lengthSeconds());
            case OUT -> sampleAt(aimOut, clipTime + offsetSeconds);
            case NONE -> heldPose;
        };
    }

    /**
     * @param clip          the clip to sample, may be {@code null}
     * @param seconds       the time to sample at
     * @return the pose without its scale channels, or {@code null} when the clip is missing or animates nothing
     */
    private static WeaponPose sampleAt(BedrockAnimation clip, double seconds) {
        if (clip == null) {
            return null;
        }
        return stripScale(WeaponPose.fromSampledAnimation(AnimationSampler.sample(clip, seconds)));
    }

    /**
     * Drops every scale channel of an aiming pose; an aiming clip may move or turn the weapon, never resize it.
     *
     * @param pose the sampled pose
     * @return the same pose without scale channels, or {@code null} when nothing but scale was animated
     */
    private static WeaponPose stripScale(WeaponPose pose) {
        if (pose == null || pose.isEmpty()) {
            return null;
        }
        Map<String, SampledBone> bones = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, SampledBone> entry : pose.bones().entrySet()) {
            SampledBone bone = entry.getValue();
            if (bone.rotation() == null && bone.position() == null) {
                continue;
            }
            bones.put(entry.getKey(), new SampledBone(entry.getKey(), bone.rotation(), bone.position(), null));
        }
        if (bones.isEmpty()) {
            return null;
        }
        return new WeaponPose(pose.animationName(), pose.timeSeconds(), bones, pose.issues());
    }
}
