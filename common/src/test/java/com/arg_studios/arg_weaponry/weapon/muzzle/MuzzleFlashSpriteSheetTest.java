package com.arg_studios.arg_weaponry.weapon.muzzle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

import org.junit.jupiter.api.Test;

/**
 * Sprite-sheet layout and animation timing of the muzzle flash.
 *
 * <p>The shipped {@code universal_flash.png} is 2172x724, so the layout rule has to turn those numbers into three square
 * frames without any duration in the JSON. These tests pin the rule, the UV rectangles, the frame clock and the point at
 * which the animation stops - the three things a renderer needs in order to draw the right frame without guessing.</p>
 */
class MuzzleFlashSpriteSheetTest {

    @Test
    void theShippedSheetIsThreeSquareFramesInOneRow() {
        assertEquals(3, MuzzleFlashSpriteSheet.frameCount(2172, 724),
                "2172 / 724 = 3: the frames are square and laid out horizontally");
    }

    @Test
    void aSquareTextureIsOneFrame() {
        assertEquals(1, MuzzleFlashSpriteSheet.frameCount(512, 512));
        assertEquals(1, MuzzleFlashSpriteSheet.frameCount(724, 724));
    }

    @Test
    void dimensionsThatDoNotDivideEvenlyAreDrawnAsOneFrame() {
        assertEquals(1, MuzzleFlashSpriteSheet.frameCount(650, 100),
                "a sheet with padding must not be cut into guessed frames");
        assertEquals(1, MuzzleFlashSpriteSheet.frameCount(100, 200),
                "a vertical strip is not a horizontal sprite sheet");
        assertEquals(1, MuzzleFlashSpriteSheet.frameCount(0, 0));
        assertEquals(1, MuzzleFlashSpriteSheet.frameCount(-4, 16));
    }

    @Test
    void everyFrameSamplesItsOwnSliceOfTheTexture() {
        int frames = MuzzleFlashSpriteSheet.frameCount(2172, 724);

        MuzzleFlashSpriteSheet.Frame first = MuzzleFlashSpriteSheet.frame(0, frames);
        MuzzleFlashSpriteSheet.Frame second = MuzzleFlashSpriteSheet.frame(1, frames);
        MuzzleFlashSpriteSheet.Frame third = MuzzleFlashSpriteSheet.frame(2, frames);

        assertEquals(0.0F, first.u0(), 1.0E-6F);
        assertEquals(1.0F / 3.0F, first.u1(), 1.0E-6F);
        assertEquals(1.0F / 3.0F, second.u0(), 1.0E-6F);
        assertEquals(2.0F / 3.0F, second.u1(), 1.0E-6F);
        assertEquals(2.0F / 3.0F, third.u0(), 1.0E-6F);
        assertEquals(1.0F, third.u1(), 1.0E-6F);
        for (MuzzleFlashSpriteSheet.Frame frame : new MuzzleFlashSpriteSheet.Frame[] {first, second, third}) {
            assertEquals(0.0F, frame.v0(), 1.0E-6F, "the frames span the full texture height");
            assertEquals(1.0F, frame.v1(), 1.0E-6F);
        }
    }

    @Test
    void theAnimationPlaysTheFramesInSequenceAndThenStops() {
        int frames = 3;
        double frame = MuzzleFlashSpriteSheet.FRAME_SECONDS;

        assertEquals(0, MuzzleFlashSpriteSheet.frameIndexAt(0.0D, frames), "a shot starts on the first frame");
        assertEquals(0, MuzzleFlashSpriteSheet.frameIndexAt(frame * 0.9D, frames));
        assertEquals(1, MuzzleFlashSpriteSheet.frameIndexAt(frame, frames));
        assertEquals(2, MuzzleFlashSpriteSheet.frameIndexAt(frame * 2.5D, frames));
        assertEquals(-1, MuzzleFlashSpriteSheet.frameIndexAt(frame * frames, frames),
                "after the last frame the animation is over");
        assertEquals(-1, MuzzleFlashSpriteSheet.frameIndexAt(10.0D, frames));
        assertNull(MuzzleFlashSpriteSheet.frameAt(frame * frames, frames), "a finished animation draws nothing");
        assertNotNull(MuzzleFlashSpriteSheet.frameAt(0.0D, frames));
    }

    @Test
    void aSingleFrameSheetStillEnds() {
        assertEquals(0, MuzzleFlashSpriteSheet.frameIndexAt(0.0D, 1));
        assertEquals(-1, MuzzleFlashSpriteSheet.frameIndexAt(MuzzleFlashSpriteSheet.FRAME_SECONDS, 1));
        assertEquals(-1, MuzzleFlashSpriteSheet.frameIndexAt(0.0D, 0));
    }

    @Test
    void aNegativeElapsedTimeIsTreatedAsTheStart() {
        assertEquals(0, MuzzleFlashSpriteSheet.frameIndexAt(-1.0D, 3),
                "a clock that went backwards must not hide the flash");
    }

    @Test
    void theDurationIsTheFrameCountTimesTheFrameLength() {
        assertEquals(MuzzleFlashSpriteSheet.FRAME_SECONDS * 3, MuzzleFlashSpriteSheet.durationSeconds(3), 1.0E-9D);
        assertEquals(0.0D, MuzzleFlashSpriteSheet.durationSeconds(0), 1.0E-9D);
        assertTrue(MuzzleFlashSpriteSheet.durationSeconds(3) < 0.2D, "a muzzle flash is a very short effect");
    }
    @Test
    void aDeclaredGridMapsEveryFrameToItsOwnCell() {
        WeaponDefinition.MuzzleFlash.Sprites grid = new WeaponDefinition.MuzzleFlash.Sprites(1, 8, 60,
                WeaponDefinition.MuzzleFlash.SpriteType.PLAY_ONCE);

        assertEquals(8, grid.frameCount());
        assertEquals(1.0D / 60.0D, grid.frameSeconds(), 1.0E-9D);

        MuzzleFlashSpriteSheet.Frame first = MuzzleFlashSpriteSheet.frameAt(0.0D, grid);
        assertNotNull(first);
        assertEquals(0, first.index());
        assertEquals(0.0F, first.u0(), 1.0E-6F);
        assertEquals(0.125F, first.u1(), 1.0E-6F, "one sprite is one eighth of the sheet, not one third");
        assertEquals(0.0F, first.v0(), 1.0E-6F);
        assertEquals(1.0F, first.v1(), 1.0E-6F, "a single row spans the whole sheet height");

        MuzzleFlashSpriteSheet.Frame last = MuzzleFlashSpriteSheet.frameAt(0.13D, grid);
        assertNotNull(last);
        assertEquals(7, last.index());
        assertEquals(0.875F, last.u0(), 1.0E-6F);
        assertEquals(1.0F, last.u1(), 1.0E-6F, "the last frame ends exactly at the sheet edge");

        assertNull(MuzzleFlashSpriteSheet.frameAt(0.14D, grid),
                "a play_once grid stops after its last frame instead of wrapping onto the first");
    }

    @Test
    void rowsAreInterpetedVerticallyAndColumnsHorizontally() {
        // PB's rule: row = index / columns, column = index % columns; U comes from the column, V from the row.
        MuzzleFlashSpriteSheet.Frame topLeft = MuzzleFlashSpriteSheet.frame(0, 2, 2);
        MuzzleFlashSpriteSheet.Frame topRight = MuzzleFlashSpriteSheet.frame(1, 2, 2);
        MuzzleFlashSpriteSheet.Frame bottomLeft = MuzzleFlashSpriteSheet.frame(2, 2, 2);
        MuzzleFlashSpriteSheet.Frame bottomRight = MuzzleFlashSpriteSheet.frame(3, 2, 2);

        assertEquals(0.0F, topLeft.u0(), 1.0E-6F);
        assertEquals(0.0F, topLeft.v0(), 1.0E-6F);
        assertEquals(0.5F, topRight.u0(), 1.0E-6F, "the second frame moves right, not down");
        assertEquals(0.0F, topRight.v0(), 1.0E-6F);
        assertEquals(0.0F, bottomLeft.u0(), 1.0E-6F, "the third frame wraps onto the next row");
        assertEquals(0.5F, bottomLeft.v0(), 1.0E-6F);
        assertEquals(0.5F, bottomRight.u0(), 1.0E-6F);
        assertEquals(0.5F, bottomRight.v0(), 1.0E-6F);
    }

    @Test
    void aDeclaredGridNeverLeavesItsCell() {
        WeaponDefinition.MuzzleFlash.Sprites grid = new WeaponDefinition.MuzzleFlash.Sprites(2, 3, 60,
                WeaponDefinition.MuzzleFlash.SpriteType.LOOP);
        for (int index = 0; index < 12; index++) {
            MuzzleFlashSpriteSheet.Frame frame = MuzzleFlashSpriteSheet.frame(index, grid.rows(), grid.columns());
            assertTrue(frame.u0() >= 0.0F && frame.u1() <= 1.0F, "U stays inside the sheet");
            assertTrue(frame.v0() >= 0.0F && frame.v1() <= 1.0F, "V stays inside the sheet");
            assertEquals(1.0F / 3.0F, frame.u1() - frame.u0(), 1.0E-6F, "one cell is one column wide");
            assertEquals(0.5F, frame.v1() - frame.v0(), 1.0E-6F, "one cell is one row tall");
        }
    }
}
