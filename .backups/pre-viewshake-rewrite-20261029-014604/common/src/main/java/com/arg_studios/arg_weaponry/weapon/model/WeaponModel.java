package com.arg_studios.arg_weaponry.weapon.model;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

import java.util.List;
import java.util.Map;

/**
 * A parsed, immutable Bedrock geometry model.
 *
 * <p>The model keeps the authored bone hierarchy, so future bone animations can apply parent-child
 * transforms without re-parsing. {@link #bonesInOrder()} contains every bone exactly once, in the
 * order that produces correct hierarchy nesting (parents before children).</p>
 *
 * @param id            the pack-relative resource identifier the model was loaded from
 * @param identifier    the authored {@code description.identifier}, verbatim (often {@code geometry.unknown})
 * @param formatVersion the authored {@code format_version}, verbatim
 * @param textureWidth  the declared texture width in pixels
 * @param textureHeight the declared texture height in pixels
 * @param bonesInOrder  every bone, parents before children
 * @param roots         the root bones
 * @param polygonCount  the number of source polygons (polymesh polys and cube faces)
 * @param cubeCount     the number of source cubes
 */
public record WeaponModel(ResourceId id, String identifier, String formatVersion, int textureWidth,
                          int textureHeight, List<ModelBone> bonesInOrder, List<ModelBone> roots,
                          int polygonCount, int cubeCount) {

    public WeaponModel {
        bonesInOrder = List.copyOf(bonesInOrder);
        roots = List.copyOf(roots);
    }

    /** @return every bone keyed by name. */
    public Map<String, ModelBone> bonesByName() {
        Map<String, ModelBone> map = new java.util.LinkedHashMap<>();
        for (ModelBone bone : bonesInOrder) {
            map.put(bone.name(), bone);
        }
        return Map.copyOf(map);
    }

    /** @return the bone with the given name, or {@code null}. */
    public ModelBone bone(String name) {
        for (ModelBone bone : bonesInOrder) {
            if (bone.name().equals(name)) {
                return bone;
            }
        }
        return null;
    }

    /** @return the total number of triangles in the whole model. */
    public int triangleCount() {
        int count = 0;
        for (ModelBone root : roots) {
            count += root.triangleCount();
        }
        return count;
    }

    /** @return the total number of renderable meshes (one per bone with geometry). */
    public int meshCount() {
        int count = 0;
        for (ModelBone bone : bonesInOrder) {
            count += bone.meshes().size();
        }
        return count;
    }

    /** @return the number of bones that carry geometry. */
    public int geometryBoneCount() {
        int count = 0;
        for (ModelBone bone : bonesInOrder) {
            if (!bone.meshes().isEmpty()) {
                count++;
            }
        }
        return count;
    }
}
