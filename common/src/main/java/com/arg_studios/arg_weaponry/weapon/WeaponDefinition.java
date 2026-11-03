package com.arg_studios.arg_weaponry.weapon;

import com.arg_studios.arg_weaponry.weapon.animation.WeaponSwayStyle;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundKey;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundSet;
import com.arg_studios.arg_weaponry.weapon.reload.ReloadRules;
import com.arg_studios.arg_weaponry.weapon.state.FireMode;
import com.google.gson.JsonElement;
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
 * (including the {@code kickback_recoil_amplitude} and {@code horizontal_recoil_left} spellings) so
 * creators and the model stay in sync.</p>
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
 * @param glowingWeaponParts        {@code glowing_weapon_parts}: the parent bones whose whole bone hierarchy renders
 *                                  emissively. Empty means nothing glows, and the order is the authored order with
 *                                  duplicates already removed, so the resolver never visits the same bone twice
 * @param muzzleFlash               {@code muzzle_flash}, or {@code null} when the weapon declares no flash at all.
 *                                  A parsed block is not necessarily usable: see {@link MuzzleFlash#isUsable()}
 * @param sounds                    {@code sounds}; original keys preserved so creators can add keys. Every entry is the
 *                                  <em>primary</em> identifier of that key, which is exactly what a single-string
 *                                  declaration names; the complete description - every variant plus the tuning - is
 *                                  {@link #soundSets()}
 * @param soundSets                 the playable description of every declared sound key: its samples and the pitch and
 *                                  volume variation the engine may add. Always consistent with {@link #sounds()}: an
 *                                  entry that exists here exists there, with the same primary identifier. Empty only for a
 *                                  hand-built definition, in which case the audio engine falls back to
 *                                  {@link #sounds()} and plays every key at its declared pitch and volume
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
        MuzzleFlash muzzleFlash,
        Map<String, ResourceId> sounds,
        Map<String, WeaponSoundSet> soundSets,
        Accuracy accuracy,
        SustainedRecoil sustainedFiringRecoil,
        List<AnimationSelection> idleAnimations,
        List<AnimationSelection> holsterAnimations,
        List<DrawSelection> drawAnimations,
        List<AnimationSelection> inspectAnimations,
        List<ReloadStep> reloadSequences,
        List<AnimationSelection> fireAnimations,
        Double range,
        Sway sway,
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

    /**
     * Rounds inserted per shell-insertion cycle when the definition does not declare {@code shell_amount_reload}.
     *
     * <p>One round is the documented behaviour of a shell-by-shell reload and it is also the neutral value for every
     * existing definition: no pack written before the property existed declares it, and every one of them keeps its
     * single-transfer reload.</p>
     */
    public static final int DEFAULT_SHELL_AMOUNT_RELOAD = 1;

    /** Smallest accepted {@code shell_amount_reload}; a cycle that inserts nothing would stall the reload. */
    public static final int MIN_SHELL_AMOUNT_RELOAD = 1;

    /**
     * Largest accepted {@code shell_amount_reload}.
     *
     * <p>Bounded for the same reason as {@link #MAX_BURST_COUNT}: every cycle is a real inventory transfer and the
     * planner inserts at most one cycle per stage, so a broken value must not schedule an unbounded amount of work.
     * The bound is generous enough for every magazine this schema allows.</p>
     */
    public static final int MAX_SHELL_AMOUNT_RELOAD = 64;

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
        sway = sway == null ? Sway.DEFAULT : sway;
        sounds = Collections.unmodifiableMap(new LinkedHashMap<>(sounds));
        soundSets = soundSets == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(soundSets));
    }

    /**
     * @return the hitscan range in blocks: the declared {@code range}, or {@link #DEFAULT_RANGE} when the
     *         definition does not declare one
     */
    public double fireRange() {
        return range == null ? DEFAULT_RANGE : range;
    }

    /**
     * Whether firing is allowed while the player is sprinting.
     *
     * <p>The property is optional and defaults to {@code true}, which is exactly the behaviour every weapon had before
     * the property existed. It is read from the retained source document instead of becoming a record component, so a
     * hand-built definition (and every existing test helper) keeps the documented default without another constructor
     * argument. Only a JSON boolean is authoritative; a wrongly typed value was already reported by the parser and falls
     * back to {@code true} here.</p>
     *
     * @return {@code false} only when the pack explicitly wrote {@code "allow_firing_when_sprinting": false}
     */
    public boolean allowFiringWhenSprinting() {
        if (source == null) {
            return true;
        }
        JsonElement element = source.get("allow_firing_when_sprinting");
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()) {
            return true;
        }
        return element.getAsBoolean();
    }

    /**
     * The number of rounds one shell-insertion cycle of a reload moves.
     *
     * <p>{@code shell_amount_reload} is the shell-by-shell counterpart of a magazine transfer: a shotgun that can only
     * be fed one shell at a time declares {@code 1}, a weapon fed in pairs declares {@code 2}. The value is only a
     * <em>maximum</em> per cycle - the reload planner clamps it to the room left in the magazine and to the reserve
     * ammunition that is actually present, so a cycle can never insert more than fits or more than the player owns.</p>
     *
     * <p>Like {@link #allowFiringWhenSprinting()} the property is optional and is read from the retained source
     * document rather than becoming a record component, so a hand-built definition keeps the documented default without
     * another constructor argument. A wrongly typed or non-positive value was already reported by the parser and falls
     * back to {@link #DEFAULT_SHELL_AMOUNT_RELOAD} here.</p>
     *
     * @return the declared amount per insertion cycle, between {@value #MIN_SHELL_AMOUNT_RELOAD} and
     *         {@value #MAX_SHELL_AMOUNT_RELOAD}
     */
    public int shellAmountReload() {
        if (source == null) {
            return DEFAULT_SHELL_AMOUNT_RELOAD;
        }
        JsonElement element = source.get("shell_amount_reload");
        if (element == null || element.isJsonNull()) {
            return DEFAULT_SHELL_AMOUNT_RELOAD;
        }
        Integer declared = com.arg_studios.arg_weaponry.json.JsonValues.asInt(element);
        if (declared == null || declared < MIN_SHELL_AMOUNT_RELOAD || declared > MAX_SHELL_AMOUNT_RELOAD) {
            return DEFAULT_SHELL_AMOUNT_RELOAD;
        }
        return declared;
    }

    /**
     * Resolves the playable description of one sound key.
     *
     * <p>The declared {@link #soundSets()} entry wins. A definition that only has {@link #sounds()} - a hand-built
     * definition in a test, or any definition built before the variation system existed - gets a single-sample set with the
     * tuning of its key, so a caller never has to special-case the two shapes and no definition can lose its sound.</p>
     *
     * @param key the action, may be {@code null}
     * @return the sound set, or {@code null} when this weapon declares nothing for that key
     */
    public WeaponSoundSet soundSet(WeaponSoundKey key) {
        if (key == null) {
            return null;
        }
        WeaponSoundSet declared = soundSets.get(key.jsonName());
        if (declared != null) {
            return declared;
        }
        ResourceId single = sounds.get(key.jsonName());
        return single == null ? null : WeaponSoundSet.single(key, single);
    }

    // --- nested configuration records ---

    /**
     * The ADS constraint: how much of the weapon's animated {@code main} pose is retained while aiming.
     *
     * <p>The retained fraction is read from the model's {@code ads_offset} bone exactly like the reference
     * implementation: its authored plus animated rotation (in degrees) and its position set the retention per axis,
     * clamped into {@code [0, 1]}. A retention of {@code 1} keeps the animation untouched, {@code 0} removes it at full
     * ADS. When {@code camera_constraint_modifier} is declared it is used for the camera bone on all six axes instead;
     * {@code 0.7} therefore retains 70 % of the camera movement and removes 30 % at full ADS.</p>
     *
     * @param enabled                  whether the constraint runs at all
     * @param camera                   whether the {@code camera} bone is constrained as well
     * @param cameraConstraintModifier the camera retention override, or {@code NaN} when the bone-derived values are used
     */
    public record AdsConstraint(boolean enabled, boolean camera, double cameraConstraintModifier) {

        /** The neutral configuration: disabled, camera correction on, no override. */
        public static final AdsConstraint DISABLED = new AdsConstraint(false, true, Double.NaN);

        public AdsConstraint {
            if (Double.isFinite(cameraConstraintModifier)) {
                cameraConstraintModifier = Math.max(0.0, Math.min(1.0, cameraConstraintModifier));
            } else {
                cameraConstraintModifier = Double.NaN;
            }
        }

        /** @return whether the camera modifier was declared; otherwise the {@code ads_offset} bone is authoritative. */
        public boolean hasCameraConstraint() {
            return Double.isFinite(cameraConstraintModifier);
        }
    }

    /**
     * The additional zoom applied while the player holds ADS during a reload.
     *
     * <p>This is not an absolute field of view. It follows the reference implementation's meaning: at full effect the
     * aiming zoom multiplier is multiplied by {@code 1 - zoomAmount}, so {@code 0.06} removes 6 % of the current zoom
     * distance rather than setting a zoom of {@code 0.06}. The value is clamped into {@code [0, 1]}.</p>
     *
     * @param enabled        whether the effect runs at all
     * @param zoomEffectType the easing name; {@code smooth} when absent or unknown
     * @param zoomAmount     the fraction of the current zoom removed at full effect, {@code 0} when absent
     */
    public record AdsReloadZoom(boolean enabled, String zoomEffectType, double zoomAmount) {

        /** The easing used when the weapon declares none, matching the reference implementation. */
        public static final String DEFAULT_EFFECT_TYPE = "smooth";

        /** The neutral configuration. */
        public static final AdsReloadZoom DISABLED = new AdsReloadZoom(false, DEFAULT_EFFECT_TYPE, 0.0);

        public AdsReloadZoom {
            zoomEffectType = zoomEffectType == null || zoomEffectType.isBlank()
                    ? DEFAULT_EFFECT_TYPE : zoomEffectType.trim();
            if (!Double.isFinite(zoomAmount) || zoomAmount <= 0.0) {
                zoomAmount = 0.0;
            } else {
                zoomAmount = Math.min(1.0, zoomAmount);
            }
        }

        /**
         * @return the easing curve of the reload zoom envelope; {@code smooth} when the declared name is unknown, which
         *         is the reference implementation's fallback and never disables the effect
         */
        public com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve curve() {
            com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve known =
                    com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve.lookup(zoomEffectType);
            return known == null ? com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve.SMOOTH : known;
        }

        /** @return whether the effect can change the field of view. */
        public boolean hasEffect() {
            return enabled && zoomAmount > 0.0;
        }
    }

    /**
     * The {@code aiming} block.
     *
     * <p>Missing sub-properties keep the schema's implied {@code false}, which is also what a weapon with no {@code aiming}
     * block at all gets: no zoom and no new restrictions. The two optional sub-blocks are
     * {@link AdsConstraint} ({@code ads_constraint}) and {@link AdsReloadZoom} ({@code ads_reload_zoom_control}).</p>
     *
     * <h2>Timing</h2>
     * <p>{@code aiming_speed} scales the rate at which the aiming placement is entered and exited, and
     * {@code aiming_zoom_speed} does the same for the field-of-view transition. {@code 1.0} is the framework base rate;
     * {@code aiming_animation_type} and {@code aiming_zoom_type} name their easing curves.</p>
     *
     * @param enabled           whether aim-down-sights may be entered for this weapon
     * @param noAimingOnInspect whether aiming is refused while the weapon is being inspected
     * @param noAimingOnReload  whether aiming is refused while the weapon is being reloaded
     * @param zoom              the field-of-view multiplier at full ADS; {@code 1.0} means "no zoom"
     * @param zoomType          the easing name of the zoom transition, or {@code null} when none was declared
     * @param animationType     the easing name of the aiming placement transition, or {@code null} when none was declared
     * @param animationSpeed    the rate multiplier of the aiming placement transition, {@code 1.0} when absent
     * @param zoomSpeed         the rate multiplier of the zoom transition, {@code 1.0} when absent
     * @param aimIn             the optional {@code aim_in} animation identifier, or {@code null}
     * @param aimOut            the optional {@code aim_out} animation identifier, or {@code null}
     * @param adsConstraint     the optional {@code ads_constraint} block, never {@code null}
     * @param adsReloadZoom     the optional {@code ads_reload_zoom_control} block, never {@code null}
     */
    public record Aiming(boolean enabled, boolean noAimingOnInspect, boolean noAimingOnReload, double zoom,
                         String zoomType, String animationType, double animationSpeed, double zoomSpeed,
                         String aimIn, String aimOut, AdsConstraint adsConstraint, AdsReloadZoom adsReloadZoom) {

        /** The documented speed multiplier of a weapon that declares neither speed. */
        public static final double DEFAULT_SPEED = 1.0;

        /** Slowest accepted speed multiplier; below this a transition would last longer than a reload. */
        public static final double MIN_SPEED = 0.05;

        /** Fastest accepted speed multiplier; above this a transition is a snap, not a transition. */
        public static final double MAX_SPEED = 20.0;

        public Aiming {
            zoom = com.arg_studios.arg_weaponry.weapon.aiming.AimZoom.sanitise(zoom);
            animationSpeed = sanitiseSpeed(animationSpeed);
            zoomSpeed = sanitiseSpeed(zoomSpeed);
            zoomType = blankToNull(zoomType);
            animationType = blankToNull(animationType);
            aimIn = blankToNull(aimIn);
            aimOut = blankToNull(aimOut);
            adsConstraint = adsConstraint == null ? AdsConstraint.DISABLED : adsConstraint;
            adsReloadZoom = adsReloadZoom == null ? AdsReloadZoom.DISABLED : adsReloadZoom;
        }

        /**
         * Backwards-compatible construction of a block that declares neither ADS extras.
         *
         * @param enabled           whether aiming is enabled
         * @param noAimingOnInspect whether inspection refuses aiming
         * @param noAimingOnReload  whether reloading refuses aiming
         * @param zoom              the field-of-view multiplier at full ADS
         * @param zoomType          the zoom easing name, may be {@code null}
         * @param animationType     the placement easing name, may be {@code null}
         * @param animationSpeed    the placement transition rate
         * @param zoomSpeed         the zoom transition rate
         * @param aimIn             the optional {@code aim_in} clip, may be {@code null}
         * @param aimOut            the optional {@code aim_out} clip, may be {@code null}
         */
        public Aiming(boolean enabled, boolean noAimingOnInspect, boolean noAimingOnReload, double zoom,
                      String zoomType, String animationType, double animationSpeed, double zoomSpeed,
                      String aimIn, String aimOut) {
            this(enabled, noAimingOnInspect, noAimingOnReload, zoom, zoomType, animationType, animationSpeed,
                    zoomSpeed, aimIn, aimOut, AdsConstraint.DISABLED, AdsReloadZoom.DISABLED);
        }

        /** Backwards-compatible construction of a block that declares no zoom and no timing. */
        public Aiming(boolean enabled, boolean noAimingOnInspect, boolean noAimingOnReload) {
            this(enabled, noAimingOnInspect, noAimingOnReload, 1.0, null, null, DEFAULT_SPEED, DEFAULT_SPEED,
                    null, null);
        }

        /** Backwards-compatible construction of a block that declares a zoom but no timing. */
        public Aiming(boolean enabled, boolean noAimingOnInspect, boolean noAimingOnReload, double zoom,
                      String zoomType) {
            this(enabled, noAimingOnInspect, noAimingOnReload, zoom, zoomType, null, DEFAULT_SPEED, DEFAULT_SPEED,
                    null, null);
        }

        /**
         * @return the easing curve of the zoom transition; {@code linear} when the weapon declared none or an unknown
         *         name
         */
        public com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve zoomCurve() {
            return com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve.parse(zoomType);
        }

        /**
         * @return the easing curve of the aiming placement transition; {@code linear} when none or an unknown name was
         *         declared
         */
        public com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve animationCurve() {
            return com.arg_studios.arg_weaponry.weapon.aiming.ZoomCurve.parse(animationType);
        }

        /** @return whether an {@code aim_in} clip was declared. */
        public boolean hasAimIn() {
            return aimIn != null;
        }

        /** @return whether an {@code aim_out} clip was declared. */
        public boolean hasAimOut() {
            return aimOut != null;
        }

        /**
         * @param value the configured multiplier; may be absent, non-finite or out of range
         * @return a usable multiplier, {@link #DEFAULT_SPEED} when the value is missing or nonsensical
         */
        private static double sanitiseSpeed(double value) {
            if (!Double.isFinite(value) || value <= 0.0) {
                return DEFAULT_SPEED;
            }
            return Math.max(MIN_SPEED, Math.min(MAX_SPEED, value));
        }

        /**
         * @param value a configured identifier
         * @return the trimmed identifier, or {@code null} when it is absent or blank
         */
        private static String blankToNull(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    /**
     * The weapon sway configuration: how the weapon follows the player's mouse movement.
     *
     * <p>Sway is a purely additive view-model transform. It never touches the animation pose, so it composes with idle,
     * walking, running, aim-down-sights, firing and every other clip - exactly like the recoil kickback it sits next to. All
     * properties are optional and every one of them falls back to {@link #DEFAULT}, so a content pack that does not declare
     * {@code weapons_sway} keeps a natural, subtle sway instead of none.</p>
     *
     * <h2>Intensity and sensitivity are different controls</h2>
     * <p>{@code sway_sensitivity} is the input gain: it decides how much weapon rotation one degree per second of camera
     * movement produces, and therefore how easily a movement reaches the style's rotation ceiling. {@code intensity} scales
     * the <em>output</em> - the rotation and the translation the renderer receives - so it changes how far the weapon
     * visibly moves without changing how quickly it saturates. A high sensitivity with a low intensity is a responsive but
     * small sway; a low sensitivity with a high intensity is a slow sway that can still travel far.</p>
     *
     * @param enabled        whether the weapon uses sway at all
     * @param style          the sway style; {@link WeaponSwayStyle#VANILLA} when absent or unknown
     * @param intensity      output amplitude; {@code 1.0} is the authored baseline, {@code 0} disables the effect
     * @param smoothing      how quickly the weapon follows the mouse, in inverse seconds; larger is snappier
     * @param sensitivity    input gain: how strongly the camera's movement contributes to the sway
     * @param returnSpeed    how quickly the weapon returns to rest when the mouse stops, in inverse seconds
     * @param aimMultiplier  multiplier on the sway while aiming down sights; {@code 1.0} keeps hip-fire strength,
     *                       below {@code 1.0} is steadier and above it is heavier
     */
    public record Sway(boolean enabled, WeaponSwayStyle style, double intensity, double smoothing, double sensitivity,
                       double returnSpeed, double aimMultiplier) {

        private static final double DEFAULT_INTENSITY = 1.0;
        private static final double DEFAULT_SMOOTHING = 5.0;
        private static final double DEFAULT_SENSITIVITY = 3.0;
        private static final double DEFAULT_RETURN_SPEED = 6.0;
        private static final double DEFAULT_AIM_MULTIPLIER = 0.3;

        /** The documented defaults, used by every property a pack does not declare. */
        public static final Sway DEFAULT = new Sway(true, WeaponSwayStyle.VANILLA, DEFAULT_INTENSITY, DEFAULT_SMOOTHING,
                DEFAULT_SENSITIVITY, DEFAULT_RETURN_SPEED, DEFAULT_AIM_MULTIPLIER);

        /** Largest accepted {@code intensity}; a broken definition cannot make the weapon leave the screen. */
        public static final double MAX_INTENSITY = 10.0;

        /** Largest accepted {@code sway_sensitivity}; above this every movement saturates the style's ceiling. */
        public static final double MAX_SENSITIVITY = 20.0;

        /** Smallest accepted {@code smoothing} / {@code return_speed}; zero would freeze the spring. */
        public static final double MIN_RATE = 0.05;

        /** Largest accepted rate, so a broken definition cannot make the weapon jitter. */
        public static final double MAX_RATE = 60.0;

        /** Largest accepted {@code aim_multiplier}; above {@code 1} aiming simply sways more than hip fire. */
        public static final double MAX_AIM_MULTIPLIER = 10.0;

        public Sway {
            style = style == null ? WeaponSwayStyle.VANILLA : style;
            intensity = Double.isFinite(intensity) ? Math.max(0.0, Math.min(MAX_INTENSITY, intensity))
                    : DEFAULT_INTENSITY;
            smoothing = clampRate(smoothing, DEFAULT_SMOOTHING);
            returnSpeed = clampRate(returnSpeed, DEFAULT_RETURN_SPEED);
            sensitivity = Double.isFinite(sensitivity) ? Math.max(0.0, Math.min(MAX_SENSITIVITY, sensitivity))
                    : DEFAULT_SENSITIVITY;
            aimMultiplier = Double.isFinite(aimMultiplier)
                    ? Math.max(0.0, Math.min(MAX_AIM_MULTIPLIER, aimMultiplier)) : DEFAULT_AIM_MULTIPLIER;
        }

        /**
         * The pre-style form, kept so a hand-built definition (and the existing tests) still compiles.
         *
         * @param enabled       whether the weapon uses sway at all
         * @param intensity     output amplitude
         * @param smoothing     follow rate
         * @param returnSpeed   return rate
         * @param aimMultiplier sway multiplier while aiming
         */
        public Sway(boolean enabled, double intensity, double smoothing, double returnSpeed, double aimMultiplier) {
            this(enabled, WeaponSwayStyle.VANILLA, intensity, smoothing, DEFAULT_SENSITIVITY, returnSpeed,
                    aimMultiplier);
        }

        private static double clampRate(double value, double fallback) {
            if (!Double.isFinite(value)) {
                return fallback;
            }
            return Math.max(MIN_RATE, Math.min(MAX_RATE, value));
        }

        /** @return whether the effect is switched on and actually strong enough to be visible. */
        public boolean active() {
            return enabled && intensity > 0.0 && sensitivity > 0.0;
        }
    }

    /**
     * The flat recoil properties. All values are optional; names follow the existing schema,
     * including the per-direction {@code horizontal_recoil_left} / {@code horizontal_recoil_right}.
     *
     * <p>The retired {@code screen_shake_Recoil_value} and {@code screen_shake_recoil_speed} properties are not part of
     * this model any more and have no replacement here. The parser still tolerates both keys in a content pack (see
     * {@link WeaponDefinitionParser}), but nothing in this record can carry them any further, so no code path can turn a
     * value in an old pack into a camera effect.</p>
     *
     * <p>The four tuning properties are also optional and are carried verbatim from the JSON; their defaults and their
     * limits are applied by {@link com.arg_studios.arg_weaponry.client.recoil.RecoilProfile}, the same place the existing
     * values are sanitised, so a definition always reports exactly what the pack wrote:</p>
     * <ul>
     *   <li>{@code smoothing} - share of the remaining camera-recoil offset applied in one 50 ms reference interval;</li>
     *   <li>{@code recovery_speed} - multiplier on the accumulated-recoil recovery rate, {@code 1.0} being the baseline;</li>
     *   <li>{@code aim_multiplier} - multiplier on the per-shot camera-recoil impulse while aiming;</li>
     *   <li>{@code crouch_multiplier} - multiplier on the per-shot camera-recoil impulse while crouching.</li>
     * </ul>
     *
     * @param kickbackAmplitude {@code kickback_recoil_amplitude}, in blocks
     * @param vertical          {@code vertical_recoil}, in degrees
     * @param horizontal        the legacy symmetrical {@code horizontal_recoil}, in degrees
     * @param horizontalLeft    {@code horizontal_recoil_left}, in degrees
     * @param horizontalRight   {@code horizontal_recoil_right}, in degrees
     * @param smoothing         {@code smoothing}, share of the remaining offset per reference interval
     * @param recoverySpeed     {@code recovery_speed}, multiplier on the baseline recovery rate
     * @param aimMultiplier     {@code aim_multiplier}, multiplier applied while aiming
     * @param crouchMultiplier  {@code crouch_multiplier}, multiplier applied while crouching
     * @param kickbackAimingModifier {@code kickback_aiming_modifier}, multiplier on the weapon kickback while aiming
     */
    public record Recoil(Double kickbackAmplitude, Double vertical, Double horizontal, Double horizontalLeft,
                         Double horizontalRight, Double smoothing, Double recoverySpeed, Double aimMultiplier,
                         Double crouchMultiplier, Double kickbackAimingModifier) {

        /**
         * Backwards-compatible construction of a configuration that declares no tuning properties, so every existing
         * caller (and every pack) keeps the documented defaults.
         */
        public Recoil(Double kickbackAmplitude, Double vertical, Double horizontal, Double horizontalLeft,
                      Double horizontalRight) {
            this(kickbackAmplitude, vertical, horizontal, horizontalLeft, horizontalRight, null, null, null, null, null);
        }

        /**
         * Backwards-compatible construction of a configuration with the original four tuning properties but without
         * {@code kickback_aiming_modifier}; the new property keeps its legacy-compatible default.
         */
        public Recoil(Double kickbackAmplitude, Double vertical, Double horizontal, Double horizontalLeft,
                      Double horizontalRight, Double smoothing, Double recoverySpeed, Double aimMultiplier,
                      Double crouchMultiplier) {
            this(kickbackAmplitude, vertical, horizontal, horizontalLeft, horizontalRight, smoothing, recoverySpeed,
                    aimMultiplier, crouchMultiplier, null);
        }

        public boolean isEmpty() {
            return kickbackAmplitude == null && vertical == null && horizontal == null && horizontalLeft == null
                    && horizontalRight == null && smoothing == null && recoverySpeed == null && aimMultiplier == null
                    && crouchMultiplier == null && kickbackAimingModifier == null;
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

    /** One entry of {@code glowing_weapon_parts}; the name references a bone in the weapon model. */
    public record GlowingPart(String name) {
    }

    /**
     * The {@code muzzle_flash} block.
     *
     * <p>The block is pure data: which attachment bones exist, which sprite sheet to draw, how large the flash is and,
     * optionally, how long it stays on screen. Nothing here decides <em>when</em> a flash appears - that is the client's
     * reaction to an accepted shot.</p>
     *
     * <h2>{@code duration_ms} is independent of the sprite animation</h2>
     * <p>The sprite frame rate ({@code sprites.fps}) only controls how fast the sheet's frames advance; {@code duration_ms}
     * only controls how long the flash remains visible. They are deliberately separate: a 200 fps sheet with an 80 ms
     * duration draws sixteen frames, while the same sheet at 60 fps draws five - and both stay on screen for exactly
     * 80 ms. A pack that declares no {@code duration_ms} keeps the behaviour it always had, where the animation's own
     * frames decide when it ends.</p>
     *
     * <p>{@link #bones()} is always the resolved list, whichever spelling the creator used: {@code bone} is stored as a
     * one-element list and {@code bones} as written. The two properties are mutually exclusive in JSON; when a pack
     * supplies both, the parser keeps {@code bone} and reports the conflict, so the resulting list is never a
     * combination of the two.</p>
     *
     * @param enabled {@code enabled}; {@code false} keeps the configuration readable but renders nothing
     * @param bones   the attachment points a shot may use. More than one entry means one of them is chosen per shot
     * @param texture the animated sprite sheet, or {@code null} when it is missing or invalid
     * @param size    the edge length of the rendered flash in blocks
     * @param sprites    the optional declared sprite grid; {@code null} means "derive a horizontal strip from the sheet's
     *                   own pixel size", which is the behaviour packs had before the grid could be declared
     * @param durationMs the optional {@code duration_ms}, the flash's visibility lifetime in milliseconds; {@code null}
     *                   means "derive it from the sheet's frames", which is the behaviour packs had before the property
     *                   existed
     */
    public record MuzzleFlash(boolean enabled, List<String> bones, ResourceId texture, double size, Sprites sprites,
                              Integer durationMs) {

        /** The largest number of attachment points a single weapon may declare. */
        public static final int MAX_BONES = 10;

        /** The size used when {@code size} is absent or unusable, in blocks. */
        public static final double DEFAULT_SIZE = 0.35D;

        /** Smallest accepted {@code size}, in blocks. */
        public static final double MIN_SIZE = 0.01D;

        /** Largest accepted {@code size}, in blocks. */
        public static final double MAX_SIZE = 4.0D;

    /**
     * The optional {@code sprites} block of a muzzle flash: the sprite-sheet grid and its animation.
     *
     * <p>This is the reference implementation's model: PB never guesses a sheet's layout, it declares it
     * ({@code "sprites": {"rows": 1, "columns": 8, "fps": 60, "type": "play_once"}}) and derives a frame's UV rectangle
     * from the row and column the frame index lands in. Declaring it is the only way to describe a sheet whose cell size
     * is not the whole texture height, which is exactly what a flash strip is.</p>
     *
     * @param rows            the sprite rows, at least one
     * @param columns         the sprite columns, at least one
     * @param fps             sprite frames per second; non-positive and non-finite values fall back to
     *                        {@link #DEFAULT_FPS}
     * @param type            how the frames advance, {@link #DEFAULT_TYPE} when the pack declares none
     */
    public record Sprites(int rows, int columns, int fps, SpriteType type) {

        /** Frames per second used when the block does not declare one, matching the reference implementation. */
        public static final int DEFAULT_FPS = 60;

        /** The animation used when the block does not declare one: a flash is a one-shot animation. */
        public static final SpriteType DEFAULT_TYPE = SpriteType.PLAY_ONCE;

        /** The largest accepted grid dimension, so a typo cannot ask for a million frames. */
        public static final int MAX_DIMENSION = 64;

        public Sprites {
            rows = clamp(rows);
            columns = clamp(columns);
            fps = fps > 0 ? Math.min(fps, 1000) : DEFAULT_FPS;
            type = type == null ? DEFAULT_TYPE : type;
        }

        /** @return the total number of frames the grid describes, at least one. */
        public int frameCount() {
            return rows * columns;
        }

        /** @return how long one frame is shown, in seconds. */
        public double frameSeconds() {
            return 1.0D / fps;
        }

        private static int clamp(int value) {
            return Math.max(1, Math.min(MAX_DIMENSION, value));
        }
    }

    /**
     * How the frames of a declared sprite grid advance.
     *
     * <p>The four names are the reference implementation's, so a pack can be moved between the two without rewriting its
     * JSON. {@code PLAY_ONCE} draws every frame once and then nothing, which is what a muzzle flash wants; {@code LOOP}
     * repeats; {@code STATIC} always draws the first frame; {@code RANDOM} picks a different frame per frame period.</p>
     */
    public enum SpriteType {
        STATIC,
        LOOP,
        PLAY_ONCE,
        RANDOM
    }

        /**
         * The longest accepted {@code duration_ms}.
         *
         * <p>A flash is a very short effect; this ceiling exists so a typo cannot leave one on screen for minutes. A
         * longer value is reported and clamped rather than rejected, because presentation never rejects a weapon.</p>
         */
        public static final int MAX_DURATION_MS = 10_000;

        /**
         * Backwards-compatible construction of a flash that declares no duration and derives its frames from the
         * sheet's own pixel size.
         *
         * @param enabled whether the flash is drawn
         * @param bones   the attachment bones
         * @param texture the sprite sheet
         * @param size    the quad's edge length in blocks
         */
        public MuzzleFlash(boolean enabled, List<String> bones, ResourceId texture, double size) {
            this(enabled, bones, texture, size, null, null);
        }

        /**
         * Backwards-compatible construction of a flash that declares no duration, so its animation's own frames decide
         * when it ends.
         *
         * @param enabled whether the flash is drawn
         * @param bones   the attachment bones
         * @param texture the sprite sheet
         * @param size    the quad's edge length in blocks
         * @param sprites the declared sprite grid, or {@code null}
         */
        public MuzzleFlash(boolean enabled, List<String> bones, ResourceId texture, double size, Sprites sprites) {
            this(enabled, bones, texture, size, sprites, null);
        }

        public MuzzleFlash {
            bones = bones == null ? List.of() : List.copyOf(bones);
            durationMs = durationMs == null || durationMs <= 0 ? null : Math.min(durationMs, MAX_DURATION_MS);
        }

        /** @return whether the pack declares its own visibility lifetime. */
        public boolean hasDuration() {
            return durationMs != null;
        }

        /**
         * @return the authored visibility lifetime in seconds, or {@code 0} when the pack declares none and the
         *         animation's own frames must decide
         */
        public double durationSeconds() {
            return durationMs == null ? 0.0D : durationMs / 1000.0D;
        }

        /**
         * @return {@code true} when this block can actually render something: it is enabled, it names at least one
         *         attachment point, its sprite sheet resolved and its size is usable
         */
        public boolean isUsable() {
            return enabled && texture != null && !bones.isEmpty() && Double.isFinite(size) && size > 0.0D;
        }
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
     * <p>The schema accepts two spellings for the first-draw clip, and the parser normalizes both into this one record,
     * so no selection rule ever has to know which of them a pack used:</p>
     * <pre>
     *   { "draw_first": "anim.model.draw_first", "duration": 1.2 }                 // key-only form
     *   { "name": "anim.model.draw_first", "first_draw": true, "duration": 1.2 }   // flag form
     * </pre>
     * <p>An entry whose {@code firstDraw} is true is used <em>only</em> when the stack's persistent first-draw flag is
     * unset, and it is excluded from the ordinary draw selection even when it declares no {@code when} condition - which
     * is what keeps the first-draw clip from becoming the default draw clip.</p>
     *
     * @param name                     the animation name
     * @param duration                 the duration in seconds
     * @param when                     the raw condition expression, preserved as data
     * @param declaredWithDrawFirstKey {@code true} when the entry used the {@code draw_first} key instead of the
     *                                 established {@code name} property; used only so a validation diagnostic can
     *                                 point at the property the creator actually wrote. The file is never rewritten.
     * @param firstDraw                {@code true} when this entry is the first-draw clip, whichever spelling declared
     *                                 it
     */
    public record DrawSelection(String name, double duration, String when, boolean declaredWithDrawFirstKey,
                                boolean firstDraw) {
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
    /**
     * One entry of {@code reload_sequences}.
     *
     * <p>The first component is the authored stage name. Content packs may spell it as {@code stage} (the staged,
     * shell-by-shell form) or as {@code phase} (the original spelling), and both are stored here unchanged so a
     * diagnostic can quote what the pack actually wrote. {@link com.arg_studios.arg_weaponry.weapon.reload.ReloadStage}
     * interprets the name; this record only carries it.</p>
     *
     * @param phase    the authored stage name, for example {@code prepare} or {@code reloading}
     * @param animation the animation clip name played for this entry, or {@code null} when the stage declares none (a
     *                  timed beat: it still spans its duration and still inserts its rounds)
     * @param duration  the authored duration in seconds
     * @param when      the raw, unevaluated condition, or {@code null} for "always"
     * @param repeat    the optional {@code repeat} flag; {@code false} on a {@code repeat_load} stage makes it a single
     *                  step, {@code null} and {@code true} both mean "insert one round per cycle"
     * @param insertAt  the optional point inside the entry, in seconds, at which ammunition is inserted; {@code null}
     *                  means "at the end of the entry", which is when a shell is seated
     * @param transitionDuration the optional {@code transition_duration}, the cross-fade in seconds used when this
     *                  entry's clip starts; {@code null} means "the default", {@code 0} disables blending
     */
    public record ReloadStep(String phase, String animation, double duration, String when, Boolean repeat,
                             Double insertAt, Double transitionDuration) {

        /**
         * Backwards-compatible construction of an entry that inserts at its end and uses the default cross-fade.
         *
         * @param phase     the authored stage name
         * @param animation the animation clip name
         * @param duration  the authored duration in seconds
         * @param when      the raw condition, or {@code null}
         * @param repeat    the optional repeat flag, or {@code null}
         */
        public ReloadStep(String phase, String animation, double duration, String when, Boolean repeat) {
            this(phase, animation, duration, when, repeat, null, null);
        }

        /**
         * Backwards-compatible construction of an entry that uses the default cross-fade.
         *
         * @param phase     the authored stage name
         * @param animation the animation clip name
         * @param duration  the authored duration in seconds
         * @param when      the raw condition, or {@code null}
         * @param repeat    the optional repeat flag, or {@code null}
         * @param insertAt  the optional insertion point, or {@code null}
         */
        public ReloadStep(String phase, String animation, double duration, String when, Boolean repeat,
                          Double insertAt) {
            this(phase, animation, duration, when, repeat, insertAt, null);
        }

        /** @return whether the entry asks to be played again for every further insertion cycle. */
        public boolean repeated() {
            return Boolean.TRUE.equals(repeat);
        }

        /**
         * @return the cross-fade length of this entry's clip, in milliseconds; the documented default when the entry
         *         declares none, and {@code 0} when it disables blending
         */
        public int transitionMillis() {
            return ReloadRules.transitionMillis(transitionDuration);
        }

        /**
         * @return the point inside the entry, in seconds, at which ammunition is inserted; the entry's own duration
         *         when the pack declares no {@code insert_at}
         */
        public double insertOffsetSeconds() {
            double authored = insertAt == null ? duration : insertAt;
            if (!Double.isFinite(authored) || authored < 0.0) {
                return duration;
            }
            return Math.min(authored, duration);
        }
    }
}
