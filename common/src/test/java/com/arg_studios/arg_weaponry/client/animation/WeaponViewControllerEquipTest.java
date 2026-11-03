package com.arg_studios.arg_weaponry.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
import com.arg_studios.arg_weaponry.weapon.animation.SampledBone;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationCache;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPoseBlend;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * The playback rules an equip clip depends on, driven through the real controller and a real (temporary) content pack.
 *
 * <p>These are the mechanics the "weapon disappears during the transition" report broke: a draw clip is only useful if it
 * keeps running while the frame sampler and the ammunition refresh look at it, if a completion that belongs to another clip
 * cannot end it, and if a spurious re-bind of the controller is recognised as the destructive event it is. Nothing here needs
 * a running game, so the fragile part of the feature is covered by tests instead of only by play-testing.</p>
 */
class WeaponViewControllerEquipTest {

    private static final String NAMESPACE = "testpack";

    private static final ResourceId DISPLAY = new ResourceId(NAMESPACE, "test/weapon");

    private static final WeaponViewController.Owner OWNER = new WeaponViewController.Owner(3, "testpack:test_weapon");

    private static final WeaponViewController.Owner OTHER_OWNER =
            new WeaponViewController.Owner(4, "testpack:test_weapon");

    @TempDir
    Path root;

    private ContentPack pack;

    private WeaponDefinition definition;

    @BeforeEach
    void setUp() throws Exception {
        // The caches are process-wide singletons and remember misses, so each test starts from a clean view of the pack.
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
                      "bones": { "root": { "rotation": [0, 0, 0] } }
                    },
                    "anim.test.draw": {
                      "animation_length": 1.0,
                      "bones": {
                        "root": {
                          "rotation": { "0.0": [0, 0, 0], "0.5": [0, 45, 0], "1.0": [0, 90, 0] }
                        }
                      }
                    },
                    "anim.test.holster": {
                      "animation_length": 0.74,
                      "bones": { "root": { "rotation": [0, -90, 0] } }
                    }
                  }
                }
                """);
        pack = new ContentPack(NAMESPACE, ContentPackSource.BUNDLED,
                new PackMetadata(null, null, null, NAMESPACE, null, null), root, List.of(NAMESPACE));
        definition = definition();
    }

    /**
     * A hand-built definition, so this test needs neither the JSON parser nor the classes it pulls in. Only the members
     * the controller reads are populated: the display resource, the idle selection and the two clip lists.
     */
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
                java.util.Map.of(),
                java.util.Map.of(),
                null,
                null,
                List.of(new WeaponDefinition.AnimationSelection("anim.test.idle", 2.0, null)),
                List.of(new WeaponDefinition.AnimationSelection("anim.test.holster", 0.74, null)),
                List.of(new WeaponDefinition.DrawSelection("anim.test.draw", 1.0, "ammoCount > 0", false, false)),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null);
    }

    private void write(String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private WeaponViewController activated() {
        WeaponViewController controller = new WeaponViewController();
        controller.activate(OWNER, pack, definition, 10);
        assertEquals("anim.test.idle", controller.idleAnimation(),
                "an equipped weapon starts on its idle animation before any transition");
        return controller;
    }

    // ---------------------------------------------------------------- the draw clip survives the frame

    @Test
    void theDrawClipSurvivesFrameSamplingAndTheIdleRefresh() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0), "the draw clip starts");

        for (int frame = 0; frame < 20; frame++) {
            controller.poseFor(pack, definition, 10, 0.5F);
            controller.tick(pack, definition, 10, 0.02);
        }

        assertTrue(controller.isEquipPlaying(),
                "sampling a frame and advancing the tick must not end a running draw clip");
        assertEquals("anim.test.draw", controller.equipAnimation(),
                "the clip that is playing is still the draw clip, not the idle one");
    }

    @Test
    void anAmmunitionChangeDuringTheDrawDoesNotRestartIt() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));
        controller.tick(pack, definition, 10, 0.05);
        double timeBefore = controller.elapsedSeconds();

        // A magazine change re-evaluates the idle selection; it must not touch the equip clip that owns playback.
        controller.poseFor(pack, definition, 0, 0.0F);
        controller.tick(pack, definition, 0, 0.05);

        assertTrue(controller.isEquipPlaying());
        assertEquals("anim.test.draw", controller.equipAnimation());
        assertTrue(controller.elapsedSeconds() > timeBefore, "the clip kept advancing instead of restarting");
    }

    // ---------------------------------------------------------------- only the owning completion ends it

    @Test
    void aCompletionForAnotherClipCannotEndTheDraw() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));

        assertFalse(controller.finishEquip(pack, definition, "anim.test.holster"),
                "a completion that belongs to another clip must be ignored");
        assertTrue(controller.isEquipPlaying(), "and the draw clip must still be playing");

        assertTrue(controller.finishEquip(pack, definition, "anim.test.draw"),
                "the clip that is actually playing is the one this completion may end");
        assertFalse(controller.isEquipPlaying());
        assertEquals("anim.test.idle", controller.idleAnimation(), "playback returns to the idle selection");
    }

    @Test
    void aClipTheAnimationResourceDoesNotContainCannotLeaveTheWeaponLocked() {
        WeaponViewController controller = activated();

        assertFalse(controller.playEquip(pack, definition, "anim.test.missing", 1.0),
                "a clip the animation document does not contain cannot start");
        assertFalse(controller.isEquipPlaying(), "and it must not leave an equip clip behind");

        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0),
                "the weapon is still usable afterwards");
    }

    // ---------------------------------------------------------------- why the owner must be stable

    @Test
    void rebindingToAnotherOwnerDropsTheRunningClip() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));

        // This is the destructive event the render fix removes from the frame path: a re-activation - which is what a
        // spurious owner change causes - resets playback, and the draw clip is lost.
        controller.activate(OTHER_OWNER, pack, definition, 10);

        assertFalse(controller.isEquipPlaying(), "a re-activation always drops the equip clip");
        assertEquals("anim.test.idle", controller.idleAnimation());
    }

    // ---------------------------------------------------------------- the cross-fade at a clip change

    @Test
    void idleIntoHolsterCrossFadesInsteadOfCutting() {
        WeaponViewController controller = activated();
        WeaponPose idle = tickAndSample(controller, 0.05);
        assertEquals(0.0, yaw(idle), 0.001, "the test idle clip holds the weapon at zero yaw");

        assertTrue(controller.playEquip(pack, definition, "anim.test.holster", 0.74));
        WeaponPose first = sample(controller);
        assertEquals(0.0, degreesApart(idle, first), 0.5,
                "the frame after the switch still shows the idle pose, not the holster clip's first frame");

        double largestStep = largestStepOver(controller, 6);
        assertEquals(-90.0, yaw(sample(controller)), 0.5, "the cross-fade ends on the holster pose");
        assertTrue(largestStep < 30.0,
                "90 degrees spread over the fade must not appear as one step, largest was " + largestStep);
    }

    @Test
    void anEarlyDrawInterruptionFadesFromTheDisplayedDrawPose() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));

        Interruption interruption = interruptDrawAfter(controller, 0.2);
        double displayed = yaw(interruption.displayedBeforeSwitch());
        assertTrue(Math.abs(displayed) > 10.0,
                "the test needs an interrupted draw pose that is clearly not the idle pose, was " + displayed);

        double largestStep = assertFadeStartsFromTheDisplayedPose(controller, interruption, -90.0);
        assertTrue(largestStep < 40.0,
                "the draw-to-holster switch must be spread over the fade, largest was " + largestStep);
    }

    @Test
    void aLateDrawInterruptionFadesFromTheDisplayedDrawPose() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));

        Interruption interruption = interruptDrawAfter(controller, 0.8);
        double displayed = yaw(interruption.displayedBeforeSwitch());
        assertTrue(displayed > 60.0,
                "a late interruption shows a very different draw pose than an early one, was " + displayed);

        double largestStep = assertFadeStartsFromTheDisplayedPose(controller, interruption, -90.0);
        assertTrue(largestStep < 60.0,
                "162 degrees must still be spread over the fade instead of jumping, largest was " + largestStep);
    }

    @Test
    void aSecondInterruptionDuringTheFadeDoesNotSnapBack() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));
        interruptDrawAfter(controller, 0.4);

        // Let the fade run part of the way, so the pose on screen is between the draw pose and the holster pose.
        tickAndSample(controller, 0.02);
        tickAndSample(controller, 0.02);
        WeaponPose midFade = tickAndSample(controller, 0.02);
        assertTrue(yaw(midFade) < -1.0, "the fade is running and the weapon is already moving to the holster");
        assertTrue(yaw(midFade) > -89.0, "and it has not arrived yet");

        // A third clip change must continue from the blended pose, not from the snapshot taken at the first change.
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));
        WeaponPose afterSecondSwitch = sample(controller);
        assertEquals(0.0, degreesApart(midFade, afterSecondSwitch), 0.5,
                "the second switch continues from the pose that was on screen");
        assertEquals(yaw(midFade), yaw(afterSecondSwitch), 0.5, "and not from the earlier draw pose");

        for (int tick = 0; tick < 5; tick++) {
            tickAndSample(controller, 0.02);
        }
        // 0.1 s into the restarted draw the root has turned about 9 degrees: the fade went to the new clip.
        assertEquals(9.0, yaw(sample(controller)), 1.5,
                "the restarted fade ends on the clip that replaced the fading one");
    }

    // ---------------------------------------------------------------- the terminal pose and completion

    @Test
    void theTerminalEquipPoseIsHeldUntilTheAuthoritativeCompletion() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));

        runTicks(controller, 1.2);
        assertTrue(controller.isEquipPlaying(),
                "the clip's last frame is held instead of releasing playback to idle");
        assertEquals("anim.test.draw", controller.equipAnimation());
        assertEquals(90.0, yaw(sample(controller)), 0.5,
                "the held pose is the clip's terminal frame, not the idle pose");

        runTicks(controller, 0.2);
        assertEquals(90.0, yaw(sample(controller)), 0.5,
                "more ticks of the authoritative window must not make the weapon flash at the idle pose");
    }

    @Test
    void aStaleCompletionCannotEndTheHeldTerminalPose() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));
        runTicks(controller, 1.2);

        assertFalse(controller.finishEquip(pack, definition, "anim.test.holster"),
                "a completion that belongs to another clip is still ignored while the last frame is held");
        assertTrue(controller.isEquipPlaying());
        assertEquals("anim.test.draw", controller.equipAnimation());
        assertEquals(90.0, yaw(sample(controller)), 0.5);
    }

    @Test
    void idleResumesAfterTheOwningCompletion() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));
        runTicks(controller, 1.2);

        assertTrue(controller.finishEquip(pack, definition, "anim.test.draw"));
        assertFalse(controller.isEquipPlaying());
        assertEquals("anim.test.idle", controller.idleAnimation());
        assertEquals(90.0, yaw(sample(controller)), 0.5,
                "the fade back to idle starts from the pose that was on screen");

        runTicks(controller, 0.2);
        assertEquals(0.0, yaw(sample(controller)), 0.5, "and ends on the idle clip once the fade is over");
    }

    @Test
    void aCompletionThatNeverArrivesCannotLockTheWeapon() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));
        runTicks(controller, 1.2);
        assertTrue(controller.isEquipPlaying(), "the last frame is held while the window is still open");

        // The safety net: a caller that never reports completion must not leave the weapon on a finished clip.
        runTicks(controller, 2.4);
        assertFalse(controller.isEquipPlaying(), "the finished clip is released instead of locking the weapon");
        assertEquals("anim.test.idle", controller.idleAnimation());
        runTicks(controller, 0.2);
        assertEquals(0.0, yaw(sample(controller)), 0.5, "and playback is back on the idle clip");
    }

    @Test
    void resetClearsTheCrossFadeSoItCannotLeakIntoTheNextWeapon() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));
        runTicks(controller, 0.6);
        assertTrue(controller.finishEquip(pack, definition, "anim.test.draw"),
                "this starts a fade from about 54 degrees back to idle");
        assertEquals(54.0, yaw(sample(controller)), 1.0);

        controller.reset();
        assertNull(controller.pose(), "a reset controller holds no pose at all");

        controller.activate(OTHER_OWNER, pack, definition, 10);
        assertEquals(0.0, yaw(sample(controller)), 0.001,
                "the new owner starts on its own idle pose, with no leftover fade from the previous weapon");
        runTicks(controller, 0.2);
        assertEquals(0.0, yaw(sample(controller)), 0.001);
    }

    @Test
    void aMissingClipLeavesNeitherTheWeaponLockedNorACrossFade() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));
        runTicks(controller, 0.4);
        double before = yaw(sample(controller));

        assertFalse(controller.playEquip(pack, definition, "anim.test.missing", 1.0),
                "a clip the animation document does not contain cannot start");
        assertTrue(controller.isEquipPlaying(), "the draw that was playing is untouched");
        assertEquals("anim.test.draw", controller.equipAnimation());
        assertEquals(before, yaw(sample(controller)), 0.5,
                "and nothing was replaced, so no fade was started either");
    }

    @Test
    void aHandoffFadesInFromTheOutgoingWeaponsLastDisplayedPose() {
        WeaponViewController controller = activated();
        runTicks(controller, 0.2);

        WeaponPose beforeHandoff = handOffToOtherOwner(controller);
        assertEquals(-90.0, yaw(beforeHandoff), 0.5, "the outgoing weapon is drawn at its holster pose");

        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));
        WeaponPose first = sample(controller);
        assertEquals(0.0, degreesApart(beforeHandoff, first), 0.5,
                "the first frame after the switch is the pose the outgoing weapon was drawn with");
        assertEquals(-90.0, yaw(first), 0.5,
                "and not the incoming weapon's idle pose, which was started in the same tick and never rendered");

        double largest = largestStepOver(controller, 5);
        assertEquals(9.0, yaw(sample(controller)), 1.5, "the fade ends on the incoming draw clip");
        assertTrue(largest < 40.0,
                "the 99 degree handover is spread over the fade, largest step was " + largest);
    }

    @Test
    void aFreshActivationInventsNoCarriedPose() {
        WeaponViewController controller = new WeaponViewController();
        controller.activate(OWNER, pack, definition, 10);

        assertTrue(controller.playEquip(pack, definition, "anim.test.holster", 0.74));
        assertEquals(-90.0, yaw(sample(controller)), 0.5,
                "with nothing on screen the clip starts at its own first frame instead of fading from an invented pose");
    }

    @Test
    void aCarriedPoseIsUsedOnceAndDroppedOnceAFrameIsDrawn() {
        WeaponViewController controller = activated();
        runTicks(controller, 0.2);
        handOffToOtherOwner(controller);

        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));
        assertEquals(-90.0, yaw(sample(controller)), 0.5, "the first frame comes from the carried pose");

        // A frame of the incoming weapon has been drawn and the draw has advanced, so the carry is spent.
        runTicks(controller, 0.2);
        double drawn = yaw(sample(controller));
        assertEquals(18.0, drawn, 1.0, "the draw is 0.2 s into its own clip");

        assertTrue(controller.playEquip(pack, definition, "anim.test.holster", 0.74));
        assertEquals(drawn, yaw(sample(controller)), 1.0,
                "the next clip fades from the draw pose, not from the pose carried by the earlier weapon");
    }

    @Test
    void aCarriedPoseCannotSurviveAReset() {
        WeaponViewController controller = activated();
        assertTrue(controller.playEquip(pack, definition, "anim.test.draw", 1.0));
        runTicks(controller, 0.4);
        assertEquals(36.0, yaw(sample(controller)), 1.0, "a draw pose that was genuinely displayed");

        controller.activate(OTHER_OWNER, pack, definition, 10);
        controller.reset();
        assertNull(controller.pose(), "a reset controller holds nothing");

        controller.activate(OWNER, pack, definition, 10);
        assertTrue(controller.playEquip(pack, definition, "anim.test.holster", 0.74));
        assertEquals(-90.0, yaw(sample(controller)), 0.5,
                "the holster starts at its own first frame; no pose from the earlier weapon may be reused");
    }

    @Test
    void anArmedDrawConsumesNoClipTimeUntilTheFirstRenderedFrame() {
        WeaponViewController controller = activated();

        assertTrue(controller.armEquip(pack, definition, "anim.test.draw", 1.0, null),
                "the draw clip is armed on the fresh animator");
        assertTrue(controller.isEquipAwaitingFirstFrame(), "and it waits, paused, for the frame that draws it");
        assertEquals(0.0, yaw(sample(controller)), 0.001, "the armed clip shows its own first frame");

        // Two ticks pass without a first-person frame drawing the weapon (third person, a screen, or simply the tick that
        // lands between the slot change and the next frame).
        runTicks(controller, 0.1);
        assertTrue(controller.isEquipAwaitingFirstFrame(), "no frame was drawn, so the clip is still armed");
        assertEquals(0.0, yaw(sample(controller)), 0.001,
                "and no part of the raise was consumed while the weapon could not be seen");

        assertTrue(controller.releaseEquipPlayback(), "the first drawn frame starts the clip");
        assertFalse(controller.releaseEquipPlayback(), "releasing twice cannot restart it");

        runTicks(controller, 0.2);
        assertEquals(18.0, yaw(sample(controller)), 1.5,
                "0.2 s of the 1 s, 90 degree raise have now really played");
    }

    @Test
    void anArmedDrawHoldsItsCrossFadeUntilItIsDrawn() {
        WeaponViewController controller = activated();
        runTicks(controller, 0.2);
        WeaponPose outgoing = handOffToOtherOwner(controller);
        assertEquals(-90.0, yaw(outgoing), 0.5, "the outgoing weapon is drawn at its holster pose");

        assertTrue(controller.armEquip(pack, definition, "anim.test.draw", 1.0, null));
        assertEquals(-90.0, yaw(sample(controller)), 0.5,
                "the armed draw fades in from the pose the outgoing weapon ended on");

        runTicks(controller, 0.3);
        assertEquals(-90.0, yaw(sample(controller)), 0.5,
                "the fade waits with the clip: consuming it while the weapon is invisible would make the first drawn "
                        + "frame start mid-fade, which is the snap this test exists to prevent");

        assertTrue(controller.releaseEquipPlayback());
        assertEquals(-90.0, yaw(sample(controller)), 0.5,
                "so the first frame the player really sees is still the outgoing pose");
        double largest = largestStepOver(controller, 6);
        assertEquals(10.8, yaw(sample(controller)), 0.5, "and the raise then eases into the draw clip");
        assertTrue(largest < 40.0, "the handover is spread over the fade, largest step was " + largest);
    }

    @Test
    void armingAClipTheResourceDoesNotContainLeavesTheControllerUntouched() {
        WeaponViewController controller = activated();
        assertFalse(controller.armEquip(pack, definition, "anim.test.missing", 1.0, null));
        assertFalse(controller.isEquipAwaitingFirstFrame(), "nothing was armed");
        assertEquals("anim.test.idle", controller.idleAnimation(), "so idle playback is still the owner");
    }

    // ---------------------------------------------------------------- pose helpers

    /**
     * Reproduces the real handoff: the holster is rendered to its end, then the equip subsystem ends its own clip, the slot
     * changes and the same controller is reused for the incoming weapon - all inside one tick, before any frame.
     *
     * @return the pose that was on screen on the last frame before the switch
     */
    private WeaponPose handOffToOtherOwner(WeaponViewController controller) {
        assertTrue(controller.playEquip(pack, definition, "anim.test.holster", 0.74));
        WeaponPose beforeHandoff = null;
        for (int tick = 0; tick < 45; tick++) {
            beforeHandoff = tickAndSample(controller, 0.02);
        }
        assertTrue(controller.finishEquip(pack, definition, "anim.test.holster"),
                "the equip subsystem ends its own clip before the slot changes");
        controller.activate(OTHER_OWNER, pack, definition, 10);
        return beforeHandoff;
    }

    /** The pose the frame after a switch shows, together with the pose that was on screen before it. */
    private record Interruption(WeaponPose displayedBeforeSwitch, WeaponPose firstFrameAfterSwitch) {
    }

    private WeaponPose sample(WeaponViewController controller) {
        return controller.poseFor(pack, definition, 10, 0.0F);
    }

    /** One client tick at twenty ticks per second, followed by the frame that tick produces. */
    private WeaponPose tickAndSample(WeaponViewController controller, double deltaSeconds) {
        controller.tick(pack, definition, 10, deltaSeconds);
        return sample(controller);
    }

    private void runTicks(WeaponViewController controller, double seconds) {
        int ticks = (int) Math.round(seconds / 0.02);
        for (int tick = 0; tick < ticks; tick++) {
            tickAndSample(controller, 0.02);
        }
    }

    /** Advances a few ticks and returns the largest single-tick pose change among them. */
    private double largestStepOver(WeaponViewController controller, int ticks) {
        WeaponPose previous = sample(controller);
        double largest = 0.0;
        for (int tick = 0; tick < ticks; tick++) {
            WeaponPose next = tickAndSample(controller, 0.02);
            largest = Math.max(largest, degreesApart(previous, next));
            previous = next;
        }
        return largest;
    }

    /** Ends a running draw and starts the holster, exactly as the equip subsystem does on an interruption. */
    private Interruption interruptDrawAfter(WeaponViewController controller, double seconds) {
        runTicks(controller, seconds);
        WeaponPose displayed = sample(controller);
        assertTrue(controller.finishEquip(pack, definition, "anim.test.draw"), "the interrupted draw ends");
        assertTrue(controller.playEquip(pack, definition, "anim.test.holster", 0.74));
        return new Interruption(displayed, sample(controller));
    }

    /**
     * Asserts that the first frame after a switch is the pose that was on screen, then follows the fade to its end.
     *
     * @return the largest single-tick pose change while the fade ran
     */
    private double assertFadeStartsFromTheDisplayedPose(WeaponViewController controller, Interruption interruption,
                                                        double expectedYaw) {
        assertEquals(0.0, degreesApart(interruption.displayedBeforeSwitch(), interruption.firstFrameAfterSwitch()), 0.5,
                "the first frame after the switch is the pose that was on screen, not the new clip's first frame");
        double largest = largestStepOver(controller, 6);
        assertEquals(expectedYaw, yaw(sample(controller)), 0.5, "the cross-fade ends on the new clip's pose");
        return largest;
    }

    /** @return the yaw of the animated root bone, in degrees. */
    private static float yaw(WeaponPose pose) {
        return rootRotation(pose).y();
    }

    private static Vec3 rootRotation(WeaponPose pose) {
        assertNotNull(pose, "a playing clip must produce a pose");
        SampledBone root = pose.bone("root");
        assertNotNull(root, "every test clip animates 'root'");
        assertNotNull(root.rotation(), "and it animates that bone's rotation");
        return root.rotation();
    }

    /** Angular difference, so a wrapped Euler component cannot fake a large step. */
    private static double degreesApart(WeaponPose first, WeaponPose second) {
        return WeaponPoseBlend.angleDegrees(rootRotation(first), rootRotation(second));
    }
}
