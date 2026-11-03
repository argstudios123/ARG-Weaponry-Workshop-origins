package com.arg_studios.arg_weaponry.client.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackSource;
import com.arg_studios.arg_weaponry.pack.PackMetadata;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.WeaponType;

/**
 * The display document's per-context transforms, read from a real content pack.
 *
 * <h2>The regression this pins</h2>
 * <p>The shipped weapon display documents declare their transforms under <strong>{@code display_views}</strong>, but the weapon
 * loader only read {@code display}: every authored third-person, ground, shelf and GUI transform was silently ignored and reported
 * as an "uninterpreted key". Reading both spellings - with the older one winning when a document has both - is what makes the
 * authored numbers reach the renderer at all.</p>
 */
class WeaponDisplayTransformsTest {

    private static final String NAMESPACE = "testpack";
    private static final ResourceId DISPLAY = new ResourceId(NAMESPACE, "test/weapon");

    @TempDir
    Path root;

    private ContentPack pack;

    @BeforeEach
    void setUp() {
        pack = new ContentPack(NAMESPACE, ContentPackSource.BUNDLED,
                new PackMetadata(null, null, null, NAMESPACE, null, null), root, List.of(NAMESPACE));
    }

    @Test
    void theDisplayViewsKeyIsRead() {
        WeaponDisplay display = load("""
                {
                  "animations": "testpack:test/anim",
                  "model": "testpack:test/model",
                  "texture": "testpack:test/texture",
                  "display_views": {
                    "thirdperson_righthand": { "translation": [0, -2.75, -2.5], "scale": [0.3, 0.3, 0.3] },
                    "thirdperson_lefthand":  { "translation": [0, -2.75, -2.5], "scale": [0.3, 0.3, 0.3] },
                    "ground": { "scale": [0.3, 0.3, 0.3] },
                    "fixed":  { "rotation": [0, 88, 0], "translation": [0, -3.25, -2], "scale": [0.5, 0.5, 0.5] },
                    "head":   { "scale": [0, 0, 0] }
                  }
                }
                """);

        DisplayTransform right = display.transform("thirdperson_righthand");
        assertEquals(0.3F, right.scale().x(), 1.0E-6F, "the authored scale reaches the renderer");
        assertEquals(-2.75F, right.translation().y(), 1.0E-6F, "and so does the authored translation");
        assertEquals(0.3F, display.transform("thirdperson_lefthand").scale().x(), 1.0E-6F,
                "the left hand is read as its own entry");
        assertEquals(0.3F, display.transform("ground").scale().x(), 1.0E-6F);
        assertEquals(88.0F, display.transform("fixed").rotation().y(), 1.0E-6F);
        assertTrue(StaticWeaponPlacement.isHidden(display, "head"), "a zero scale is read as 'do not show it here'");
    }

    @Test
    void theLeftHandIsNotBorrowedFromTheRightHand() {
        WeaponDisplay display = load("""
                {
                  "model": "testpack:test/model",
                  "texture": "testpack:test/texture",
                  "display_views": {
                    "thirdperson_righthand": { "translation": [0, -2.75, -2.5], "scale": [0.3, 0.3, 0.3] }
                  }
                }
                """);

        assertEquals(0.3F, display.transform("thirdperson_righthand").scale().x(), 1.0E-6F);
        assertTrue(display.transform("thirdperson_lefthand").isIdentity(),
                "a left hand the pack does not describe keeps the identity, so it is never mirrored by accident");
    }

    @Test
    void aDocumentWithoutTransformsUsesTheIdentityEverywhere() {
        WeaponDisplay display = load("""
                {
                  "model": "testpack:test/model",
                  "texture": "testpack:test/texture"
                }
                """);

        assertTrue(display.transform("thirdperson_righthand").isIdentity());
        assertTrue(display.transform("ground").isIdentity());
        assertTrue(display.transforms().isEmpty());
    }

    @Test
    void theOlderDisplayKeyStillWins() {
        WeaponDisplay display = load("""
                {
                  "model": "testpack:test/model",
                  "texture": "testpack:test/texture",
                  "display": { "ground": { "scale": [0.4, 0.4, 0.4] } },
                  "display_views": { "ground": { "scale": [0.9, 0.9, 0.9] } }
                }
                """);

        assertEquals(0.4F, display.transform("ground").scale().x(), 1.0E-6F,
                "'display' is the older spelling and keeps priority when a document declares both");
    }

    @Test
    void aMalformedValueIsReportedWithoutLosingTheOthers() {
        ValidationReport report = new ValidationReport();
        WeaponDisplay display = load("""
                {
                  "model": "testpack:test/model",
                  "texture": "testpack:test/texture",
                  "display_views": {
                    "ground": { "scale": [0.3, 0.3, 0.3] },
                    "thirdperson_righthand": { "translation": "nonsense" }
                  }
                }
                """, report);

        assertEquals(0.3F, display.transform("ground").scale().x(), 1.0E-6F, "a valid entry is still read");
        assertTrue(display.transform("thirdperson_righthand").translation().isZero(),
                "the malformed value is treated as zero instead of dropping the whole document");
        assertFalse(report.diagnostics().isEmpty(), "and the problem is reported");
    }

    // ---------------------------------------------------------------- helpers

    private WeaponDisplay load(String document) {
        return load(document, new ValidationReport());
    }

    private WeaponDisplay load(String document, ValidationReport report) {
        try {
            Path file = root.resolve("assets/" + NAMESPACE + "/display/test/weapon.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, document, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not write the test display document", e);
        }
        return WeaponDisplayLoader.load(pack, definition(), report).orElseThrow();
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
                java.util.Map.of(),
                java.util.Map.of(),
                null,
                null,
                List.of(),
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
