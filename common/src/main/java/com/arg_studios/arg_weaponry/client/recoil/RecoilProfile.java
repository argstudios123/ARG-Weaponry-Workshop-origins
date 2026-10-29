package com.arg_studios.arg_weaponry.client.recoil;

import java.util.Random;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The sanitised recoil values of one weapon, resolved once per accepted shot.
 *
 * <p>This is the only place that reads the JSON, so the two controllers below it never have to know whether a value
 * came from the nested {@code recoil} object or from a legacy top-level property.</p>
 *
 * <h2>Units</h2>
 * <table border="1">
 *   <caption>Recoil properties and their units</caption>
 *   <tr><th>Property</th><th>Unit</th><th>Owner</th><th>Meaning</th></tr>
 *   <tr><td>{@code kickback_recoil_amplitude}</td><td>blocks</td><td>{@link WeaponKickbackController}</td>
 *       <td>Backward displacement of the weapon and arms along the view axis for one accepted shot.
 *       <em>Optional</em>: when the weapon does not declare it the weapon does not move at all.</td></tr>
 *   <tr><td>{@code vertical_recoil}</td><td>degrees of pitch</td><td>{@link PlayerRecoilController}</td>
 *       <td>Upward aim offset one accepted shot adds to the player's accumulated recoil offset. It is an
 *       <em>impulse</em>, not the height of a bump: successive shots add, and the accumulated offset recovers over the
 *       weapon's {@link #recoverySeconds() recovery interval}.</td></tr>
 *   <tr><td>{@code horizontal_recoil_left} / {@code horizontal_recoil_right}</td><td>degrees of yaw</td>
 *       <td>{@link PlayerRecoilController}</td>
 *       <td>Sideways aim offset of one accepted shot, one magnitude per direction, chosen once per shot by
 *       {@link #horizontalDirection(Random)}. They stay independent so a weapon can be configured to walk
 *       asymmetrically.</td></tr>
 *   <tr><td>{@code smoothing}</td><td>share of the remaining difference per 50 ms</td>
 *       <td>{@link PlayerRecoilController}</td>
 *       <td>How gradually the target offset is handed to the player. Optional, default
 *       {@link PlayerRecoilController#DEFAULT_SMOOTHING} ({@code 1.0}), which applies the whole difference at once and
 *       therefore reproduces the behaviour of a weapon that does not declare the property.</td></tr>
 *   <tr><td>{@code recovery_speed}</td><td>multiple of the baseline rate</td><td>{@link PlayerRecoilController}</td>
 *       <td>Rate at which the accumulated offset normalises, {@code 1.0} being the existing baseline of
 *       {@link PlayerRecoilController#RECOVERY_SECONDS}. Optional; the interval is
 *       {@code RECOVERY_SECONDS / recovery_speed}, so smaller values are slower and larger values are faster.</td></tr>
 *   <tr><td>{@code aim_multiplier}</td><td>factor</td><td>{@link #impulseScale(boolean, boolean)}</td>
 *       <td>Multiplier on the per-shot <em>camera</em> impulse while aiming. Optional, default {@code 1.0}. It never
 *       touches the weapon kickback.</td></tr>
 *   <tr><td>{@code crouch_multiplier}</td><td>factor</td><td>{@link #impulseScale(boolean, boolean)}</td>
 *       <td>Multiplier on the per-shot <em>camera</em> impulse while crouching. Optional, default {@code 1.0}. Applied
 *       multiplicatively with {@code aim_multiplier} when both apply, and it never touches the weapon kickback.</td></tr>
 * </table>
 *
 * <h2>Defaults and limits</h2>
 * <p>Every optional property resolves to a value that preserves the behaviour the weapon had before the property existed:
 * {@code smoothing = 1.0}, {@code recovery_speed = 1.0}, {@code aim_multiplier = 1.0}, {@code crouch_multiplier = 1.0}.
 * The values are sanitised here, once, following the conventions of the existing ones: a non-numeric or non-finite value
 * falls back to its default, and an out-of-range value is clamped to the documented range instead of being rejected, so a
 * single odd number can never make a weapon unusable.</p>
 *
 * <h2>Retired properties</h2>
 * <p>{@code screen_shake_Recoil_value} and {@code screen_shake_recoil_speed} are retired. They are not part of this
 * profile any more, no controller consumes them and no code path can turn them into a camera effect; a content pack that
 * still carries them keeps loading, and the values are simply ignored (see
 * {@link com.arg_studios.arg_weaponry.weapon.WeaponDefinitionParser}).</p>
 *
 * <h2>Sustained fire</h2>
 * <p>{@code sustained_firing_Recoil} keeps its authored camelCase keys and is still parsed into {@link Sustained}, so the
 * content packs and the data model are unchanged. It no longer displaces the weapon model, though: the backward movement
 * of the weapon is produced only by {@code kickback_recoil_amplitude} through {@link WeaponKickbackController}, because
 * the old per-shot step of this block was the cause of both the backward jump at every shot and the snap at the end of a
 * response, and because "amplitude controls the strength of the weapon kick" only holds while it is the only
 * contributor.</p>
 *
 * @param kickbackBlocks         backward weapon displacement of one shot, in blocks; only meaningful when
 *                               {@code kickbackEnabled} is true
 * @param kickbackEnabled        whether the weapon <em>configured</em> {@code kickback_recoil_amplitude} at all; this is
 *                               the enable switch, and it exists so a missing property can never be confused with a
 *                               configured zero
 * @param verticalDegrees        upward aim offset of one shot, in degrees of pitch
 * @param horizontalLeftDegrees  leftward aim offset of one shot, in degrees of yaw
 * @param horizontalRightDegrees rightward aim offset of one shot, in degrees of yaw
 * @param smoothing              share of the remaining camera offset applied per reference interval, in {@code (0, 1]}
 * @param recoverySpeed          multiple of the baseline recovery rate, in
 *                               {@code [MIN_RECOVERY_SPEED, MAX_RECOVERY_SPEED]}
 * @param aimMultiplier          factor applied to the camera impulse while aiming, in {@code [0, 1]}
 * @param crouchMultiplier       factor applied to the camera impulse while crouching, in {@code [0, 1]}
 * @param sustained              the {@code sustained_firing_Recoil} block, never {@code null}
 */
public record RecoilProfile(double kickbackBlocks, boolean kickbackEnabled, double verticalDegrees,
                            double horizontalLeftDegrees, double horizontalRightDegrees, double smoothing,
                            double recoverySpeed, double aimMultiplier, double crouchMultiplier, Sustained sustained) {

    /** The largest displacement one shot may request, in blocks. */
    public static final double MAX_KICKBACK_AMPLITUDE_BLOCKS = 2.0;

    /** The upper bound of one view impulse, in degrees, so a typo cannot spin the player around. */
    public static final double MAX_VIEW_IMPULSE_DEGREES = 45.0;

    /**
     * Default {@code recovery_speed}: the existing baseline, i.e. the accumulated offset recovers over
     * {@link PlayerRecoilController#RECOVERY_SECONDS}.
     */
    public static final double DEFAULT_RECOVERY_SPEED = 1.0;

    /** Slowest accepted {@code recovery_speed}, i.e. the longest recovery interval. */
    public static final double MIN_RECOVERY_SPEED = PlayerRecoilController.RECOVERY_SECONDS
            / PlayerRecoilController.MAX_RECOVERY_SECONDS;

    /** Fastest accepted {@code recovery_speed}, i.e. the shortest recovery interval. */
    public static final double MAX_RECOVERY_SPEED = PlayerRecoilController.RECOVERY_SECONDS
            / PlayerRecoilController.MIN_RECOVERY_SECONDS;

    /** Default {@code aim_multiplier}: aiming keeps the configured camera impulse. */
    public static final double DEFAULT_AIM_MULTIPLIER = 1.0;

    /** Default {@code crouch_multiplier}: crouching keeps the configured camera impulse. */
    public static final double DEFAULT_CROUCH_MULTIPLIER = 1.0;

    /** The neutral profile: no recoil at all, and kickback explicitly not configured. */
    public static final RecoilProfile NONE = new RecoilProfile(0.0, false, 0.0, 0.0, 0.0,
            PlayerRecoilController.DEFAULT_SMOOTHING, DEFAULT_RECOVERY_SPEED, DEFAULT_AIM_MULTIPLIER,
            DEFAULT_CROUCH_MULTIPLIER, Sustained.NONE);

    public RecoilProfile {
        kickbackBlocks = clamp(kickbackBlocks, 0.0, MAX_KICKBACK_AMPLITUDE_BLOCKS);
        verticalDegrees = clamp(verticalDegrees, -MAX_VIEW_IMPULSE_DEGREES, MAX_VIEW_IMPULSE_DEGREES);
        horizontalLeftDegrees = clamp(horizontalLeftDegrees, 0.0, MAX_VIEW_IMPULSE_DEGREES);
        horizontalRightDegrees = clamp(horizontalRightDegrees, 0.0, MAX_VIEW_IMPULSE_DEGREES);
        smoothing = clamp(smoothing, PlayerRecoilController.MIN_SMOOTHING, PlayerRecoilController.MAX_SMOOTHING);
        recoverySpeed = clamp(recoverySpeed, MIN_RECOVERY_SPEED, MAX_RECOVERY_SPEED);
        aimMultiplier = clamp(aimMultiplier, 0.0, 1.0);
        crouchMultiplier = clamp(crouchMultiplier, 0.0, 1.0);
        sustained = sustained == null ? Sustained.NONE : sustained;
    }

    /**
     * Backwards-compatible construction for a configuration that declares a kickback amplitude (the pre-enable-flag
     * shape) and no tuning properties. The flag is derived from the amplitude, so this constructor can never claim
     * "configured" with no value.
     */
    public RecoilProfile(double kickbackBlocks, double verticalDegrees, double horizontalLeftDegrees,
                         double horizontalRightDegrees, Sustained sustained) {
        this(kickbackBlocks, true, verticalDegrees, horizontalLeftDegrees, horizontalRightDegrees, sustained);
    }

    /**
     * Backwards-compatible construction of a profile without the four tuning properties; every one of them takes its
     * documented default, which is exactly the behaviour such a profile had before the properties existed.
     */
    public RecoilProfile(double kickbackBlocks, boolean kickbackEnabled, double verticalDegrees,
                         double horizontalLeftDegrees, double horizontalRightDegrees, Sustained sustained) {
        this(kickbackBlocks, kickbackEnabled, verticalDegrees, horizontalLeftDegrees, horizontalRightDegrees,
                PlayerRecoilController.DEFAULT_SMOOTHING, DEFAULT_RECOVERY_SPEED, DEFAULT_AIM_MULTIPLIER,
                DEFAULT_CROUCH_MULTIPLIER, sustained);
    }

    /**
     * Resolves the profile of a weapon, applying the documented precedence: nested values first, legacy top-level
     * values second, and the legacy symmetrical {@code horizontal_recoil} only when neither direction is configured.
     *
     * @param definition the weapon definition, may be {@code null}
     * @return the profile, never {@code null}
     */
    public static RecoilProfile of(WeaponDefinition definition) {
        if (definition == null) {
            return NONE;
        }
        return of(definition.recoil(), definition.sustainedFiringRecoil());
    }

    /**
     * Resolves the profile from the two parsed JSON blocks.
     *
     * <p>Separated from {@link #of(WeaponDefinition)} so the resolution rules - absent versus configured zero and the
     * horizontal precedence - can be exercised without a whole weapon definition.</p>
     *
     * @param recoil           the {@code recoil} block, may be {@code null} when the weapon declares none
     * @param sustainedRecoil  the {@code sustained_firing_Recoil} block, may be {@code null}
     * @return the profile, never {@code null}
     */
    public static RecoilProfile of(WeaponDefinition.Recoil recoil, WeaponDefinition.SustainedRecoil sustainedRecoil) {
        Sustained sustained = Sustained.of(sustainedRecoil);
        if (recoil == null) {
            // No recoil object at all: nothing is configured, so kickback stays disabled even if a sustained block
            // happens to be present.
            return new RecoilProfile(0.0, false, 0.0, 0.0, 0.0, sustained);
        }
        // The parser keeps an absent property as null, which is the only place the difference between "absent" and
        // "configured zero" exists. It is carried into the profile explicitly instead of being flattened to 0.0.
        boolean kickbackConfigured = recoil.kickbackAmplitude() != null;
        return new RecoilProfile(
                value(recoil.kickbackAmplitude()),
                kickbackConfigured,
                value(recoil.vertical()),
                recoil.horizontalMagnitude(true),
                recoil.horizontalMagnitude(false),
                tuning(recoil.smoothing(), PlayerRecoilController.DEFAULT_SMOOTHING),
                tuning(recoil.recoverySpeed(), DEFAULT_RECOVERY_SPEED),
                tuning(recoil.aimMultiplier(), DEFAULT_AIM_MULTIPLIER),
                tuning(recoil.crouchMultiplier(), DEFAULT_CROUCH_MULTIPLIER),
                sustained);
    }

    private static double value(Double configured) {
        return configured == null || !Double.isFinite(configured) ? 0.0 : configured;
    }

    /**
     * @param configured the parsed value, may be {@code null} when the weapon does not declare the property
     * @param fallback   the documented default, used instead of a missing or non-numeric value
     * @return the value to hand to the canonical constructor, which applies the range limits
     */
    private static double tuning(Double configured, double fallback) {
        return configured == null || !Double.isFinite(configured) ? fallback : configured;
    }

    static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }

    /**
     * @return whether the weapon-model kickback is active: the property was <em>configured</em> and its amplitude is
     *         greater than zero. A missing property is {@code false}, a configured {@code 0.0} is {@code false}, and no
     *         other recoil setting can turn it on.
     */
    public boolean hasKickback() {
        return kickbackEnabled && kickbackBlocks > 0.0;
    }

    /** @return whether the weapon asks for any player/view angle change. */
    public boolean hasViewRecoil() {
        return verticalDegrees != 0.0 || horizontalLeftDegrees > 0.0 || horizontalRightDegrees > 0.0;
    }

    /** @return whether this profile would do anything at all. */
    public boolean isNeutral() {
        return !hasKickback() && !hasViewRecoil();
    }

    /**
     * The factor the per-shot camera impulse is scaled by, for the player's current stance.
     *
     * <p>Aiming applies {@code aim_multiplier}, crouching applies {@code crouch_multiplier}, and when both hold the two
     * are multiplied together - so {@code aim_multiplier 0.6} with {@code crouch_multiplier 0.8} yields {@code 0.48}.
     * Neither multiplier is applied to the weapon-model kickback: that layer keeps using
     * {@link #kickbackBlocks()} unchanged.</p>
     *
     * @param aiming    whether the player is currently aiming down the sights
     * @param crouching whether the player is currently crouching
     * @return the factor in {@code [0, 1]}, {@code 1.0} when neither applies
     */
    public double impulseScale(boolean aiming, boolean crouching) {
        double scale = 1.0;
        if (aiming) {
            scale *= aimMultiplier;
        }
        if (crouching) {
            scale *= crouchMultiplier;
        }
        return scale;
    }

    /**
     * Builds one accepted shot's camera impulse.
     *
     * <p>The magnitude is the existing per-shot value - {@code vertical_recoil} on the pitch channel and the selected
     * horizontal direction on the yaw channel - scaled by {@link #impulseScale(boolean, boolean)}. The direction is drawn
     * once, here, so the impulse is a fixed value from this moment on. The response also carries this weapon's
     * {@code smoothing} and {@link #recoverySeconds() recovery interval}, which is how the tuning properties reach
     * {@link PlayerRecoilController} without that controller having to know anything about profiles.</p>
     *
     * @param random    the client's shot randomness, advanced once per accepted shot
     * @param aiming    whether the player is aiming down the sights at the moment of the shot
     * @param crouching whether the player is crouching at the moment of the shot
     * @return the impulse to apply, never {@code null}
     */
    public ViewRecoilResponse viewRecoilImpulse(Random random, boolean aiming, boolean crouching) {
        double scale = impulseScale(aiming, crouching);
        return new ViewRecoilResponse(verticalDegrees * scale, horizontalDirection(random) * scale, smoothing,
                recoverySeconds());
    }

    /**
     * @return the seconds the accumulated offset of this weapon takes to normalise back to neutral, i.e.
     *         {@link PlayerRecoilController#RECOVERY_SECONDS} divided by {@code recovery_speed}. {@code 1.0} therefore
     *         keeps the baseline {@code 0.8 s}, {@code 0.5} takes {@code 1.6 s} and {@code 2.0} takes {@code 0.4 s}.
     */
    public double recoverySeconds() {
        return PlayerRecoilController.RECOVERY_SECONDS / recoverySpeed;
    }

    /**
     * Picks the signed horizontal offset of one accepted shot.
     *
     * <p>Both directions configured: the side is drawn at random, so sustained fire can walk either way, and the two
     * configured magnitudes stay independent - a weapon whose right value is larger really does recoil harder to the
     * right. Only one configured: that side is always used, so a one-sided configuration always recoils instead of
     * sometimes doing nothing. Neither: no sideways offset. A legacy symmetrical {@code horizontal_recoil} resolves to
     * the same magnitude for both directions before this method is reached.</p>
     *
     * <p>The decision lives here, next to the values it selects between, so the JSON semantics can be tested without a
     * client; {@link WeaponRecoil} only supplies the shot randomness.</p>
     *
     * @param random the client's shot randomness, advanced once per accepted shot; {@code null} selects the rightward
     *               direction when both are configured
     * @return the yaw offset in degrees of one shot, positive to the right and negative to the left
     */
    public double horizontalDirection(Random random) {
        if (horizontalLeftDegrees <= 0.0 && horizontalRightDegrees <= 0.0) {
            return 0.0;
        }
        if (horizontalLeftDegrees <= 0.0) {
            return horizontalRightDegrees;
        }
        if (horizontalRightDegrees <= 0.0) {
            return -horizontalLeftDegrees;
        }
        boolean right = random == null || random.nextBoolean();
        return right ? horizontalRightDegrees : -horizontalLeftDegrees;
    }

    /**
     * The {@code sustained_firing_Recoil} block, preserved as parsed data.
     *
     * <p>These values are read from the content pack and exposed here so nothing is lost and a later feature can use them,
     * but nothing in this build applies them to the weapon transform: the weapon-model kickback is driven by
     * {@code kickback_recoil_amplitude} alone. See {@link WeaponKickbackController} for why.</p>
     *
     * @param positionZIncrement backward weapon displacement the block describes for every accepted shot, in blocks
     * @param positionZCap       the block's own ceiling of accumulated backward displacement, in blocks
     * @param positionYIncrement downward weapon displacement the block describes for every accepted shot, in blocks
     * @param pitchIncrement     upward weapon pitch the block describes for every accepted shot, in degrees
     */
    public record Sustained(double positionZIncrement, double positionZCap, double positionYIncrement,
                            double pitchIncrement) {

        /** No sustained term, which is what a weapon without the block gets. */
        public static final Sustained NONE = new Sustained(0.0, 0.0, 0.0, 0.0);

        /** Ceiling of the accumulated vertical offset when the schema does not declare one, in blocks. */
        public static final double DEFAULT_POSITION_Y_CAP = 0.05;

        /** Ceiling of the accumulated weapon pitch when the schema does not declare one, in degrees. */
        public static final double DEFAULT_PITCH_CAP = 1.5;

        /** The largest accumulated backward displacement any configuration may reach, in blocks. */
        public static final double MAX_POSITION_Z_CAP = 1.0;

        public Sustained {
            positionZIncrement = clamp(positionZIncrement, 0.0, MAX_KICKBACK_AMPLITUDE_BLOCKS);
            positionZCap = clamp(positionZCap, 0.0, MAX_POSITION_Z_CAP);
            positionYIncrement = clamp(positionYIncrement, -MAX_KICKBACK_AMPLITUDE_BLOCKS,
                    MAX_KICKBACK_AMPLITUDE_BLOCKS);
            pitchIncrement = clamp(pitchIncrement, -MAX_VIEW_IMPULSE_DEGREES, MAX_VIEW_IMPULSE_DEGREES);
        }

        /**
         * @param configured the parsed block, may be {@code null}
         * @return the sanitised block, never {@code null}
         */
        public static Sustained of(WeaponDefinition.SustainedRecoil configured) {
            if (configured == null) {
                return NONE;
            }
            double zIncrement = value(configured.positionZIncrement());
            double zCap = value(configured.positionZCap());
            if (zIncrement > 0.0 && zCap <= 0.0) {
                // An increment without a cap keeps a bounded value instead of climbing forever.
                zCap = Math.min(MAX_POSITION_Z_CAP, zIncrement * 8.0);
            }
            return new Sustained(zIncrement, zCap, value(configured.positionYIncrement()),
                    value(configured.pitchIncrement()));
        }

        /** @return whether this block would move the weapon at all. */
        public boolean isActive() {
            return positionZIncrement != 0.0 || positionYIncrement != 0.0 || pitchIncrement != 0.0;
        }

        /**
         * The ceiling of the accumulated vertical offset.
         *
         * <p>The ARG schema declares a cap only for the backward channel, so this one is derived from the authored
         * increment instead of being invented: four shots' worth, bounded by the documented maximum, so the term builds
         * up over a burst instead of saturating after the first round.</p>
         *
         * @return the ceiling in blocks, never negative
         */
        public double positionYCap() {
            return Math.min(0.5, Math.max(DEFAULT_POSITION_Y_CAP, Math.abs(positionYIncrement) * 4.0));
        }

        /**
         * The ceiling of the accumulated weapon pitch, derived the same way as {@link #positionYCap()}.
         *
         * @return the ceiling in degrees, never negative
         */
        public double pitchCap() {
            return Math.min(15.0, Math.max(DEFAULT_PITCH_CAP, Math.abs(pitchIncrement) * 4.0));
        }
    }
}
