package com.arg_studios.arg_weaponry.weapon.muzzle;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

import java.util.Random;

/**
 * Frame layout and timing of an animated muzzle-flash sprite sheet.
 *
 * <p>The configuration names <em>one</em> texture, so the frames are extracted from its pixel size instead of being
 * listed. The layout this class implements is the one the shipped sheet uses and the one a creator can produce in any
 * image editor: <strong>square frames in a single horizontal strip</strong>. {@code universal_flash.png} is 2172x724, so
 * {@code 2172 / 724 = 3} frames of 724x724 each.</p>
 *
 * <p>The rule is applied literally and nothing is guessed:</p>
 * <ul>
 *     <li>{@code height > 0} and {@code width % height == 0} -&gt; {@code width / height} frames;</li>
 *     <li>anything else (a square single image, a vertical strip, a sheet with padding that does not divide evenly)
 *     -&gt; one frame that covers the whole texture. A wrong guess would show pieces of neighbouring frames, so the
 *     ambiguous case deliberately renders the image as it is.</li>
 * </ul>
 *
 * <p>Timing is a renderer constant, not a content-pack property: the schema exposes no duration, and one sheet is one
 * animation. {@link #FRAME_SECONDS} therefore gives a three-frame sheet 0.12 s, which is the length of a muzzle flash
 * rather than of an explosion.</p>
 *
 * <p>The class is pure so the layout and the animation clock can be unit tested without a game running.</p>
 */
public final class MuzzleFlashSpriteSheet {

    /** How long one frame is shown, in seconds. Three frames therefore last {@code 0.12} s. */
    public static final double FRAME_SECONDS = 0.04D;

    /**
     * The generator behind the {@code random} sprite type.
     *
     * <p>Reseeded before every selection, exactly like the reference implementation's per-effect provider does, so the
     * field carries no state between calls and a shared instance is equivalent to one generator per effect - without
     * allocating a generator on every rendered frame. Only the client render thread reaches it, and the block is
     * synchronized so a second caller can never observe a half-reseeded sequence.</p>
     */
    private static final Random RANDOM_SOURCE = new Random();

    /**
     * One frame's portion of the texture, in normalised UV coordinates.
     *
     * @param index the zero-based frame index
     * @param u0    the left edge
     * @param v0    the top edge
     * @param u1    the right edge
     * @param v1    the bottom edge
     */
    public record Frame(int index, float u0, float v0, float u1, float v1) {
    }

    private MuzzleFlashSpriteSheet() {
    }

    /**
     * @param width  the texture width in pixels
     * @param height the texture height in pixels
     * @return the number of frames the sheet contains, at least one
     */
    public static int frameCount(int width, int height) {
        if (width <= 0 || height <= 0 || width < height || width % height != 0) {
            return 1;
        }
        return width / height;
    }

    /**
     * @param frameCount the frame count of the sheet
     * @return how long the whole animation lasts, in seconds; {@code 0} for no frames
     */
    public static double durationSeconds(int frameCount) {
        return Math.max(0, frameCount) * FRAME_SECONDS;
    }

    /**
     * Resolves the frame that is showing at a point in time.
     *
     * @param elapsedSeconds seconds since the shot that started the animation; negative values are treated as the start
     * @param frameCount     the frame count of the sheet
     * @return the frame to draw, or {@code null} when the animation has finished and nothing must be drawn
     */
    public static Frame frameAt(double elapsedSeconds, int frameCount) {
        return frameAt(elapsedSeconds, frameCount, 0.0D);
    }

    /**
     * Resolves the frame of a sheet whose layout is derived from its pixel size, honouring an authored lifetime.
     *
     * <p>Such a sheet has no declared type, so it behaves like {@code play_once}: its frames are shown in order, the last
     * one is held until the duration runs out, and a duration of {@code 0} means "not declared" and keeps the historical
     * behaviour of ending with the sheet's own frames.</p>
     *
     * @param elapsedSeconds  seconds since the shot
     * @param frameCount      the derived frame count
     * @param durationSeconds the authored visibility lifetime, or {@code 0} when the pack declares none
     * @return the frame to draw, or {@code null} when the flash is over
     */
    public static Frame frameAt(double elapsedSeconds, int frameCount, double durationSeconds) {
        double elapsed = Math.max(0.0D, Double.isFinite(elapsedSeconds) ? elapsedSeconds : 0.0D);
        boolean bounded = Double.isFinite(durationSeconds) && durationSeconds > 0.0D;
        if (bounded && elapsed >= durationSeconds) {
            return null;
        }
        if (frameCount <= 0) {
            return null;
        }
        int index = (int) (elapsed / FRAME_SECONDS);
        if (bounded) {
            return frame(Math.min(index, frameCount - 1), frameCount);
        }
        return index >= frameCount ? null : frame(index, frameCount);
    }

    /**
     * @param elapsedSeconds seconds since the shot that started the animation
     * @param frameCount     the frame count of the sheet
     * @return the zero-based frame index, or {@code -1} once every frame has been shown
     */
    public static int frameIndexAt(double elapsedSeconds, int frameCount) {
        if (frameCount <= 0) {
            return -1;
        }
        double elapsed = Math.max(0.0D, elapsedSeconds);
        int index = (int) (elapsed / FRAME_SECONDS);
        return index >= frameCount ? -1 : index;
    }

    /**
     * Resolves the frame that is showing at a point in time <strong>from a declared grid</strong>, for a sprite animation
     * that has no per-instance randomness.
     *
     * @param elapsedSeconds seconds since the shot that started the animation; negative values mean the start
     * @param sprites        the declared grid, never {@code null}
     * @return the frame to draw, or {@code null} when the animation has finished and nothing must be drawn
     */
    public static Frame frameAt(double elapsedSeconds, WeaponDefinition.MuzzleFlash.Sprites sprites) {
        return frameAt(elapsedSeconds, sprites, 0);
    }

    /**
     * Resolves the frame that is showing at a point in time <strong>from a declared grid</strong>.
     *
     * <p>The frame index advances one step per frame duration and is then mapped to a grid cell with
     * {@code row = index / columns}, {@code column = index % columns}. The cell's UV rectangle is
     * {@code [row / rows, column / columns]} to {@code [(row + 1) / rows, (column + 1) / columns]}.</p>
     *
     * <p>{@code random} reproduces the reference implementation's algorithm and its arithmetic exactly: the step is
     * computed from a <em>truncated millisecond</em> frame duration, and the sprite is
     * {@code new Random(seed + step).nextInt(frameCount)} - the same seed therefore always yields the same sprite for
     * the same instant, while a different seed yields a different sequence. See {@link #randomIndex(int, double, WeaponDefinition.MuzzleFlash.Sprites, int, boolean)}.</p>
     *
     * @param elapsedSeconds seconds since the shot that started the animation; negative values mean the start
     * @param sprites        the declared grid, never {@code null}
     * @param seed           the per-effect random seed; only {@code random} uses it
     * @return the frame to draw, or {@code null} when the animation has finished and nothing must be drawn
     */
    public static Frame frameAt(double elapsedSeconds, WeaponDefinition.MuzzleFlash.Sprites sprites, int seed) {
        return frameAt(elapsedSeconds, sprites, seed, 0.0D);
    }

    /**
     * Resolves the frame that is showing at a point in time, honouring an authored visibility lifetime.
     *
     * <p>This is where the two independent clocks meet. The <em>frame rate</em> decides how fast the sprite advances;
     * the <em>duration</em> decides when the flash stops being drawn, and it is authoritative for every animation type:
     * once {@code elapsedSeconds} reaches it, nothing is returned regardless of how many frames the sheet has or how the
     * type would otherwise behave.</p>
     * <ul>
     *   <li>{@code random} keeps picking a new sprite every frame interval for the whole lifetime;</li>
     *   <li>{@code loop} keeps wrapping until the lifetime ends;</li>
     *   <li>{@code static} keeps drawing its one sprite until the lifetime ends;</li>
     *   <li>{@code play_once} plays its frames in order and then <em>holds the last one</em> until the lifetime ends.</li>
     * </ul>
     *
     * <p>A duration of {@code 0} (or a non-finite value) means "not declared", and reproduces the behaviour packs had
     * before the property existed: the animation's own frames decide, so {@code random} and {@code play_once} stop when
     * the sheet runs out while {@code loop} and {@code static} keep drawing.</p>
     *
     * @param elapsedSeconds  seconds since the shot that started the animation; negative values mean the start
     * @param sprites         the declared grid, never {@code null}
     * @param seed            the per-effect random seed; only {@code random} uses it
     * @param durationSeconds the authored visibility lifetime, or {@code 0} when the pack declares none
     * @return the frame to draw, or {@code null} when the flash is over and nothing must be drawn
     */
    public static Frame frameAt(double elapsedSeconds, WeaponDefinition.MuzzleFlash.Sprites sprites, int seed,
                                double durationSeconds) {
        if (sprites == null) {
            return null;
        }
        double elapsed = Math.max(0.0D, Double.isFinite(elapsedSeconds) ? elapsedSeconds : 0.0D);
        boolean bounded = Double.isFinite(durationSeconds) && durationSeconds > 0.0D;
        if (bounded && elapsed >= durationSeconds) {
            return null;
        }
        int total = sprites.frameCount();
        int index = gridIndex(elapsed, sprites, total, seed, bounded);
        return index < 0 ? null : frame(index, sprites.rows(), sprites.columns());
    }

    /**
     * @param elapsedSeconds seconds since the shot that started the animation
     * @param sprites        the declared grid
     * @param total          the grid's frame count
     * @param seed           the per-effect random seed
     * @return the zero-based frame index, or {@code -1} when the animation is over
     */
    private static int gridIndex(double elapsed, WeaponDefinition.MuzzleFlash.Sprites sprites, int total, int seed,
                                 boolean bounded) {
        int step = (int) (elapsed / sprites.frameSeconds());
        return switch (sprites.type()) {
            case STATIC -> 0;
            case LOOP -> Math.floorMod(step, total);
            case RANDOM -> randomIndex(seed, elapsed, sprites, total, bounded);
            // A bounded one-shot holds its final frame until the duration runs out; an unbounded one, the historical
            // behaviour, draws nothing once its frames are used up.
            case PLAY_ONCE -> bounded ? Math.min(step, total - 1) : (step >= total ? -1 : step);
        };
    }

    /**
     * The reference implementation's random sprite selection, reproduced operation for operation.
     *
     * <p>PB builds one {@code RandomSpriteUVProvider} per effect instance, draws <em>one</em> seed in its constructor and
     * then, for every query, computes</p>
     * <pre>
     *   frameDurationMillis = (long) (1000.0 / spritesPerSecond)      // truncated, not rounded
     *   elapsedTimeMillis   = progress * lifetimeMillis
     *   seed                = this.seed + (int) (elapsedTimeMillis / frameDurationMillis)
     *   this.random.setSeed(seed);  spriteIndex = this.random.nextInt(totalSprites)
     * </pre>
     * <p>Two consequences are preserved here: the same instant always resolves to the same sprite (so repeated render
     * calls cannot flicker), and <em>different</em> effects - which carry different seeds - show different sequences.
     * Repeats are allowed, because a plain {@code nextInt(total)} can return an index that was already shown.</p>
     *
     * <p>One reseeded {@link Random} is used rather than one per call: because PB reseeds before every selection, the
     * result depends only on the seed, so a shared instance produces exactly the same numbers without allocating a
     * generator on every rendered frame. The animation ends when the sheet's own frames are used up - the closest
     * analogue of the reference implementation's effect duration, which this schema does not declare.</p>
     *
     * @param seed    the per-effect seed
     * @param elapsed elapsed seconds since the shot
     * @param sprites the declared grid
     * @param total   the grid's frame count
     * @param bounded whether an authored duration decides the end of the effect; when {@code false} the sheet's own
     *                frames do, which is the behaviour packs had before {@code duration_ms} existed
     * @return the sprite index, or {@code -1} once the animation is over
     */
    private static int randomIndex(int seed, double elapsed, WeaponDefinition.MuzzleFlash.Sprites sprites, int total,
                                   boolean bounded) {
        long frameMillis = (long) (1000.0D / sprites.fps());
        if (frameMillis <= 0L) {
            frameMillis = 1L;
        }
        double elapsedMillis = elapsed * 1000.0D;
        if (!bounded && elapsedMillis >= (double) total * frameMillis) {
            // Historical behaviour for a pack that declares no duration: the animation ends with its own frames. With a
            // duration the effect is bounded by that instead, so the sheet can cycle for as long as the flash lasts.
            return -1;
        }
        int step = (int) (elapsedMillis / frameMillis);
        synchronized (RANDOM_SOURCE) {
            RANDOM_SOURCE.setSeed(seed + step);
            return RANDOM_SOURCE.nextInt(total);
        }
    }

    /**
     * @param index   the zero-based frame index; values outside the grid are clamped
     * @param rows    the grid rows, at least one
     * @param columns the grid columns, at least one
     * @return that cell's UV rectangle
     */
    public static Frame frame(int index, int rows, int columns) {
        int safeRows = Math.max(1, rows);
        int safeColumns = Math.max(1, columns);
        int total = safeRows * safeColumns;
        int clamped = Math.min(Math.max(0, index), total - 1);
        int row = clamped / safeColumns;
        int column = clamped % safeColumns;
        float u0 = (float) column / safeColumns;
        float v0 = (float) row / safeRows;
        float u1 = (float) (column + 1) / safeColumns;
        float v1 = (float) (row + 1) / safeRows;
        return new Frame(clamped, u0, v0, u1, v1);
    }

    /**
     * @param index      the zero-based frame index; values outside the sheet are clamped
     * @param frameCount the frame count of the sheet
     * @return that frame's UV rectangle
     */
    public static Frame frame(int index, int frameCount) {
        int count = Math.max(1, frameCount);
        int clamped = Math.min(Math.max(0, index), count - 1);
        float width = 1.0F / count;
        float start = width * clamped;
        return new Frame(clamped, start, 0.0F, start + width, 1.0F);
    }
}
