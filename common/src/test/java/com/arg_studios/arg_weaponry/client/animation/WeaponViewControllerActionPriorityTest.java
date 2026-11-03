package com.arg_studios.arg_weaponry.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.arg_studios.arg_weaponry.weapon.animation.MovementState;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponAnimationCache;
import com.arg_studios.arg_weaponry.weapon.animation.WeaponPose;

/**
 * Action priority: the movement and aiming overlays must never modify a reload or an inspection pose.
 *
 * <p>The regression this pins is a report that a walking weapon still bobbed while it was reloading or being inspected. The
 * controller is driven through a real (temporary) content pack here, so the test checks the pose a caller renders instead of
 * the flags the gate reads: two controllers play the same action clip, one is fed a walking player and one a standing one,
 * and both must produce the same pose on every tick.</p>
 */
class WeaponViewControllerActionPriorityTest {

    private static final String NAMESPACE = "testpack";
    private static final ResourceId DISPLAY = new ResourceId(NAMESPACE, "test/weapon");
    private static final WeaponViewController.Owner OWNER = new WeaponViewController.Owner(3, "testpack:test_weapon");
    private static final double TICK = 0.05;

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
                      "bones": { "root": { "position": [0, 1, 0] } }
                    },
                    "anim.test.inspect": {
                      "animation_length": 1.0,
                      "bones": { "root": { "position": [0, 2, 0] } }
                    },
                    "anim.test.aim_in": {
                      "animation_length": 0.3,
                      "bones": { "root": { "position": [0, 4, 0] } }
                    },
                    "anim.model.walk_forward": {
                      "loop": true,
                      "animation_length": 0.8,
                      "bones": { "root": { "position": [0, 5, 0] } }
                    }
                  }
                }
                """);
        pack = new ContentPack(NAMESPACE, ContentPackSource.BUNDLED,
                new PackMetadata(null, null, null, NAMESPACE, null, null), root, List.of(NAMESPACE));
        definition = definition();
    }

    @Test
    void aReloadIsNotModifiedByWalking() {
        WeaponViewController walking = activated();
        WeaponViewController standing = activated();
        assertTrue(walking.playReload(pack, definition, "anim.test.reload", 2.0));
        assertTrue(standing.playReload(pack, definition, "anim.test.reload", 2.0));

        for (int tick = 0; tick < 5; tick++) {
            walking.tick(pack, definition, 10, TICK, new MovementState(1.0, 0.0, false));
            standing.tick(pack, definition, 10, TICK, MovementState.STILL);
        }

        assertEquals(1.0, rootY(walking), 1.0E-6, "the reload clip owns the pose");
        assertEquals(rootY(standing), rootY(walking), 1.0E-6,
                "a walking player and a standing player see exactly the same reload pose");
    }

    @Test
    void anInspectionIsNotModifiedByWalkingOrAiming() {
        WeaponViewController walking = activated();
        WeaponViewController standing = activated();
        assertTrue(walking.playInspect(pack, definition, "anim.test.inspect", 2.0));
        assertTrue(standing.playInspect(pack, definition, "anim.test.inspect", 2.0));

        for (int tick = 0; tick < 5; tick++) {
            walking.tick(pack, definition, 10, TICK, new MovementState(1.0, 0.0, false), true);
            standing.tick(pack, definition, 10, TICK, MovementState.STILL, true);
        }

        assertEquals(2.0, rootY(walking), 1.0E-6, "the inspection clip owns the pose");
        assertEquals(rootY(standing), rootY(walking), 1.0E-6,
                "neither the walk cycle nor the aiming layer may touch an inspection");
    }

    @Test
    void theAimingOverlayReturnsAfterTheActionEnds() {
        WeaponViewController controller = activated();
        assertTrue(controller.playInspect(pack, definition, "anim.test.inspect", 2.0));
        controller.tick(pack, definition, 10, TICK, MovementState.STILL, true);
        assertEquals(2.0, rootY(controller), 1.0E-6, "the inspection still owns the pose");

        controller.finishInspect(pack, definition);
        for (int tick = 0; tick < 6; tick++) {
            controller.tick(pack, definition, 10, TICK, MovementState.STILL, true);
        }

        assertEquals(4.0, rootY(controller), 1.0E-4,
                "once the inspection ends, the aim overlay is applied again - it was hidden, not forgotten");
    }

    // ---------------------------------------------------------------- helpers

    private double rootY(WeaponViewController controller) {
        WeaponPose pose = controller.poseFor(pack, definition, 10, 0.0F);
        return pose == null ? Double.NaN : pose.bone("root").position().y();
    }

    private WeaponViewController activated() {
        WeaponViewController controller = new WeaponViewController();
        controller.activate(OWNER, pack, definition, 10);
        assertEquals("anim.test.idle", controller.idleAnimation());
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
                        "anim.test.aim_in", null),
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
