package com.arg_studios.arg_weaponry.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.arg_studios.arg_weaponry.client.render.WeaponDisplayCache;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackSource;
import com.arg_studios.arg_weaponry.pack.PackMetadata;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.WeaponType;
import com.arg_studios.arg_weaponry.weapon.animation.MovementLayer;
import com.arg_studios.arg_weaponry.weapon.animation.MovementState;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationCache;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;

/**
 * Locomotion as the renderer sees it: the movement contribution fades in and out of an action instead of being cut, it is
 * never counted twice, it survives a weapon switch, and the composed pose stays continuous at several partial ticks per tick.
 *
 * <p>Driven through a real (temporary) content pack, so what is asserted is the pose a caller renders, not a flag.</p>
 */
class WeaponViewControllerLocomotionTest {

    private static final String NAMESPACE = "testpack";
    private static final ResourceId DISPLAY = new ResourceId(NAMESPACE, "test/weapon");
    private static final WeaponViewController.Owner OWNER = new WeaponViewController.Owner(3, "testpack:test_weapon");
    private static final double TICK = 0.05;

    /** The highest root offset any clip in the test pack produces, so a doubled overlay would be visible immediately. */
    private static final double MAX_CLIP_OFFSET = 1.0;

    /** Frames sampled inside one tick, i.e. an 80 fps client. */
    private static final int FRAMES_PER_TICK = 4;

    @TempDir
    Path root;

    private ContentPack pack;
    private WeaponDefinition definition;

    @BeforeEach
    void setUp() throws Exception {
        WeaponDisplayCache.instance().clear();
        WeaponAnimationCache.instance().clear();
        write("assets/" + NAMESPACE + "/display/test/weapon.json", """
                {
                  "animations": "testpack:test/anim",
                  "model": "testpack:test/model",
                  "texture": "testpack:test/texture"
                }
                """);
        write("assets/" + NAMESPACE + "/animations/test/anim.animation.json", """
                {
                  "format_version": "1.8.0",
                  "animations": {
                    "anim.test.idle": {
                      "loop": true,
                      "animation_length": 2.0,
                      "bones": { "root": { "position": [0, 0, 0] } }
                    },
                    "anim.test.reload": {
                      "animation_length": 1.0,
                      "bones": { "root": { "position": [0, 0, 0] } }
                    },
                    "anim.model.walk_forward": {
                      "loop": true,
                      "animation_length": 0.8,
                      "bones": { "root": { "position": {
                        "0.0": [0, 0, 0],
                        "0.4": [0, 1, 0],
                        "0.8": [0, 0, 0]
                      } } }
                    },
                    "anim.model.run_start": {
                      "animation_length": 0.3333,
                      "bones": { "root": { "position": [0, 0.5, 0] } }
                    },
                    "anim.model.run": {
                      "loop": true,
                      "animation_length": 0.62,
                      "bones": { "root": { "position": {
                        "0.0": [0, 0.5, 0],
                        "0.31": [0, 1, 0],
                        "0.62": [0, 0.5, 0]
                      } } }
                    },
                    "anim.model.run_end": {
                      "animation_length": 0.42,
                      "bones": { "root": { "position": {
                        "0.0": [0, 0.5, 0],
                        "0.42": [0, 0, 0]
                      } } }
                    }
                  }
                }
                """);
        pack = new ContentPack(NAMESPACE, ContentPackSource.BUNDLED,
                new PackMetadata(null, null, null, NAMESPACE, null, null), root, List.of(NAMESPACE));
        definition = definition();
    }

    @Test
    void theMovementContributionFadesOutOfAnActionInsteadOfBeingCut() {
        WeaponViewController controller = activated();
        MovementState walking = new MovementState(1.0, 0.0, false);
        for (int tick = 0; tick < 20; tick++) {
            controller.tick(pack, definition, 10, TICK, walking);
        }
        double walkingPose = rootY(controller, 0.0F);
        assertTrue(walkingPose > 0.2, "the walk overlay owns the root while the player walks (" + walkingPose + ")");

        assertTrue(controller.playReload(pack, definition, "anim.test.reload", 1.0));
        // Four ticks: the fade is FADE_SECONDS long and is sampled from the value at the start of each tick, so it is fully
        // out one tick after the tick that began it.
        List<Double> frames = record(controller, 4, walking);

        double start = frames.get(0);
        double end = frames.get(frames.size() - 1);
        assertTrue(start > 0.2, "the walk overlay owned the root when the reload started (" + start + ")");
        assertEquals(0.0, end, 1.0E-6, "and the action owns it once the fade is over");
        // The walk pose itself keeps moving while it fades, so the value is not monotone - the *fade* is. Measure the drop
        // between consecutive frames instead, and require the retreat to be spread over several frames.
        double peak = start;
        for (double value : frames) {
            peak = Math.max(peak, value);
        }
        int framesToFadeOut = frames.size();
        for (int index = 1; index < frames.size(); index++) {
            double drop = frames.get(index - 1) - frames.get(index);
            assertTrue(drop <= peak * 0.35,
                    "no frame may carry more than a third of the fade; a cut would carry all of it in one frame ("
                            + frames + ")");
            if (framesToFadeOut == frames.size() && frames.get(index) <= peak * 0.05) {
                framesToFadeOut = index;
            }
        }
        assertTrue(framesToFadeOut >= 6,
                "the movement was faded out over " + framesToFadeOut + " frames, which is a cut rather than a fade ("
                        + frames + ")");

        controller.finishReload(pack, definition);
        for (int tick = 0; tick < 6; tick++) {
            controller.tick(pack, definition, 10, TICK, walking);
        }
        assertTrue(rootY(controller, 0.0F) > 0.0,
                "and the movement comes back afterwards instead of being forgotten");
    }

    @Test
    void aWeaponSwitchContinuesTheWalkInsteadOfRestartingIt() {
        WeaponViewController outgoing = activated();
        MovementState walking = new MovementState(1.0, 0.0, false);
        for (int tick = 0; tick < 20; tick++) {
            outgoing.tick(pack, definition, 10, TICK, walking);
        }

        // A switch builds a new controller, exactly like VisualWeapon does.
        MovementLayer.Timeline outgoingLocomotion = outgoing.locomotionTimeline();
        WeaponViewController incoming = activated();
        // Snapshot first, then carry: this is the order VisualWeapons.adopt uses, because disposing the outgoing weapon resets
        // its layer.
        incoming.carryLocomotionFrom(outgoingLocomotion);

        outgoing.tick(pack, definition, 10, TICK, walking);
        incoming.tick(pack, definition, 10, TICK, walking);

        double continued = rootY(incoming, 0.0F);
        assertEquals(rootY(outgoing, 0.0F), continued, 1.0E-6,
                "the incoming weapon continues the same walk phase at the same overlay weight");
        assertTrue(continued > 0.2, "the walk was not faded out and restarted (" + continued + ")");
        assertTrue(continued <= MAX_CLIP_OFFSET + 1.0E-6,
                "and it is applied once, not twice (" + continued + " > " + MAX_CLIP_OFFSET + ")");
    }

    @Test
    void aTimelineTakenBeforeTheDisposeSurvivesTheReset() {
        MovementState walking = new MovementState(1.0, 0.0, false);
        WeaponViewController outgoing = activated();
        WeaponViewController reference = activated();
        for (int tick = 0; tick < 20; tick++) {
            outgoing.tick(pack, definition, 10, TICK, walking);
            reference.tick(pack, definition, 10, TICK, walking);
        }

        // Exactly what VisualWeapons.adopt does: take the timeline, then dispose (which resets the layer).
        MovementLayer.Timeline snapshot = outgoing.locomotionTimeline();
        outgoing.reset();

        WeaponViewController incoming = activated();
        incoming.carryLocomotionFrom(snapshot);
        incoming.tick(pack, definition, 10, TICK, walking);
        reference.tick(pack, definition, 10, TICK, walking);

        assertEquals(rootY(reference, 0.0F), rootY(incoming, 0.0F), 1.0E-9,
                "the snapshot is a value, so the outgoing weapon's reset cannot empty it before it is adopted");
    }

    @Test
    void theComposedPoseStaysContinuousAtFourPartialTicksPerTick() {
        WeaponViewController controller = activated();
        List<Double> frames = new ArrayList<>();

        frames.addAll(record(controller, 20, new MovementState(1.0, 0.0, false)));
        frames.addAll(record(controller, 10, new MovementState(1.0, 0.0, true)));
        frames.addAll(record(controller, 6, new MovementState(0.0, 0.0, true, true)));
        frames.addAll(record(controller, 10, new MovementState(1.0, 0.0, true)));
        frames.addAll(record(controller, 12, new MovementState(1.0, 0.0, false)));

        // An action in the middle of a walk: the overlay fades out and back in.
        assertTrue(controller.playReload(pack, definition, "anim.test.reload", 1.0));
        frames.addAll(record(controller, 10, new MovementState(1.0, 0.0, false)));
        controller.finishReload(pack, definition);
        frames.addAll(record(controller, 10, new MovementState(1.0, 0.0, false)));

        for (double value : frames) {
            assertTrue(Double.isFinite(value), "every frame drew a pose");
            assertTrue(Math.abs(value) <= MAX_CLIP_OFFSET + 0.05,
                    "the composed root offset stays inside the range its clips produce, so the movement overlay cannot "
                            + "have been applied twice (" + value + ")");
        }

        double largestStep = 0.0;
        double largestCurvature = 0.0;
        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        for (int index = 1; index < frames.size(); index++) {
            double step = frames.get(index) - frames.get(index - 1);
            largestStep = Math.max(largestStep, Math.abs(step));
            lowest = Math.min(lowest, frames.get(index));
            highest = Math.max(highest, frames.get(index));
            if (index > 1) {
                double previous = frames.get(index - 1) - frames.get(index - 2);
                largestCurvature = Math.max(largestCurvature, Math.abs(step - previous));
            }
        }
        double range = highest - lowest;

        // The fastest legitimate motion is one clip range covered by the shortest fade (0.10 s = 8 frames), i.e. 0.125 per
        // frame. Anything close to a whole clip range in one frame is a cut.
        assertTrue(largestStep < 0.25,
                "no frame may jump by more than a fade can carry it; largest step was " + largestStep);
        assertTrue(largestCurvature < 0.2,
                "and no frame may change speed abruptly; largest acceleration was " + largestCurvature);
        assertTrue(range > 0.2, "the scripted sequence really moved the pose, so the bounds above are meaningful (range "
                + range + ")");
    }

    // ---------------------------------------------------------------- helpers

    /** Records one frame per {@code FRAMES_PER_TICK} of the given number of ticks. */
    private List<Double> record(WeaponViewController controller, int ticks, MovementState movement) {
        List<Double> frames = new ArrayList<>();
        for (int tick = 0; tick < ticks; tick++) {
            for (int frame = 0; frame < FRAMES_PER_TICK; frame++) {
                frames.add(rootY(controller, frame / (float) FRAMES_PER_TICK));
            }
            controller.tick(pack, definition, 10, TICK, movement);
        }
        return frames;
    }

    private double rootY(WeaponViewController controller, float partialTick) {
        WeaponPose pose = controller.poseFor(pack, definition, 10, partialTick);
        return pose == null ? Double.NaN : pose.bone("root").position().y();
    }

    private WeaponViewController activated() {
        WeaponViewController controller = new WeaponViewController();
        controller.activate(OWNER, pack, definition, 10);
        assertNotNull(controller.idleAnimation());
        return controller;
    }

    private void write(String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private WeaponDefinition definition() {
        return new WeaponDefinition(
                new ResourceId(NAMESPACE, "test/weapon"),
                new ResourceId(NAMESPACE, "test_weapon"),
                "testpack:test_weapon",
                "test_weapon",
                WeaponType.GUN,
                DISPLAY,
                List.of(),
                1.0,
                null,
                List.of(),
                1,
                false,
                WeaponDefinition.DEFAULT_TIME_BETWEEN_BURSTS,
                600,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                Map.of(),
                Map.of(),
                null,
                null,
                List.of(new WeaponDefinition.AnimationSelection("anim.test.idle", 2.0, null)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null);
    }
}
