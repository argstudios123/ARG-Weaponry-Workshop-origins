package com.arg_studios.arg_weaponry.weapon.model;

import java.util.List;

/**
 * One bone of a weapon model, and the one bone abstraction the whole renderer shares.
 *
 * <p>This is ARG Weaponry's equivalent of the reference implementation's poly-mesh bone interface: everything a geometry
 * system needs from a bone - its {@link #name() name}, its {@link #pivot() pivot}, its authored {@link #rotation()
 * rotation}, its {@link #meshes() geometry} and its {@link #children() children} - is here, and nothing else is. Both
 * geometry systems are attached the same way, as entries of {@link #meshes()}, so a cube bone and a poly-mesh bone are the
 * same kind of bone carrying different geometry, and a bone may carry both.</p>
 *
 * <p>The animated half of the contract is not stored here: {@link com.arg_studios.arg_weaponry.weapon.animation.SampledBone}
 * carries the animated offset, rotation delta and scale of one bone for one frame, and
 * {@code AnimatedBoneTransform} is the single place that turns the pair into a {@code PoseStack} transform. Because the
 * transform is applied by the hierarchy walk and not by geometry, both systems animate from the same evaluation - there
 * is no second skeleton, no per-geometry transform and therefore nothing that can be applied twice.</p>
 *
 * <p>Bones exist for three different purposes and all three are preserved:</p>
 * <ul>
 *     <li>geometry holders (polymesh or cube meshes),</li>
 *     <li>transform-only anchors such as {@code views}, {@code default_view}, {@code aiming_view} and
 *     the attachment positions,</li>
 *     <li>animation targets such as {@code camera}, {@code master_bolt} or {@code magazine_with_LH}.</li>
 * </ul>
 *
 * <p>{@code camera} is stored like any other bone and is explicitly <em>not</em> used as a view anchor;
 * {@code default_view} and {@code aiming_view} keep that role.</p>
 *
 * @param name     the bone name, unique inside the model
 * @param parent   the parent bone name, or {@code null} for a root bone
 * @param pivot    the model-space pivot that rotations are applied around
 * @param rotation the declared static rotation in degrees, {@code ZERO} when absent
 * @param meshes   the geometry attached to this bone, in declaration order; cube and poly meshes may be mixed
 * @param children the child bones, in declaration order
 */
public record ModelBone(String name, String parent, Vec3 pivot, Vec3 rotation, List<BoneMesh> meshes,
                        List<ModelBone> children) {

    public ModelBone {
        meshes = List.copyOf(meshes);
        children = List.copyOf(children);
    }

    /** @return {@code true} when the bone carries no geometry and only exists as a transform anchor. */
    public boolean isAnchorOnly() {
        return meshes.isEmpty();
    }

    /** @return the number of triangles below this bone, including its children. */
    public int triangleCount() {
        int count = 0;
        for (BoneMesh mesh : meshes) {
            count += mesh.triangleCount();
        }
        for (ModelBone child : children) {
            count += child.triangleCount();
        }
        return count;
    }
}
