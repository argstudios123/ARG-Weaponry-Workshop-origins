package com.arg_studios.arg_weaponry.client.recoil;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

/**
 * The sanitised recoil values of one weapon, resolved once per accepted shot.
 *
 * <p>This is the only place that reads the JSON, so the three controllers below it never have to know whether a value
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
 *       <td>Upward view impulse of one accepted shot, applied to the player's own view rotation.</td></tr>
 *   <tr><td>{@code horizontal_recoil_left} / {@code horizontal_recoil_right}</td><td>degrees of yaw</td>
 *       <td>{@link PlayerRecoilController}</td>
 *       <td>Sideways view impulse of one accepted shot, chosen once per shot.</td></tr>
 *   <tr><td>{@code screen_shake_Recoil_value}</td><td>degrees</td><td>{@link ViewShakeController}</td>
 *       <td>Peak amplitude of the camera-only shake. It never moves the player's aim, and zero disables it.</td></tr>
 *   <tr><td>{@code screen_shake_recoil_speed}</td><td>per second</td><td>{@link ViewShakeController}</td>
 *       <td>Decay rate of that shake: the amplitude falls by a factor of {@code e} every {@code 1 / speed} seconds.</td></tr>
 * </table>
 *
 * <h2>Sustained fire</h2>
 * <p>{@code sustained_firing_Recoil} keeps its authored camelCase keys and its Point Blank meaning: a small per-shot
 * increment that accumulates while the trigger stays down and decays when it stops. It feeds the weapon-kickback
 * controller only - the view response accumulates by summing its per-shot responses, and the shake is a transient.</p>
 *
 * @param kickbackBlocks         backward weapon displacement of one shot, in blocks; only meaningful when
 *                               {@code kickbackEnabled} is true
 * @param kickbackEnabled        whether the weapon <em>configured</em> {@code kickback_recoil_amplitude} at all; this is
 *                               the enable switch, and it exists so a missing property can never be confused with a
 *                               configured zero
 * @param shakeDegrees           peak camera-only shake, in degrees
 * @param shakeSpeed             exponential decay rate of the shake, in per-second units
 * @param verticalDegrees        upward view impulse of one shot, in degrees of pitch
 * @param horizontalLeftDegrees  leftward view impulse of one shot, in degrees of yaw
 * @param horizontalRightDegrees rightward view impulse of one shot, in degrees of yaw
 * @param sustained              the {@code sustained_firing_Recoil} block, never {@code null}
 */
public record RecoilProfile(double kickbackBlocks, boolean kickbackEnabled, double shakeDegrees, double shakeSpeed,
                            double verticalDegrees, double horizontalLeftDegrees, double horizontalRightDegrees,
                            Sustained sustained) {

    /** The largest displacement one shot may request, in blocks. */
    public static final double MAX_KICKBACK_AMPLITUDE_BLOCKS = 2.0;

    /** The largest shake amplitude one configuration can reach, in degrees. */
    public static final double MAX_SHAKE_DEGREES = 8.0;

    /** The upper bound of one view impulse, in degrees, so a typo cannot spin the player around. */
    public static final double MAX_VIEW_IMPULSE_DEGREES = 45.0;

    /** Decay rate used when {@code screen_shake_recoil_speed} is absent or not positive, in per-second units. */
    public static final double DEFAULT_SHAKE_SPEED = 6.0;

    /** The neutral profile: no recoil at all, and kickback explicitly not configured. */
    public static final RecoilProfile NONE = new RecoilProfile(0.0, false, 0.0, 0.0, 0.0, 0.0, 0.0, Sustained.NONE);

    public RecoilProfile {
        kickbackBlocks = clamp(kickbackBlocks, 0.0, MAX_KICKBACK_AMPLITUDE_BLOCKS);
        // A pure presence flag: "the property was configured". It deliberately stays true for an explicit 0.0 so the
        // difference between "absent" and "configured zero" remains observable; hasKickback() is what decides whether any
        // movement happens.
        shakeDegrees = clamp(shakeDegrees, 0.0, MAX_SHAKE_DEGREES);
        shakeSpeed = Double.isFinite(shakeSpeed) && shakeSpeed > 0.0 ? Math.min(shakeSpeed, 100.0)
                : DEFAULT_SHAKE_SPEED;
        verticalDegrees = clamp(verticalDegrees, -MAX_VIEW_IMPULSE_DEGREES, MAX_VIEW_IMPULSE_DEGREES);
        horizontalLeftDegrees = clamp(horizontalLeftDegrees, 0.0, MAX_VIEW_IMPULSE_DEGREES);
        horizontalRightDegrees = clamp(horizontalRightDegrees, 0.0, MAX_VIEW_IMPULSE_DEGREES);
        sustained = sustained == null ? Sustained.NONE : sustained;
    }

    /**
     * Backwards-compatible construction for a configuration that declares a kickback amplitude (the pre-enable-flag
     * shape). The flag is derived from the amplitude, so this constructor can never claim "configured" with no value.
     */
    public RecoilProfile(double kickbackBlocks, double shakeDegrees, double shakeSpeed, double verticalDegrees,
                         double horizontalLeftDegrees, double horizontalRightDegrees, Sustained sustained) {
        this(kickbackBlocks, true, shakeDegrees, shakeSpeed, verticalDegrees, horizontalLeftDegrees,
                horizontalRightDegrees, sustained);
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
        WeaponDefinition.Recoil recoil = definition.recoil();
        Sustained sustained = Sustained.of(definition.sustainedFiringRecoil());
        if (recoil == null) {
            // No recoil object at all: nothing is configured, so kickback stays disabled even if a sustained block
            // happens to be present.
            return new RecoilProfile(0.0, false, 0.0, DEFAULT_SHAKE_SPEED, 0.0, 0.0, 0.0, sustained);
        }
        // The parser keeps an absent property as null, which is the only place the difference between "absent" and
        // "configured zero" exists. It is carried into the profile explicitly instead of being flattened to 0.0.
        boolean kickbackConfigured = recoil.kickbackAmplitude() != null;
        return new RecoilProfile(
                value(recoil.kickbackAmplitude()),
                kickbackConfigured,
                value(recoil.screenShakeValue()),
                value(recoil.screenShakeSpeed()),
                value(recoil.vertical()),
                recoil.horizontalMagnitude(true),
                recoil.horizontalMagnitude(false),
                sustained);
    }

    private static double value(Double configured) {
        return configured == null || !Double.isFinite(configured) ? 0.0 : configured;
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

    /** @return whether the weapon asks for any camera-only shake. */
    public boolean hasShake() {
        return shakeDegrees > 0.0;
    }

    /** @return whether this profile would do anything at all. */
    public boolean isNeutral() {
        return !hasKickback() && !hasViewRecoil() && !hasShake();
    }

    /**
     * The {@code sustained_firing_Recoil} block in the units this implementation uses.
     *
     * <p>The increments are per accepted shot and accumulate while the trigger stays down; they decay exponentially
     * once firing stops. {@code positionZCap} is the ceiling of the accumulated backward displacement, and the ARG
     * schema has no caps for the other two channels, so they use the documented constants below - the same shape as
     * Point Blank's default sustained caps.</p>
     *
     * @param positionZIncrement backward weapon displacement added by every accepted shot, in blocks
     * @param positionZCap       ceiling of the accumulated backward displacement, in blocks
     * @param positionYIncrement downward weapon displacement added by every accepted shot, in blocks
     * @param pitchIncrement     upward weapon pitch added by every accepted shot, in degrees
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


