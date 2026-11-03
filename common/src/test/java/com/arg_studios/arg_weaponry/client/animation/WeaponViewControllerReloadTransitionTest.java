package com.arg_studios.arg_weaponry.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationCache;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;

/**
 * The cross-fade between two consecutive reload clips.
 *
 * <p>Reload stages are separate clips, so the pose at the end of one stage is not the pose at the start of the next. The
 * regression these tests pin is the reported snap at a stage boundary: the fade used to be started from a <em>re-sampled</em>
 * pose of whatever clip was playing at that moment, and because the reload path resumes the idle clip before the next stage
 * starts, that sample was the idle pose - the weapon jumped away from where it was and then eased back.</p>
 *
 * <p>Each clip here animates one constant root offset, so the number a frame reads is exactly the pose that was drawn:
 * {@code 5.0} is the opening clip, {@code 9.0} the second one and {@code 0.0} the idle clip.</p>
 */
class WeaponViewControllerReloadTransitionTest {

    private static final String NAMESPACE = "testpack";
    private static final ResourceId DISPLAY = new ResourceId(NAMESPACE, "test/weapon");
    private static final WeaponViewController.Owner OWNER = new WeaponViewController.Owner(3, "testpack:test_weapon");
    private static final double TICK = 0.05;
    private static final double OPENING_ROOT_Y = 5.0;
    private static final double SECOND_ROOT_Y = 9.0;

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
                    "anim.test.stage_one": {
                      "animation_length": 0.5,
                      "bones": { "root": { "position": [0, 5, 0] } }
                    },
                    "anim.test.stage_two": {
                      "animation_length": 0.5,
                      "bones": { "root": { "position": [0, 9, 0] } }
                    }
                  }
                }
                """);
        pack = new ContentPack(NAMESPACE, ContentPackSource.BUNDLED,
                new PackMetadata(null, null, null, NAMESPACE, null, null), root, List.of(NAMESPACE));
        definition = definition();
    }

    @Test
    void aStageBoundaryBlendsFromThePoseThatWasOnScreen() {
        WeaponViewController controller = activated();
        controller.tick(pack, definition, 10, TICK);
        assertTrue(controller.playReload(pack, definition, "anim.test.stage_one", 0.5, 0.15));

        // One frame per tick, exactly like the renderer. The clip is retimed to exactly its stage length, so it has
        // reached its final pose after ten ticks - and from there the reload holds that pose instead of returning to idle.
        double lastFrameOfStageOne = rootY(controller);
        for (int tick = 0; tick < 10; tick++) {
            controller.tick(pack, definition, 10, TICK);
            lastFrameOfStageOne = rootY(controller);
        }
        assertEquals(OPENING_ROOT_Y, lastFrameOfStageOne, 1.0E-4, "the opening clip reached its own final pose");

        // ClientReloadSequence starts the next stage in the same tick the previous one ended.
        assertTrue(controller.playReload(pack, definition, "anim.test.stage_two", 0.5, 0.15));
        assertEquals(OPENING_ROOT_Y, rootY(controller), 1.0E-4,
                "the fade starts from the pose that was on screen, not from the idle pose at 0.0");

        double previous = OPENING_ROOT_Y;
        for (int tick = 0; tick < 3; tick++) {
            controller.tick(pack, definition, 10, TICK);
            double frame = rootY(controller);
            assertTrue(frame >= previous - 1.0E-4,
                    "the blend must walk towards the incoming clip, never back towards the idle pose: " + previous
                            + " -> " + frame);
            assertTrue(frame <= SECOND_ROOT_Y + 1.0E-4, "the blend must not overshoot the incoming clip");
            previous = frame;
        }
        assertEquals(SECOND_ROOT_Y, rootY(controller), 1.0E-3,
                "after the 0.15 s transition the incoming clip owns the pose");
        assertTrue(controller.isReloadPlaying(), "blending never ends the reload clip it introduces");
    }

    @Test
    void aZeroTransitionDurationReplacesThePoseImmediately() {
        WeaponViewController controller = activated();
        controller.tick(pack, definition, 10, TICK);
        assertTrue(controller.playReload(pack, definition, "anim.test.stage_one", 0.5, 0.15));
        controller.tick(pack, definition, 10, TICK);
        controller.tick(pack, definition, 10, TICK);
        assertEquals(OPENING_ROOT_Y, rootY(controller), 1.0E-3, "the opening clip owns the pose");

        assertTrue(controller.playReload(pack, definition, "anim.test.stage_two", 0.5, 0.0));
        assertEquals(SECOND_ROOT_Y, rootY(controller), 1.0E-4,
                "a transition_duration of 0 switches the pose without blending");
    }

    @Test
    void theTransitionOverlapsTheClipAndChangesNoTiming() {
        // The fade is a render-side blend: it is not part of the clip's retiming, so the playback rate - which is what
        // makes the clip last exactly the server-side stage length - cannot depend on it.
        WeaponViewController withoutBlend = activated();
        withoutBlend.tick(pack, definition, 10, TICK);
        withoutBlend.playReload(pack, definition, "anim.test.stage_one", 0.5, 0.0);

        WeaponViewController blended = activated();
        blended.tick(pack, definition, 10, TICK);
        blended.playReload(pack, definition, "anim.test.stage_one", 0.5, 0.15);

        assertEquals(withoutBlend.reloadPlaybackRate(), blended.reloadPlaybackRate(), 1.0E-9,
                "the cross-fade must not change the clip's playback rate, and therefore not its duration");
        assertEquals(WeaponViewController.playbackRateFor(0.5, 0.5), blended.reloadPlaybackRate(), 1.0E-9);
    }

    @Test
    void aClipThatEndsBeforeTheNextStageHoldsItsLastFrameInsteadOfReturningToIdle() {
        // The staged schedule and the clip clock are two different clocks, and a stage length that is not a whole number
        // of ticks is rounded up for the schedule but not for the retiming, so a stage clip really can reach its end
        // before the next stage is due. The reload must hold the pose it is already in: handing playback back to idle
        // here is the step that made the weapon jump away from the reload and back (measured at 43 degrees of root
        // rotation on the shipped SPAS-12 rig).
        WeaponViewController controller = activated();
        controller.tick(pack, definition, 10, TICK);
        assertTrue(controller.playReload(pack, definition, "anim.test.stage_one", 0.4, 0.15));

        for (int tick = 0; tick < 12; tick++) {
            controller.tick(pack, definition, 10, TICK);
        }

        assertTrue(controller.isReloadPlaying(),
                "a finished stage clip must not end the reload: the reload ends when the reload ends");
        assertEquals(OPENING_ROOT_Y, rootY(controller), 1.0E-4,
                "the terminal frame keeps being drawn, so nothing moves in the gap");

        // The next stage then cross-fades from that held pose, exactly like any other stage boundary.
        assertTrue(controller.playReload(pack, definition, "anim.test.stage_two", 0.5, 0.15));
        assertEquals(OPENING_ROOT_Y, rootY(controller), 1.0E-4);

        // Ending the reload (completion or cancellation) is what returns playback to idle, through the same fade.
        controller.finishReload(pack, definition);
        assertFalse(controller.isReloadPlaying());
        for (int tick = 0; tick < 8; tick++) {
            controller.tick(pack, definition, 10, TICK);
        }
        assertEquals(0.0, rootY(controller), 1.0E-3, "the reload ends in the idle pose, never in a stuck reload pose");
    }

    // ---------------------------------------------------------------- helpers

    private double rootY(WeaponViewController controller) {
        WeaponPose pose = controller.poseFor(pack, definition, 10, 0.0F);
        return pose == null ? Double.NaN : pose.bone("root").position().y();
    }

    private WeaponViewController activated() {
        WeaponViewController controller = new WeaponViewController();
        controller.activate(OWNER, pack, definition, 10);
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
                new WeaponDefinition.Aiming(true, false, false, 0.7, "linear", "smooth", 1.0, 1.0,
                        null, null),
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
