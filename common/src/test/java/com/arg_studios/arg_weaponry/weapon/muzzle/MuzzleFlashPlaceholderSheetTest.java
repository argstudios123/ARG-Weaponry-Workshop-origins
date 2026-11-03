package com.arg_studios.arg_weaponry.weapon.muzzle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackResources;
import com.arg_studios.arg_weaponry.pack.ContentPackSource;
import com.arg_studios.arg_weaponry.pack.PackMetadata;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The shipped muzzle-flash authoring sheet, checked as data.
 *
 * <p>The placeholder is both an example and the reference layout creators are pointed at, so its geometry is pinned
 * here: eight equal cells, transparent padding inside every cell, and a grid that matches the frame rectangles
 * {@link MuzzleFlashSpriteSheet} derives. A sheet that drifted away from the documented layout would fail this test
 * instead of looking subtly wrong in game.</p>
 *
 * <p>This reads the PNG from the mod's own resources, which is also the check that the file is where a weapon definition
 * can load it from.</p>
 */
class MuzzleFlashPlaceholderSheetTest {

    private static final String SHEET = "/assets/arg_weaponry/templates/muzzle_flash/"
            + "muzzle_flash_placeholder.png";
    private static final int FRAMES = 8;
    private static final int CELL = 256;

    private static BufferedImage sheet;

    @BeforeAll
    static void loadSheet() throws Exception {
        try (InputStream stream = MuzzleFlashPlaceholderSheetTest.class.getResourceAsStream(SHEET)) {
            assertNotNull(stream, "the placeholder sheet must be packaged at " + SHEET);
            sheet = ImageIO.read(stream);
        }
        assertNotNull(sheet, "the placeholder sheet must be a readable PNG");
    }

    @Test
    void theSheetHasEightEqualCellsInOneRow() {
        assertEquals(CELL * FRAMES, sheet.getWidth(), "eight 256 px cells in one row");
        assertEquals(CELL, sheet.getHeight(), "one row of square cells");
        assertEquals(FRAMES, MuzzleFlashSpriteSheet.frameCount(sheet.getWidth(), sheet.getHeight()),
                "the derived strip rule and the documented grid must agree, so an undeclared pack also works");
    }

    @Test
    void theDeclaredGridMatchesTheFrameRectangles() {
        WeaponDefinition.MuzzleFlash.Sprites grid =
                new WeaponDefinition.MuzzleFlash.Sprites(1, FRAMES, 60, WeaponDefinition.MuzzleFlash.SpriteType.PLAY_ONCE);
        assertEquals(FRAMES, grid.frameCount());

        for (int frame = 0; frame < FRAMES; frame++) {
            MuzzleFlashSpriteSheet.Frame rectangle = MuzzleFlashSpriteSheet.frame(frame, grid.rows(), grid.columns());
            assertEquals(frame, rectangle.index());
            assertEquals((float) frame / FRAMES, rectangle.u0(), 1.0E-6F, "frame " + frame + " starts at its own cell");
            assertEquals((float) (frame + 1) / FRAMES, rectangle.u1(), 1.0E-6F);
            assertEquals(0.0F, rectangle.v0(), 1.0E-6F, "a single row spans the whole height");
            assertEquals(1.0F, rectangle.v1(), 1.0E-6F);
        }
    }

    @Test
    void everyFrameKeepsItsTransparentPadding() {
        int padding = 16;
        for (int frame = 0; frame < FRAMES; frame++) {
            int x0 = frame * CELL;
            for (int x = x0; x < x0 + CELL; x++) {
                for (int y = 0; y < CELL; y++) {
                    boolean inBorder = x < x0 + padding || x >= x0 + CELL - padding
                            || y < padding || y >= CELL - padding;
                    if (inBorder) {
                        assertEquals(0, alpha(x, y), "frame " + frame + " must not paint inside its padding at "
                                + (x - x0) + "," + y);
                    }
                }
            }
        }
    }

    @Test
    void everyFrameIsDrawnAndTheFramesDiffer() {
        for (int frame = 0; frame < FRAMES; frame++) {
            int opaque = 0;
            int maxAlpha = 0;
            for (int x = frame * CELL; x < (frame + 1) * CELL; x++) {
                for (int y = 0; y < CELL; y++) {
                    int a = alpha(x, y);
                    maxAlpha = Math.max(maxAlpha, a);
                    if (a > 8) {
                        opaque++;
                    }
                }
            }
            assertTrue(opaque > 0, "frame " + frame + " must contain a flash");
            // The ignition and the peak frames need a solid, white-hot core; the dissipating tail is allowed to
            // fade, but every frame has to stay clearly visible.
            int requiredPeak = frame <= 3 ? 240 : 80;
            assertTrue(maxAlpha >= requiredPeak, "frame " + frame + " is too faint (peak alpha " + maxAlpha + ")");
        }

        // Consecutive frames must be different stages: a duplicated image would play as a stutter.
        for (int frame = 1; frame < FRAMES; frame++) {
            long difference = 0L;
            for (int x = 0; x < CELL; x++) {
                for (int y = 0; y < CELL; y++) {
                    difference += Math.abs(alpha(frame * CELL + x, y) - alpha((frame - 1) * CELL + x, y));
                }
            }
            double meanDifference = (double) difference / (CELL * CELL);
            // A duplicated frame scores exactly 0 here, which is what this guards against; the first and last
            // frames of the animation cover a small area and are expected to differ only slightly.
            assertTrue(meanDifference > 1.0,
                    "frames " + (frame - 1) + " and " + frame + " are too similar (mean alpha difference "
                            + String.format("%.2f", meanDifference) + ")");
        }
    }

    private static int alpha(int x, int y) {
        return (sheet.getRGB(x, y) >>> 24) & 0xFF;
    }

    /**
     * Proves the placeholder can actually be reached by a weapon definition.
     *
     * <p>A definition's {@code muzzle_flash.texture} is resolved <em>inside its own content pack</em>, by
     * {@link com.arg_studios.arg_weaponry.pack.ContentPackResources#findTexture}, not against the mod's own assets. This
     * therefore asks the real resolver, for the real shipped pack, whether the documented identifier resolves - which is
     * the difference between "the file exists somewhere" and "the renderer can load it".</p>
     */
    @Test
    void theFlashSheetResolvesThroughTheContentPackLookup() throws Exception {
        PackMetadata metadata = new PackMetadata("ARG Default Pack", "1.0.0", "ARG Studios",
                "arg_default_pack", null, null);
        ContentPack pack = new ContentPack("arg_default_pack", ContentPackSource.BUNDLED, metadata,
                packRoot(), List.of("arg_pack"));

        // The sheet the shipped shotgun actually uses, asked for the way a definition writes it.
        ResourceId id = new ResourceId("arg_pack", "weapons/special/muzzle_flash/universal_flash");
        assertTrue(ContentPackResources.findTexture(pack, id, "textures").isPresent(),
                "the flash sheet must resolve through the pack lookup a weapon definition goes through");

        ResourceId withSuffix = new ResourceId("arg_pack", "weapons/special/muzzle_flash/universal_flash.png");
        assertTrue(ContentPackResources.findTexture(pack, withSuffix, "textures").isPresent(),
                "the extension may be written out, exactly like every other texture reference");
    }

    /** @return the bundled content pack inside the module's resources, which is what ships in the jar. */
    private static Path packRoot() {
        try {
            URL url = MuzzleFlashPlaceholderSheetTest.class.getClassLoader()
                    .getResource("assets/arg_weaponry/weapon_packs/arg_default_pack");
            if (url != null && "file".equals(url.getProtocol())) {
                return Path.of(url.toURI());
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the bundled content pack path is not a file URL", e);
        }
        Path fallback = Path.of("src/main/resources/assets/arg_weaponry/weapon_packs/arg_default_pack");
        assertTrue(Files.isDirectory(fallback), "the bundled content pack must be reachable from the test");
        return fallback;
    }
}
