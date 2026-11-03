package com.arg_studios.arg_weaponry.weapon.muzzle;

import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Chooses which configured attachment point one shot uses.
 *
 * <p>{@code bone} and {@code bones} describe the same thing - where a flash may appear - so one rule covers both: the
 * configured names are filtered against the model (a name the model does not contain is dropped, exactly like a missing
 * glowing part), and one of the survivors is chosen. A single survivor is therefore always chosen, which is what makes
 * the single-bone form behave as "this attachment point every shot", and several survivors are picked at random, which
 * is what makes the multiple-bone form vary between shots.</p>
 *
 * <p>Only ever <em>one</em> name is returned: the schema's multiple form means "any of these", never "all of these".</p>
 *
 * <p>Pure, so the selection can be exercised headlessly with a seeded {@link Random}.</p>
 */
public final class MuzzleFlashAttachment {

    private MuzzleFlashAttachment() {
    }

    /**
     * Filters the configured attachment points down to the bones the model really contains.
     *
     * @param configured the {@code bone}/{@code bones} values, in authored order
     * @param model      the parsed weapon model, may be {@code null}
     * @return the usable attachment points, without duplicates; empty when nothing can be used
     */
    public static List<String> present(List<String> configured, WeaponModel model) {
        if (configured == null || configured.isEmpty() || model == null) {
            return List.of();
        }
        List<String> usable = new ArrayList<>(configured.size());
        for (String bone : configured) {
            if (bone == null || bone.isBlank() || usable.contains(bone)) {
                continue;
            }
            if (model.bone(bone) != null) {
                usable.add(bone);
            }
        }
        return List.copyOf(usable);
    }

    /**
     * Filters the configured attachment points down to those a caller accepts.
     *
     * @param configured the configured names
     * @param exists     the predicate that decides whether a name is usable
     * @return the usable attachment points, without duplicates
     */
    public static List<String> present(List<String> configured, java.util.function.Predicate<String> exists) {
        if (configured == null || configured.isEmpty() || exists == null) {
            return List.of();
        }
        List<String> usable = new ArrayList<>(configured.size());
        for (String bone : configured) {
            if (bone == null || bone.isBlank() || usable.contains(bone) || !exists.test(bone)) {
                continue;
            }
            usable.add(bone);
        }
        return List.copyOf(usable);
    }

    /**
     * Chooses one attachment point for one shot.
     *
     * @param candidates the usable attachment points; typically {@link #present(List, WeaponModel)}
     * @param random     the randomness to draw from, advanced exactly once
     * @return the chosen bone, or {@code null} when there is nothing to choose from
     */
    public static String select(List<String> candidates, Random random) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        Random source = random == null ? new Random() : random;
        return candidates.get(source.nextInt(candidates.size()));
    }

    /**
     * @param configured the configured attachment points
     * @param model      the parsed weapon model, may be {@code null}
     * @return the configured names that the model does not contain
     */
    public static List<String> missing(List<String> configured, WeaponModel model) {
        if (configured == null || configured.isEmpty() || model == null) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (String bone : configured) {
            if (bone == null || bone.isBlank() || missing.contains(bone)) {
                continue;
            }
            if (model.bone(bone) == null) {
                missing.add(bone);
            }
        }
        return List.copyOf(missing);
    }
}
