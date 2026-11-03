package com.arg_studios.arg_weaponry.weapon.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackSource;
import com.arg_studios.arg_weaponry.pack.PackMetadata;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.WeaponType;
import com.arg_studios.arg_weaponry.weapon.model.Vec3;

/**
 * {@code reusable_default_animations}: resolution, merging, precedence and failure handling.
 *
 * <p>The tests use real files in a temporary content pack, so they exercise the same
 * {@code ContentPackResources} lookup, cache and parser that a pack uses at runtime.</p>
 */
class ReusableAnimationsTest {

    private static final String NAMESPACE = "testpack";
    private static final ResourceId PRIMARY_ID = new ResourceId(NAMESPACE, "test/weapon");
    private static final String WALK_LIBRARY = "default_animations/weapons/pistols/walking_animations";
    private static final String RUN_LIBRARY = "default_animations/weapons/pistols/running_animations";

    @TempDir
    Path root;

    @Test
    void oneLibraryIsResolvedAndItsClipsAreMergedWithTheWeapon() throws Exception {
        writePrimary();
        write(WALK_LIBRARY, """
                {"format_version": "1.8.0", "animations": {
                  "anim.model.walk_forward": {"loop": true, "animation_length": 0.8,
                    "bones": {"root": {"position": [0, 1, 0]}}},
                  "anim.shared.only": {"animation_length": 1.0,
                    "bones": {"root": {"rotation": [0, 10, 0]}}}
                }}
                """);
        ContentPack pack = pack();
        WeaponDefinition weapon = definition(List.of(WALK_LIBRARY));

        List<ResourceId> libraries = ReusableAnimations.resolveLibraries(pack, weapon, PRIMARY_ID);
        assertEquals(List.of(new ResourceId(NAMESPACE, "weapons/pistols/walking_animations")), libraries);

        AnimationFile merged = ReusableAnimations.resolve(pack, weapon, PRIMARY_ID).orElseThrow();
        assertNotNull(merged.animation("anim.test.idle"), "the weapon's own clip survives");
        assertNotNull(merged.animation("anim.model.walk_forward"), "the library clip is registered");
        assertNotNull(merged.animation("anim.shared.only"));
    }

    @Test
    void multipleLibrariesAreMergedInDeclarationOrder() throws Exception {
        writePrimary();
        write(WALK_LIBRARY, """
                {"format_version": "1.8.0", "animations": {
                  "anim.shared.only": {"animation_length": 1.0,
                    "bones": {"root": {"rotation": [0, 10, 0]}}},
                  "anim.walk.only": {"animation_length": 1.0, "bones": {"root": {"position": [0, 1, 0]}}}
                }}
                """);
        write(RUN_LIBRARY, """
                {"format_version": "1.8.0", "animations": {
                  "anim.shared.only": {"animation_length": 1.0,
                    "bones": {"root": {"rotation": [0, 99, 0]}}},
                  "anim.run.only": {"loop": true, "animation_length": 0.6,
                    "bones": {"root": {"position": [0, 2, 0]}}}
                }}
                """);
        ContentPack pack = pack();
        WeaponDefinition weapon = definition(List.of(WALK_LIBRARY, RUN_LIBRARY));

        assertEquals(2, ReusableAnimations.resolveLibraries(pack, weapon, PRIMARY_ID).size());
        AnimationFile merged = ReusableAnimations.resolve(pack, weapon, PRIMARY_ID).orElseThrow();
        assertNotNull(merged.animation("anim.walk.only"));
        assertNotNull(merged.animation("anim.run.only"));
        assertEquals(10.0F, ((AnimationChannel.Constant) merged.animation("anim.shared.only").bones()
                .get("root").rotation()).value().y(), 1.0E-6,
                "the first declared library wins a duplicate");
    }

    @Test
    void aWeaponSpecificClipWinsOverTheSameNameInALibrary() throws Exception {
        writePrimary();
        write(WALK_LIBRARY, """
                {"format_version": "1.8.0", "animations": {
                  "anim.test.idle": {"loop": true, "animation_length": 0.1,
                    "bones": {"root": {"position": [9, 9, 9]}}},
                  "anim.shared.only": {"animation_length": 1.0, "bones": {"root": {"rotation": [0, 10, 0]}}}
                }}
                """);
        ContentPack pack = pack();
        AnimationFile merged = ReusableAnimations.resolve(pack, definition(List.of(WALK_LIBRARY)), PRIMARY_ID)
                .orElseThrow();

        assertEquals(2.0, merged.animation("anim.test.idle").lengthSeconds(), 1.0E-9,
                "the weapon's own document is authoritative");
    }

    @Test
    void duplicateDeclarationsResolveTheLibraryOnce() throws Exception {
        writePrimary();
        write(WALK_LIBRARY, """
                {"format_version": "1.8.0", "animations": {
                  "anim.shared.only": {"animation_length": 1.0, "bones": {"root": {"rotation": [0, 10, 0]}}}
                }}
                """);
        ContentPack pack = pack();
        List<ResourceId> resolved = ReusableAnimations.resolveLibraries(pack,
                definition(List.of(WALK_LIBRARY, WALK_LIBRARY)), PRIMARY_ID);

        assertEquals(1, resolved.size());
    }

    @Test
    void aMissingLibraryIsSkippedWithoutLosingTheWeaponClips() throws Exception {
        writePrimary();
        ContentPack pack = pack();
        WeaponDefinition weapon = definition(List.of("default_animations/does/not/exist"));

        assertTrue(ReusableAnimations.resolveLibraries(pack, weapon, PRIMARY_ID).isEmpty());
        AnimationFile merged = ReusableAnimations.resolve(pack, weapon, PRIMARY_ID).orElseThrow();
        assertNotNull(merged.animation("anim.test.idle"));
    }

    @Test
    void anInvalidPathIsSkippedInsteadOfThrowing() throws Exception {
        writePrimary();
        ContentPack pack = pack();
        WeaponDefinition weapon = definition(List.of("../outside", "", " : ", "no/../escape"));

        assertTrue(ReusableAnimations.resolveLibraries(pack, weapon, PRIMARY_ID).isEmpty());
        assertNotNull(ReusableAnimations.resolve(pack, weapon, PRIMARY_ID).orElseThrow());
    }

    @Test
    void aNamespacedLibraryPathIsAccepted() throws Exception {
        writePrimary();
        write(WALK_LIBRARY, """
                {"format_version": "1.8.0", "animations": {
                  "anim.shared.only": {"animation_length": 1.0, "bones": {"root": {"rotation": [0, 10, 0]}}}
                }}
                """);
        ContentPack pack = pack();
        List<ResourceId> resolved = ReusableAnimations.resolveLibraries(pack,
                definition(List.of(NAMESPACE + ":" + WALK_LIBRARY + ".animation.json")), PRIMARY_ID);

        assertEquals(List.of(new ResourceId(NAMESPACE, "weapons/pistols/walking_animations")), resolved);
    }

    @Test
    void aLibraryCanStartAPlaybackWithoutAnyWeaponAnimationResource() throws Exception {
        write(WALK_LIBRARY, """
                {"format_version": "1.8.0", "animations": {
                  "anim.shared.only": {"loop": true, "animation_length": 1.0,
                    "bones": {"root": {"rotation": [0, 10, 0]}}}
                }}
                """);
        ContentPack pack = pack();
        AnimationFile merged = ReusableAnimations.resolve(pack, definition(List.of(WALK_LIBRARY)), null)
                .orElseThrow();
        WeaponAnimationController controller = new WeaponAnimationController();

        assertTrue(controller.play(merged, "anim.shared.only", null));
        assertTrue(controller.isPlaying());
        assertEquals("anim.shared.only", controller.animationName());
    }

    @Test
    void libraryDocumentsAreParsedOnceAndServedFromTheCache() throws Exception {
        write(WALK_LIBRARY, """
                {"format_version": "1.8.0", "animations": {
                  "anim.shared.only": {"animation_length": 1.0, "bones": {"root": {"rotation": [0, 10, 0]}}}
                }}
                """);
        ContentPack pack = new ContentPack("cache-test-pack", ContentPackSource.BUNDLED,
                new PackMetadata(null, null, null, NAMESPACE, null, null), root, List.of(NAMESPACE));
        ResourceId id = new ResourceId(NAMESPACE, "weapons/pistols/walking_animations");
        int hitsBefore = WeaponAnimationCache.instance().hitCount();
        int parsesBefore = WeaponAnimationCache.instance().parseCount();

        assertTrue(WeaponAnimationCache.instance().loadLibrary(pack, id).isPresent());
        int parsesAfterFirst = WeaponAnimationCache.instance().parseCount();
        assertTrue(WeaponAnimationCache.instance().loadLibrary(pack, id).isPresent());

        assertEquals(parsesBefore + 1, parsesAfterFirst, "the first lookup parses the library");
        assertEquals(parsesAfterFirst, WeaponAnimationCache.instance().parseCount(),
                "the second lookup must not parse it again");
        assertTrue(WeaponAnimationCache.instance().hitCount() > hitsBefore);
    }

    @Test
    void mergeReturnsThePrimaryDocumentWhenNoLibraryAddsAName() {
        AnimationFile primary = file("anim.test.idle", 2.0, 0.0F);
        assertNull(ReusableAnimations.merge(null, List.of()));
        assertEquals(primary, ReusableAnimations.merge(primary, List.of()));
        assertEquals(primary, ReusableAnimations.merge(primary, List.of(file("anim.test.idle", 1.0, 0.0F))));
    }

    // ---------------------------------------------------------------- fixtures

    private void writePrimary() throws Exception {
        write("animations/test/weapon", """
                {"format_version": "1.8.0", "animations": {
                  "anim.test.idle": {"loop": true, "animation_length": 2.0,
                    "bones": {"root": {"rotation": [0, 0, 0]}}}
                }}
                """);
    }

    private void write(String path, String json) throws Exception {
        Path file = root.resolve("assets").resolve(NAMESPACE).resolve(path + ".animation.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    private ContentPack pack() {
        return new ContentPack(NAMESPACE, ContentPackSource.BUNDLED,
                new PackMetadata(null, null, null, NAMESPACE, null, null), root, List.of(NAMESPACE));
    }

    private static AnimationFile file(String name, double length, float y) {
        BedrockAnimation animation = new BedrockAnimation(name, length, LoopMode.LOOP,
                java.util.Map.of("root", new BoneAnimation("root", null,
                        AnimationChannel.Constant.ofVector(new Vec3(0.0F, y, 0.0F)), null)));
        return new AnimationFile(PRIMARY_ID, "1.8.0", java.util.Map.of(name, animation));
    }

    private static WeaponDefinition definition(List<String> libraries) {
        return new WeaponDefinition(
                new ResourceId(NAMESPACE, "test/weapon"),
                new ResourceId(NAMESPACE, "test_weapon"),
                NAMESPACE + ":test_weapon",
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
                libraries,
                List.of(),
                null,
                java.util.Map.of(),
                java.util.Map.of(),
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
