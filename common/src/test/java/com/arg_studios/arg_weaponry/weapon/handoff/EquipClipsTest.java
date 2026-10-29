package com.arg_studios.arg_weaponry.weapon.handoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

/**
 * The clip resolution of the handoff subsystem, against a real (temporary) content pack.
 *
 * <p>The point of this test is the number that drives the transition: the window must be the <em>authored</em> length of the
 * clip, not the {@code duration} the weapon declares. The declared durations here are deliberately wrong, so a regression back
 * to "trust the declared duration" fails immediately.</p>
 */
class EquipClipsTest {

    private static final String NAMESPACE = "testpack";

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
                      "bones": { "root": { "rotation": [0, 0, 0] } }
                    },
                    "anim.test.holster": {
                      "animation_length": 0.74,
                      "bones": { "root": { "rotation": [0, -30, 0] } }
                    },
                    "anim.test.draw_first": {
                      "animation_length": 1.63,
                      "bones": { "root": { "rotation": [0, 20, 0] } }
                    },
                    "anim.test.draw": {
                      "animation_length": 0.87,
                      "bones": { "root": { "rotation": [0, 15, 0] } }
                    }
                  }
                }
                """);
        pack = new ContentPack(NAMESPACE, ContentPackSource.BUNDLED,
                new PackMetadata(null, null, null, NAMESPACE, null, null), root, List.of(NAMESPACE));
        definition = definition();
    }

    private void write(String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void theHolsterWindowIsTheAuthoredClipLengthNotTheDeclaredDuration() {
        Optional<EquipClips.Clip> clip = EquipClips.holster(pack, definition, 10);
        assertTrue(clip.isPresent(), "the declared holster clip exists in the animation document");
        assertEquals("anim.test.holster", clip.get().name());
        assertEquals(0.74, clip.get().seconds(), 1.0E-6, "the authored length wins over the declared 9.0 s");
        assertFalse(clip.get().firstDraw());
    }

    @Test
    void theFirstDrawClipIsOfferedOnlyWhileTheWeaponStillNeedsIt() {
        Optional<EquipClips.Clip> first = EquipClips.draw(pack, definition, 10, true);
        assertTrue(first.isPresent());
        assertEquals("anim.test.draw_first", first.get().name());
        assertEquals(1.63, first.get().seconds(), 1.0E-6);
        assertTrue(first.get().firstDraw(), "the caller must be able to consume the first-draw flag");

        Optional<EquipClips.Clip> later = EquipClips.draw(pack, definition, 10, false);
        assertTrue(later.isPresent());
        assertEquals("anim.test.draw", later.get().name());
        assertEquals(0.87, later.get().seconds(), 1.0E-6);
        assertFalse(later.get().firstDraw());
    }

    @Test
    void aDeclaredClipTheDocumentDoesNotContainIsTreatedAsAbsent() {
        assertEquals(0.0, EquipClips.authoredSeconds(pack, definition, "anim.test.missing"), 1.0E-9);
        assertEquals(0.0, EquipClips.authoredSeconds(pack, definition, ""), 1.0E-9);
    }

    private WeaponDefinition definition() {
        return new WeaponDefinition(
                new ResourceId(NAMESPACE, "test/weapon"),
                new ResourceId(NAMESPACE, "test_weapon"),
                "testpack:test_weapon",
                "test_weapon",
                WeaponType.GUN,
                new ResourceId(NAMESPACE, "test/weapon"),
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
                java.util.Map.of(),
                null,
                null,
                List.of(new WeaponDefinition.AnimationSelection("anim.test.idle", 2.0, null)),
                List.of(new WeaponDefinition.AnimationSelection("anim.test.holster", 9.0, null)),
                List.of(new WeaponDefinition.DrawSelection("anim.test.draw_first", 9.0, null, true, true),
                        new WeaponDefinition.DrawSelection("anim.test.draw", 9.0, "ammoCount > 0", false, false)),
                List.of(),
                List.of(),
                List.of(),
                null,
                null);
    }
}
