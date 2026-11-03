package com.arg_studios.arg_weaponry.weapon.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a {@code glowing_weapon_parts} configuration onto a parsed model.
 *
 * <p>The JSON names <em>parent</em> bones. Everything underneath such a bone glows, so the answer is not the declared
 * names but the closure of their hierarchies: the named bone itself plus every descendant, at any depth. The result is
 * therefore a set of bone names a renderer can test in constant time per bone instead of recursing per frame - the
 * recursion happens once, here, when the model and the definition are first drawn.</p>
 *
 * <p>Nothing about geometry type participates in the decision. A bone either belongs to a glowing hierarchy or it does
 * not, and every mesh attached to it - one cube, several cubes, a {@code poly_mesh}, several meshes of both kinds -
 * inherits the decision. That is what makes mixed and multi-mesh bones work without the renderer knowing anything about
 * them.</p>
 *
 * <p>This class is deliberately free of client types so the hierarchy rules can be exercised headlessly.</p>
 */
public final class GlowingBoneSelection {

    /** A resolved selection. {@link Resolved#bones()} is ordered and contains every glowing bone exactly once. */
    public record Resolved(Set<String> bones, List<String> missingParents) {

        public Resolved {
            bones = Collections.unmodifiableSet(new LinkedHashSet<>(bones));
            missingParents = List.copyOf(missingParents);
        }

        /** @return {@code true} when no bone glows. */
        public boolean isEmpty() {
            return bones.isEmpty();
        }

        /**
         * @param boneName a bone name from the model
         * @return {@code true} when that bone's geometry must be drawn emissively
         */
        public boolean glows(String boneName) {
            return bones.contains(boneName);
        }
    }

    /** The empty selection, used when a weapon declares no glowing part at all. */
    public static final Resolved NONE = new Resolved(Set.of(), List.of());

    private GlowingBoneSelection() {
    }

    /**
     * Resolves the configured parent bones against a model.
     *
     * @param model            the parsed weapon model, may be {@code null}
     * @param parentBoneNames  the {@code part_name} values, in authored order; duplicates are ignored
     * @return the closure of every named bone hierarchy, plus the names that matched no bone in the model
     */
    public static Resolved resolve(WeaponModel model, Iterable<String> parentBoneNames) {
        if (model == null || parentBoneNames == null) {
            return NONE;
        }

        Set<String> glowing = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        Set<String> seenParents = new LinkedHashSet<>();
        for (String name : parentBoneNames) {
            if (name == null || name.isBlank() || !seenParents.add(name)) {
                continue;
            }
            ModelBone parent = model.bone(name);
            if (parent == null) {
                missing.add(name);
                continue;
            }
            collect(parent, glowing);
        }

        if (glowing.isEmpty() && missing.isEmpty()) {
            return NONE;
        }
        return new Resolved(glowing, missing);
    }

    /**
     * Adds one bone and, recursively, every bone below it.
     *
     * <p>The membership test doubles as the visited marker: a bone can only be reached once by a tree walk, but a
     * malformed model that repeats a bone under two parents must not be allowed to loop, and a second configured parent
     * that sits inside an already-glowing hierarchy must not repeat the work.</p>
     *
     * @param bone   the bone being visited
     * @param target the set that receives every glowing bone name
     */
    private static void collect(ModelBone bone, Set<String> target) {
        if (!target.add(bone.name())) {
            return;
        }
        for (ModelBone child : bone.children()) {
            collect(child, target);
        }
    }
}
