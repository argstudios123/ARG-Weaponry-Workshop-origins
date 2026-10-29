package com.arg_studios.arg_weaponry.weapon;

import com.arg_studios.arg_weaponry.weapon.state.FireMode;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed weapon registry definition.
 *
 * <p>This is a read-only data model: it mirrors the JSON schema that already exists in the content
 * packs and implements no gameplay behaviour. Property names keep the schema's own spelling
 * (including the {@code screen_shake_Recoil_value} style capitalisation) so creators and the model
 * stay in sync.</p>
 *
 * <p>Optional properties are {@code null} (or an empty collection) when absent; nothing is defaulted
 * or invented, and the source JSON is never modified.</p>
 *
 * @param fileIdentity              identity derived from the file location, for example
 *                                  {@code arg_pack:weapons/dmrs/m100_dmr}; used for diagnostics and
 *                                  resource context, never for item registration
 * @param authoritativeId           the authoritative weapon identity taken from a valid
 *                                  {@code weapon_id}, or {@code null} when it is missing or invalid;
 *                                  item registration uses exactly this value
 * @param declaredWeaponId          the raw {@code weapon_id} value as written, or {@code null}
 * @param name                      {@code weapon_name}
 * @param type                      {@code type}, one of {@link WeaponType}
 * @param display                   {@code display}, the display-configuration resource
 * @param ammoToUse                 {@code ammo_to_use}: the ammunition-compatibility contract, exactly as
 *                                  authored. An entry written as {@code namespace:path} names a Minecraft
 *                                  item and is the only form this build can match against inventory stacks;
 *                                  any other entry is a bare name that is preserved verbatim and currently
 *                                  resolves to nothing (see
 *                                  {@link com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionEntries}). For a
 *                                  weapon whose {@code ammo_capacity} is {@code "unlimited"} the list is kept as
 *                                  <em>metadata only</em>: firing consumes nothing and such a weapon never reloads,
 *                                  so it is never consulted for either, and it is not required to resolve to an
 *                                  ammunition item
 * @param damage                    {@code damage}
 * @param ammoCapacity              {@code ammo_capacity}; required for firearms, {@code null} otherwise. A finite
 *                                  positive integer is a magazine size; the reserved string {@code "unlimited"}
 *                                  selects unlimited ammunition, see {@link AmmoCapacity}
 * @param firingModes               {@code firing_mode}: the firing modes this weapon supports, in the authored
 *                                  order, mapped from the JSON tokens {@code semi}/{@code auto}/{@code burst} onto
 *                                  {@link FireMode}. Empty only for a hand-built definition; the parser always
 *                                  declares at least {@link FireMode#SINGLE}. Which of them a stack is using is
 *                                  stored in {@code WeaponState#fireMode()}, never here
 * @param burstCount                {@code burst_count}: how many shots one burst fires, between
 *                                  {@link #MIN_BURST_COUNT} and {@link #MAX_BURST_COUNT}; {@link #DEFAULT_BURST_COUNT}
 *                                  when the property is absent
 * @param continuousBurst           {@code continuous_burst}: whether a new burst starts automatically while the
 *                                  trigger stays held; {@code false} when absent
 * @param timeBetweenBursts         {@code time_between_bursts}: the delay in <em>seconds</em> between the end of
 *                                  one burst and the start of the next one, when {@code continuous_burst} is on;
 *                                  {@link #DEFAULT_TIME_BETWEEN_BURSTS} when absent
 * @param rpm                       {@code rpm}; required for firearms, {@code null} otherwise
 * @param aimZoom                   {@code aim_zoom}, or {@code null}
 * @param recoil                    the flat recoil properties, or {@code null} when none are declared
 * @param customHudUi               {@code custom_hud_ui}, raw value; the convention is not settled
 * @param aiming                    {@code aiming}, or {@code null}
 * @param reusableDefaultAnimations {@code reusable_default_animations}, raw values
 * @param glowingWeaponParts        {@code glowing_weapon_parts}
 * @param sounds                    {@code sounds}; original keys preserved so creators can add keys
 * @param accuracy                  {@code accuracy}, or {@code null}
 * @param sustainedFiringRecoil     {@code sustained_firing_Recoil}, or {@code null}
 * @param idleAnimations            {@code idle_anims}
 * @param holsterAnimations         {@code holster_anims}
 * @param drawAnimations            {@code draw_anims}
 * @param inspectAnimations         {@code inspect_anims}; the optional inspection-animation selection. Empty means
 *                                  inspection is not supported for this weapon, and pressing the inspect key then does
 *                                  nothing at all - no animation, no sound and no fallback to another clip
 * @param reloadSequences           {@code reload_sequences}
 * @param fireAnimations            {@code fire_anims}; the firing-animation selection, empty when absent
 * @param range                     {@code range} in blocks for a hitscan shot, or {@code null} when the
 *                                  weapon does not declare one (see {@link #fireRange()})
 * @param source                    the parsed source document, kept for later milestones
 */
public record WeaponDefinition(
        ResourceId fileIdentity,
        ResourceId authoritativeId,
        String declaredWeaponId,
        String name,
        WeaponType type,
        ResourceId display,
        List<String> ammoToUse,
        double damage,
        AmmoCapacity ammoCapacity,
        List<FireMode> firingModes,
        int burstCount,
        boolean continuousBurst,
        double timeBetweenBursts,
        Integer rpm,
        Double aimZoom,
        Recoil recoil,
        String customHudUi,
        Aiming aiming,
        List<String> reusableDefaultAnimations,
        List<GlowingPart> glowingWeaponParts,
        Map<String, ResourceId> sounds,
        Accuracy accuracy,
        SustainedRecoil sustainedFiringRecoil,
        List<AnimationSelection> idleAnimations,
        List<AnimationSelection> holsterAnimations,
        List<DrawSelection> drawAnimations,
        List<AnimationSelection> inspectAnimations,
        List<ReloadStep> reloadSequences,
        List<AnimationSelection> fireAnimations,
        Double range,
        JsonObject source
) {

    /**
     * The range a weapon without a declared {@code range} uses, in blocks.
     *
     * <p>Deliberately modest: a weapon that does not state its reach gets a short, safe hitscan rather than
     * an arbitrarily long one. Content packs that want more declare it explicitly.</p>
     */
    public static final double DEFAULT_RANGE = 32.0;

    /** Shots in one burst when the definition does not declare {@code burst_count}. */
    public static final int DEFAULT_BURST_COUNT = 3;

    /**
     * Smallest accepted {@code burst_count}.
     *
     * <p>One shot is a valid "burst" and behaves like a semi-automatic shot that may repeat while the trigger is
     * held, so the lower bound is one rather than two.</p>
     */
    public static final int MIN_BURST_COUNT = 1;

    /**
     * Largest accepted {@code burst_count}.
     *
     * <p>A bound exists so a broken definition cannot schedule an unbounded run of shots: every burst shot is a full
     * authoritative shot with a hitscan and a damage application, and the scheduler fires at most one per server tick,
     * so 64 shots already span at least 64 ticks (3.2 s) at the fastest cadence and much longer at a realistic one.
     * Values outside the range are rejected by the parser instead of being clamped, so the creator sees the mistake.</p>
     */
    public static final int MAX_BURST_COUNT = 64;

    /**
     * Delay between two consecutive bursts, in seconds, when the definition does not declare
     * {@code time_between_bursts}.
     *
     * <p>Twenty milliseconds is the value the schema documents. It only matters when {@code continuous_burst} is on,
     * and it is <em>not</em> the interval between the shots inside a burst - that one is derived from {@code rpm}.</p>
     */
    public static final double DEFAULT_TIME_BETWEEN_BURSTS = 0.02;

    /** Largest accepted {@code time_between_bursts}, in seconds. */
    public static final double MAX_TIME_BETWEEN_BURSTS = 60.0;

    public WeaponDefinition {
        ammoToUse = List.copyOf(ammoToUse);
        firingModes = firingModes == null ? List.of() : List.copyOf(firingModes);
        if (burstCount < MIN_BURST_COUNT || burstCount > MAX_BURST_COUNT) {
            throw new IllegalArgumentException("burst_count must be between " + MIN_BURST_COUNT + " and "
                    + MAX_BURST_COUNT + " but is " + burstCount);
        }
        if (!Double.isFinite(timeBetweenBursts) || timeBetweenBursts < 0.0
                || timeBetweenBursts > MAX_TIME_BETWEEN_BURSTS) {
            throw new IllegalArgumentException("time_between_bursts must be between 0 and "
                    + MAX_TIME_BETWEEN_BURSTS + " seconds but is " + timeBetweenBursts);
        }
        reusableDefaultAnimations = List.copyOf(reusableDefaultAnimations);
        glowingWeaponParts = List.copyOf(glowingWeaponParts);
        idleAnimations = List.copyOf(idleAnimations);
        holsterAnimations = List.copyOf(holsterAnimations);
        drawAnimations = List.copyOf(drawAnimations);
        inspectAnimations = inspectAnimations == null ? List.of() : List.copyOf(inspectAnimations);
        reloadSequences = List.copyOf(reloadSequences);
        fireAnimations = List.copyOf(fireAnimations);
        sounds = Collections.unmodifiableMap(new LinkedHashMap<>(sounds));
    }

    /**
     * @return the hitscan range in blocks: the declared {@code range}, or {@link #DEFAULT_RANGE} when the
     *         definition does not declare one
     */
    public double fireRange() {
        return range == null ? DEFAULT_RANGE : range;
    }

    // --- nested configuration records ---

    /**
     * The {@code aiming} block.
     *
     * <p>Missing sub-properties keep the schema's implied {@code false}, which is also what a weapon with no {@code aiming}
     * block at all gets: no zoom and no new restrictions. That is deliberate - the restrictions are opt-in, so adding this
     * milestone cannot change the behaviour of a weapon that never asked for one.</p>
     *
     * @param enabled           whether aim-down-sights may be entered for this weapon
     * @param noAimingOnInspect whether aiming is refused while the weapon is being inspected
     * @param noAimingOnReload  whether aiming is refused while the weapon is being reloaded
     * @param zoom              the field-of-view multiplier at full ADS; {@code 1.0} means "no zoom",
     *                          {@code 0.5} means "half the field of view"; always a valid value, see
     *                          {@code AimZoom#sanitise(double)}
     * @param zoomType          the configured easing name as written in the file, or {@code null} when the weapon declared
     *                          none; the parsed curve is resolved by {@link #zoomCurve()}
     */
    public record Aiming(boolean enabled, boolean noAimingOnInspect, boolean noAimingOnReload, double zoom,
                         String zoomType) {

        /** Backwards-compatible construction of a block that declares no zoom. */
        public Aiming(boolean enabled, boolean noAimingOnInspect, boolean noAimingOnReload) {
            this(enabled, noAimingOnInspect, noAimingOnReload, 1.0, null);
        }

        /**
         * @return the easing curve of the zoom transition; {@code linear} when the weapon declared none or an unknown name
         */
        public com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve zoomCurve() {
            return com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve.parse(zoomType);
        }
    }

    /**
     * The flat recoil properties. All values are optional; names follow the existing schema,
     * including {@code screen_shake_Recoil_value}.
     */
    public record Recoil(Double kickbackAmplitude, Double screenShakeValue, Double screenShakeSpeed,
                         Double vertical, Double horizontal, Double horizontalLeft, Double horizontalRight) {

        /** Backwards-compatible construction of a configuration without the directional horizontal values. */
        public Recoil(Double kickbackAmplitude, Double screenShakeValue, Double screenShakeSpeed,
                      Double vertical, Double horizontal) {
            this(kickbackAmplitude, screenShakeValue, screenShakeSpeed, vertical, horizontal, null, null);
        }

        public boolean isEmpty() {
            return kickbackAmplitude == null && screenShakeValue == null && screenShakeSpeed == null
                    && vertical == null && horizontal == null && horizontalLeft == null && horizontalRight == null;
        }

        /**
         * Resolves the configured magnitude of one horizontal direction.
         *
         * <p>The precedence is fixed and documented: an explicitly configured directional value always wins, and the legacy
         * symmetrical {@code horizontal_recoil} is only consulted when neither direction is configured. A weapon that
         * configures only one direction therefore still recoils, and one that configures only the legacy value keeps its
         * original symmetric behaviour.</p>
         *
         * @param left {@code true} for the leftward direction
         * @return the configured magnitude, or {@code 0} when that direction is not configured at all
         */
        public double horizontalMagnitude(boolean left) {
            if (horizontalLeft == null && horizontalRight == null) {
                return horizontal == null ? 0.0 : Math.abs(horizontal);
            }
            Double directional = left ? horizontalLeft : horizontalRight;
            return directional == null ? 0.0 : Math.abs(directional);
        }

        /**
         * @return whether the legacy symmetrical value is the source of the horizontal behaviour
         */
        public boolean usesLegacyHorizontal() {
            return horizontalLeft == null && horizontalRight == null && horizontal != null;
        }
    }

    /** The {@code sustained_firing_Recoil} block, which uses camelCase keys in the existing schema. */
    public record SustainedRecoil(Double positionZCap, Double positionZIncrement, Double pitchIncrement,
                                  Double positionYIncrement) {
    }

    /** The {@code accuracy} block. */
    public record Accuracy(Double crouching, Double walking, Double standing, Double aiming) {
    }

    /** One entry of {@code glowing_weapon_parts}; the name is meant to reference a model bone. */
    public record GlowingPart(String name) {
    }

    /**
     * A conditionally selected animation, as used by {@code idle_anims} and {@code holster_anims}.
     *
     * @param name     the animation name inside the weapon's animation resource
     * @param duration the duration in seconds
     * @param when     the raw condition expression, preserved as data and never evaluated here
     */
    public record AnimationSelection(String name, double duration, String when) {
    }

    /**
     * One entry of {@code draw_anims}.
     *
     * @param name                     the animation name
     * @param duration                 the duration in seconds
     * @param when                     the raw condition expression, preserved as data
     * @param declaredWithDrawFirstKey {@code true} when the entry used {@code draw_first} instead of
     *                                 the established {@code name} property; reported as a warning
     *                                 and never rewritten
     */
    public record DrawSelection(String name, double duration, String when, boolean declaredWithDrawFirstKey) {
    }

    /**
     * One entry of {@code reload_sequences}.
     *
     * @param phase     the creator-defined phase name; kept as a string because the final phase
     *                  vocabulary is not decided yet
     * @param animation the animation name
     * @param duration  the duration in seconds
     * @param when      the raw condition expression, preserved as data
     * @param repeat    the optional {@code repeat} flag, parsed but not acted on yet; {@code null}
     *                  when absent
     */
    public record ReloadStep(String phase, String animation, double duration, String when, Boolean repeat) {
    }
}
