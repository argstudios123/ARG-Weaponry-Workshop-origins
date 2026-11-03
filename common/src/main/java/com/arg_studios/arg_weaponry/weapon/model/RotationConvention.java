package com.arg_studios.arg_weaponry.weapon.model;

/**
 * The one conversion between a Bedrock-authored bone rotation and the rotation this renderer composes.
 *
 * <h2>The problem it solves</h2>
 * <p>Milestone 10J established that Bedrock geometry is written in the mirror of Minecraft's space, so the whole
 * weapon assembly is converted by {@code M = diag(-1, 1, 1)} once, outside the {@code default_view} anchor inverse
 * ({@code ViewBoneTransform}). That conversion is right for <em>positions</em> - without it the weapon sits on the
 * wrong side of the crosshair and every asymmetric detail swaps sides.</p>
 *
 * <p>The same conversion must also be applied to <em>rotations</em>, and a reflection does not act on a rotation the
 * way it acts on a position: in camera space a bone rotation becomes {@code M * R * M}. Conjugating the ZYX triple
 * {@code (a, b, c)} that way gives {@code (a, -b, -c)}, i.e. <strong>pitch and roll inverted while yaw is kept</strong>.
 * The weapon body is nearly symmetric, so nobody noticed on the static mesh, but the reload clips animate
 * {@code root} by about {@code (-13.3, -13.0, -13.8)} degrees per axis: with inverted pitch and roll the whole rifle
 * tilts the wrong way while its yaw is correct, which is exactly the "mirrored / inverted movement" that was
 * reported.</p>
 *
 * <h2>What the authored convention actually is</h2>
 * <p>Two independent references agree, and they agree exactly:</p>
 * <ul>
 *     <li><strong>Blockbench</strong> negates {@code rotation[0]} and {@code rotation[1]} (and {@code pivot[0]})
 *     when it loads a Bedrock file, so its viewport shows the authored triple as {@code (-a, -b, c)};</li>
 *     <li><strong>TaCZ Refabricated</strong> applies the triple unnegated in its Y-flipped parse space
 *     ({@code BedrockModel#convertRotation} is only degrees to radians, {@code BedrockPart#translateAndRotateAndScale}
 *     rotates Z, Y, X in that order) and then renders the gun under {@code poseStack.rotate(Axis.ZP, 180)}
 *     ({@code FirstPersonRenderGunEvent} / {@code GunItemRendererWrapper}), whose position part is
 *     {@code diag(-1, 1, 1)} - the same as our {@code M}. That outer Z-180 conjugates the rotations into
 *     {@code Rz(c) * Ry(-b) * Rx(-a)}, which is the same {@code (-a, -b, c)} triple.</li>
 * </ul>
 * <p>So the intended camera-space rotation is {@code Rz(c) * Ry(-b) * Rx(-a)}. Our pipeline produces
 * {@code M * R_feed * M}, and {@code M * Rz(z) * Ry(y) * Rx(x) * M} is {@code Rz(-z) * Ry(-y) * Rx(x)}; setting that
 * equal to the target gives the feed below. Verified numerically for a real reload keyframe: feeding
 * {@code (-a, b, -c)} reproduces the reference muzzle direction, feeding {@code (a, b, c)} does not.</p>
 *
 * <h2>Scope</h2>
 * <p>This applies to <em>every</em> Bedrock rotation this renderer composes - the sum of a bone's authored rest rotation and
 * its animated rotation, the rotations of the {@code default_view} anchor chain, and a <strong>cube's own
 * {@code rotation}</strong>, which {@link BedrockGeometryParser} converts here before it bakes the rotation into the
 * vertices - because they were all authored in the same space. Positions, pivots and scale factors are unaffected:
 * positions already go through {@code M}, and a uniform per-axis scale is unchanged by a reflection. The animated and
 * authored Euler triples are added before this conversion, exactly as Blockbench adds them before it builds its matrix;
 * the conversion is component-wise linear, so the order of those two operations cannot change the result.</p>
 */
public final class RotationConvention {

    private RotationConvention() {
    }

    /**
     * Converts one Bedrock-authored rotation triple into the triple this renderer composes in model space.
     *
     * <p>{@code (x, y, z) -> (-x, y, -z)}. Pitch and roll are inverted, yaw is kept. The returned triple is used with
     * the established ZYX composition order ({@code Rz * Ry * Rx}).</p>
     *
     * @param authored the authored rotation in degrees, may be {@code null}
     * @return the rotation to compose, never {@code null}
     */
    public static Vec3 toRenderSpace(Vec3 authored) {
        if (authored == null) {
            return Vec3.ZERO;
        }
        return new Vec3(-authored.x(), authored.y(), -authored.z());
    }
}
