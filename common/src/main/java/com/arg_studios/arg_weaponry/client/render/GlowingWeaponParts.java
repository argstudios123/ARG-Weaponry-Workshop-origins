package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.model.GlowingBoneSelection;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers, once per weapon and model, which bones render emissively.
 *
 * <p>The rule itself lives in {@link GlowingBoneSelection}: the configured {@code part_name} values are parent bones, so
 * the glowing set is their whole descendant closure. This class only remembers the answer, because the closure is a
 * property of the geometry and the definition and cannot change while both are cached - and because walking a hierarchy
 * on every frame, for every bone, would be exactly the kind of per-frame recursion a renderer must not do.</p>
 *
 * <p>One lookup per draw call is left: a map hit keyed by the pack, the model resource and the declared part list. The
 * renderer then tests each bone against the returned set, which is constant time.</p>
 *
 * <p>A {@code part_name} that no bone matches is reported once per weapon and name. The bone is then simply not part of
 * the set, so a typo costs one warning and nothing else - the remaining sections still glow.</p>
 */
public final class GlowingWeaponParts {

    private static final Map<String, Set<String>> CACHE = new HashMap<>();

    /** Findings already logged, so a warning cannot be repeated on every frame. */
    private static final Set<String> REPORTED = new HashSet<>();

    private GlowingWeaponParts() {
    }

    /**
     * Returns the bones of a model that must be drawn emissively.
     *
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon definition, which declares the parent bones
     * @param model      the model being drawn
     * @return the glowing bone names, or an empty set when the weapon declares no glowing part
     */
    public static synchronized Set<String> bonesOf(ContentPack pack, WeaponDefinition definition, WeaponModel model) {
        if (pack == null || definition == null || model == null || definition.glowingWeaponParts().isEmpty()) {
            return Set.of();
        }

        List<WeaponDefinition.GlowingPart> parts = definition.glowingWeaponParts();
        String key = cacheKey(pack, model, parts);
        Set<String> cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        List<String> parents = new ArrayList<>(parts.size());
        for (WeaponDefinition.GlowingPart part : parts) {
            parents.add(part.name());
        }
        GlowingBoneSelection.Resolved resolved = GlowingBoneSelection.resolve(model, parents);
        for (String missing : resolved.missingParents()) {
            String finding = key + "|" + missing;
            if (REPORTED.add(finding)) {
                Constants.LOG.warn("[ARG] The glowing part '{}' of weapon '{}' names no bone in model '{}'; that entry "
                        + "is ignored. The model contains {} bone(s)", missing, definition.name(), model.id(),
                        model.bonesInOrder().size());
            }
        }

        Set<String> bones = resolved.bones();
        CACHE.put(key, bones);
        return bones;
    }

    /** Drops every remembered selection; called on a resource reload, together with the model cache. */
    public static synchronized void invalidate() {
        CACHE.clear();
        REPORTED.clear();
    }

    /** @return the number of cached selections, for diagnostics. */
    public static synchronized int size() {
        return CACHE.size();
    }

    private static String cacheKey(ContentPack pack, WeaponModel model, List<WeaponDefinition.GlowingPart> parts) {
        StringBuilder builder = new StringBuilder(pack.id()).append('|').append(model.id().asString()).append('|');
        for (WeaponDefinition.GlowingPart part : parts) {
            builder.append(part.name()).append(',');
        }
        return builder.toString();
    }
}
