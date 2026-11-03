package com.arg_studios.arg_weaponry.weapon.audio;

import java.util.HashMap;
import java.util.Map;

/**
 * Chooses which recorded variant of a sound plays next, without ever repeating one immediately.
 *
 * <h2>Why a shuffle bag and not a random index</h2>
 * <p>Picking {@code nextInt(n)} for every shot is statistically uniform, which is exactly the problem: with three samples,
 * roughly a third of the shots repeat the previous one, and the ear notices. A <em>shuffle bag</em> deals every sample once
 * per cycle in a random order and only then starts a new cycle, so with {@code n} samples the same sample can never come
 * back within {@code n - 1} shots, and every sample is used equally often.</p>
 *
 * <p>Two details make it sound natural rather than mechanical:</p>
 * <ul>
 *   <li>a new cycle never starts with the sample the previous cycle ended on, so a cycle boundary is not audible as a
 *   repeat either;</li>
 *   <li>the bag is keyed by a <em>stream</em> (weapon plus key), so two weapons - or the shot and the reload of one weapon -
 *   never consume each other's samples.</li>
 * </ul>
 *
 * <h2>Purity</h2>
 * <p>The picker holds no clock and no Minecraft type: it asks the caller for one random number below a bound, which the
 * client supplies from its sound random and a test from {@code new Random(seed)::nextInt}. That makes the "never repeats"
 * property directly testable.</p>
 *
 * <p>State is intentionally small and cheap: one short array and an index per stream, allocated the first time that stream
 * is used.</p>
 */
public final class SoundVariationPicker {

    /** Supplies a uniformly distributed value in {@code [0, bound)}; {@code RandomSource} and {@code Random} both fit. */
    @FunctionalInterface
    public interface IndexShuffler {

        /**
         * @param bound an exclusive upper bound, always positive
         * @return a value in {@code [0, bound)}
         */
        int nextInt(int bound);
    }

    /** The dealt order of one stream and how much of it has been used. */
    private static final class Bag {
        private int[] order = new int[0];
        private int cursor;

        /** The sample index played last, or {@code -1} when nothing has been played yet. */
        private int last = -1;
    }

    private final Map<String, Bag> bags = new HashMap<>();

    /**
     * Picks the index of the sample to play next.
     *
     * @param stream  the identity of the sound being played, e.g. {@code "arg_pack:m100_dmr#fire"}
     * @param count   the number of available samples, at least {@code 1}
     * @param shuffle the random source
     * @return an index in {@code [0, count)}
     */
    public int pick(String stream, int count, IndexShuffler shuffle) {
        if (count <= 0) {
            return 0;
        }
        if (count == 1 || shuffle == null) {
            return 0;
        }
        Bag bag = bags.computeIfAbsent(stream == null ? "" : stream, key -> new Bag());
        if (bag.order.length != count) {
            // A pack that added or removed a variant invalidates the dealt order, not the stream.
            bag.order = new int[0];
            bag.cursor = 0;
        }
        if (bag.cursor >= bag.order.length) {
            bag.order = deal(count, bag.last, shuffle);
            bag.cursor = 0;
        }
        int index = bag.order[bag.cursor++];
        bag.last = index;
        return index;
    }

    /**
     * @param stream the stream that was played
     * @return the sample index it played last, or {@code -1} when it has not played yet
     */
    public int lastIndex(String stream) {
        Bag bag = bags.get(stream == null ? "" : stream);
        return bag == null ? -1 : bag.last;
    }

    /** Forgets every dealt order, e.g. on a resource reload or when leaving a world. */
    public void reset() {
        bags.clear();
    }

    /**
     * Deals a fresh permutation.
     *
     * <p>The first entry is swapped away from {@code avoid} when that is possible, so the last sample of the previous cycle
     * is never the first one of the next.</p>
     *
     * @param count   the number of samples
     * @param avoid   the index that must not come first, or {@code -1}
     * @param shuffle the random source
     * @return the dealt order, never {@code null}
     */
    private static int[] deal(int count, int avoid, IndexShuffler shuffle) {
        int[] order = new int[count];
        for (int index = 0; index < count; index++) {
            order[index] = index;
        }
        for (int index = count - 1; index > 0; index--) {
            int swap = clamp(shuffle.nextInt(index + 1), index + 1);
            int value = order[index];
            order[index] = order[swap];
            order[swap] = value;
        }
        if (avoid >= 0 && avoid < count && order[0] == avoid) {
            int alternative = 1 + clamp(shuffle.nextInt(Math.max(1, count - 1)), Math.max(1, count - 1));
            if (alternative < count) {
                int value = order[0];
                order[0] = order[alternative];
                order[alternative] = value;
            }
        }
        return order;
    }

    /**
     * @param value the value to clamp
     * @param bound the exclusive upper bound
     * @return {@code value} when it lies in {@code [0, bound)}, {@code 0} otherwise
     */
    private static int clamp(int value, int bound) {
        return value < 0 || value >= bound ? 0 : value;
    }
}
