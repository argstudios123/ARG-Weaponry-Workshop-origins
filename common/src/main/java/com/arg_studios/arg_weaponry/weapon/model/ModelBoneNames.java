package com.arg_studios.arg_weaponry.weapon.model;

import java.util.Map;

/**
 * The bone names that carry a fixed meaning for ARG Weaponry.
 *
 * <p>These three bones intentionally stay separate:</p>
 * <ul>
 *     <li>{@link #DEFAULT_VIEW} anchors the normal first-person placement,</li>
 *     <li>{@link #AIMING_VIEW} anchors the aiming first-person placement,</li>
 *     <li>{@link #CAMERA} is an animation target for camera animations and is never used as a
 *     placement anchor.</li>
 * </ul>
 *
 * <p>Arm bones are a fourth, separate meaning: they are the bones a first-person player arm is drawn at. See
 * {@link #armSideOf(String)}.</p>
 */
public final class ModelBoneNames {

    /** Anchor for the normal first-person placement. */
    public static final String DEFAULT_VIEW = "default_view";

    /** Anchor for the first-person placement while aiming. */
    public static final String AIMING_VIEW = "aiming_view";

    /** Animation target for camera animations; not a placement anchor. */
    public static final String CAMERA = "camera";

    /** Which player arm a rig bone drives. */
    public enum ArmSide {

        /** Not an arm bone. */
        NONE,

        /** The player's left arm. */
        LEFT,

        /** The player's right arm. */
        RIGHT
    }

    /**
     * Bone names that stand for one of the player's arms, keyed by their normalised form.
     *
     * <p>The convention was validated on the shipped M100 DMR rather than guessed from a name alone. Its
     * {@code right_hand} and {@code left_hand} bones carry a <em>single cube of {@code 4x12x4}</em> - the exact
     * size of a vanilla player arm - unwrapped into a 16x16 texture block, and the reload clips animate exactly
     * those two bones. In other words the cubes are arm placeholders inside the weapon atlas, which is why the
     * user wants the real skin there instead of a gunmetal arm. TaCZ reaches the same conclusion from the other
     * side: its weapon models declare {@code righthand_pos}/{@code lefthand_pos} bones and it renders the player's
     * own arm ({@code AvatarRenderer#renderRightHand}) at whatever pose that bone carries.</p>
     *
     * <p>The lookup is deliberately a fixed table rather than a substring test. A bone called
     * {@code handguard_default} or {@code thirdperson_hand} is <em>not</em> an arm, and treating it as one would
     * delete visible weapon geometry. Only names that unambiguously name a side and a limb match.</p>
     *
     * <p>Normalisation removes {@code '_'}, {@code '-'} and spaces and lower-cases the rest, so
     * {@code Left_Hand}, {@code left hand} and {@code lefthand} are the same entry.</p>
     */
    private static final Map<String, ArmSide> ARM_BONES = Map.ofEntries(
            Map.entry("lefthand", ArmSide.LEFT),
            Map.entry("lhand", ArmSide.LEFT),
            Map.entry("leftarm", ArmSide.LEFT),
            Map.entry("larm", ArmSide.LEFT),
            Map.entry("lefthandpos", ArmSide.LEFT),
            Map.entry("leftarmpos", ArmSide.LEFT),
            Map.entry("righthand", ArmSide.RIGHT),
            Map.entry("rhand", ArmSide.RIGHT),
            Map.entry("rightarm", ArmSide.RIGHT),
            Map.entry("rarm", ArmSide.RIGHT),
            Map.entry("righthandpos", ArmSide.RIGHT),
            Map.entry("rightarmpos", ArmSide.RIGHT));

    /**
     * @param boneName a bone name from a weapon model, may be {@code null}
     * @return which arm the bone stands for, {@link ArmSide#NONE} when it is ordinary weapon geometry
     */
    public static ArmSide armSideOf(String boneName) {
        if (boneName == null || boneName.isBlank()) {
            return ArmSide.NONE;
        }
        StringBuilder normalised = new StringBuilder(boneName.length());
        for (int index = 0; index < boneName.length(); index++) {
            char character = boneName.charAt(index);
            if (character == '_' || character == '-' || character == ' ') {
                continue;
            }
            normalised.append(Character.toLowerCase(character));
        }
        return ARM_BONES.getOrDefault(normalised.toString(), ArmSide.NONE);
    }

    private ModelBoneNames() {
    }
}
