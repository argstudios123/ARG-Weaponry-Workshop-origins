package com.arg_studios.arg_weaponry;

import com.arg_studios.arg_weaponry.compat.WeaponManifestSnapshot;
import com.arg_studios.arg_weaponry.core.PackDiagnostic.Severity;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.install.BundledPackInstaller;
import com.arg_studios.arg_weaponry.item.AmmunitionRegistration;
import com.arg_studios.arg_weaponry.item.WeaponRegistration;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackScanner;
import com.arg_studios.arg_weaponry.pack.PackMetadata;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinitionLoader;
import com.arg_studios.arg_weaponry.weapon.WeaponLoadResult;
import com.arg_studios.arg_weaponry.weapon.WeaponRegistrationPlan;
import com.arg_studios.arg_weaponry.weapon.WeaponRegistrationPlanner;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionDefinition;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionDefinitionLoader;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionLoadResult;
import com.arg_studios.arg_weaponry.weapon.ammo.AmmunitionRegistry;
import com.arg_studios.arg_weaponry.weapon.dev.DevelopmentWeapons;
import com.arg_studios.arg_weaponry.weapon.state.WeaponComponents;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared framework bootstrap.
 *
 * <p>Called once by each loader entry point during mod initialisation, which is deliberately early:
 * Minecraft freezes its registries after initialisation, so anything that will later register items
 * or creative tabs has to be discovered here rather than during a resource reload.</p>
 *
 * <p>The order inside {@link #init()} matters and is: install the bundled packs, discover them, load
 * <strong>ammunition definitions</strong>, index them, and only then load the weapon definitions — so a
 * weapon's {@code ammo_to_use} entries can already be resolved while that weapon is being validated.</p>
 */
public final class ArgWeaponry {

    private static List<ContentPack> contentPacks = List.of();
    private static AmmunitionLoadResult ammunitionLoadResult = AmmunitionLoadResult.empty();
    private static WeaponLoadResult weaponLoadResult = WeaponLoadResult.empty();
    private static WeaponRegistrationPlan registrationPlan = WeaponRegistrationPlan.empty();
    private static boolean initialized;

    private ArgWeaponry() {
    }

    /** Discovers content packs and reports the result. Safe to call more than once. */
    public static void init() {
        if (initialized) {
            Constants.LOG.warn("[ARG] Initialisation was requested more than once; ignoring the duplicate request.");
            return;
        }
        initialized = true;

        Constants.LOG.info("[ARG] {} ({}) starting on {} ({} environment)",
                Constants.MOD_NAME, Constants.MOD_ID,
                Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());

        ValidationReport report = new ValidationReport();
        List<Path> contentRoots = Services.MOD_ROOTS.getContentRootPaths();
        for (Path root : contentRoots) {
            Constants.LOG.debug("[ARG] Content root reported by the loader: {}", root);
        }

        Path installRoot = BundledPackInstaller.install(Services.GAME_DIRECTORY.getGameDirectory(), contentRoots, report);
        Constants.LOG.info("[ARG] Content pack install root: {}", installRoot);

        contentPacks = ContentPackScanner.scan(installRoot, contentRoots, report);

        // Ammunition first: a weapon's 'ammo_to_use' entries are resolved against these definitions while the
        // weapon definitions are validated below, so the index has to exist before that load runs.
        ammunitionLoadResult = AmmunitionDefinitionLoader.load(contentPacks, report);
        AmmunitionRegistry.build(ammunitionLoadResult);

        weaponLoadResult = WeaponDefinitionLoader.load(contentPacks, report);
        registrationPlan = WeaponRegistrationPlanner.plan(contentPacks, weaponLoadResult, report);

        // Development-only weapons are planned separately so production content stays untouched. Ids that a
        // content pack already claimed are never taken over by a development weapon.
        List<String> claimedItemIds = new ArrayList<>(registrationPlan.weapons().size());
        for (WeaponRegistrationPlan.PlannedWeapon planned : registrationPlan.weapons()) {
            claimedItemIds.add(planned.itemId().asString());
        }
        List<WeaponRegistrationPlan.PlannedWeapon> developmentWeapons =
                DevelopmentWeapons.plan(contentPacks, claimedItemIds, report);
        registrationPlan = registrationPlan.withAdditionalWeapons(developmentWeapons);

        report.logAll();

        Constants.LOG.info("[ARG] Discovered {} content pack(s): {} error(s), {} warning(s), {} informational finding(s)",
                contentPacks.size(),
                report.count(Severity.ERROR),
                report.count(Severity.WARNING),
                report.count(Severity.INFO));

        for (ContentPack pack : contentPacks) {
            logPack(pack);
        }

        Constants.LOG.info("[ARG] Weapon definitions: {} parsed, {} rejected; {} planned for item registration "
                        + "({} without a usable weapon_id)",
                weaponLoadResult.definitions().size(), weaponLoadResult.rejected().size(),
                registrationPlan.weapons().size(), registrationPlan.skippedWithoutIdentity());
        for (WeaponDefinition weapon : weaponLoadResult.definitions()) {
            logWeapon(weapon);
        }
        for (WeaponLoadResult.RejectedDefinition rejected : weaponLoadResult.rejected()) {
            Constants.LOG.warn("[ARG]   rejected weapon definition {}: {}", rejected.source(), rejected.reason());
        }

        for (WeaponRegistrationPlan.PlannedWeapon development : developmentWeapons) {
            Constants.LOG.info("[ARG]   development-only weapon '{}' owned by pack '{}' using display {}",
                    development.itemId(), development.pack().id(), development.definition().display());
        }

        logAmmunition();
    }

    /** Logs the ammunition definitions that were loaded and indexed, for diagnostics and runtime evidence. */
    private static void logAmmunition() {
        Constants.LOG.info("[ARG] Ammunition definitions: {} parsed, {} rejected; {} indexed for item registration",
                ammunitionLoadResult.definitions().size(), ammunitionLoadResult.rejected().size(),
                AmmunitionRegistry.size());
        for (AmmunitionLoadResult.LoadedAmmunition ammunition : AmmunitionRegistry.all()) {
            AmmunitionDefinition definition = ammunition.definition();
            Constants.LOG.info("[ARG]   ammunition '{}' -> item '{}' (pack '{}', display {})",
                    definition.ammoName(), definition.ammoId(), ammunition.pack().id(),
                    definition.hasDisplay() ? definition.display() : "<none>");
        }
        for (AmmunitionLoadResult.RejectedDefinition rejected : ammunitionLoadResult.rejected()) {
            Constants.LOG.warn("[ARG]   rejected ammunition definition {}: {}", rejected.source(), rejected.reason());
        }
    }

    /**
     * @return the ammunition definitions that parsed successfully during {@link #init()}, in discovery order
     */
    public static List<AmmunitionDefinition> ammunitionDefinitions() {
        return ammunitionLoadResult.ammunitionDefinitions();
    }

    /**
     * @return the full ammunition loading outcome, including rejected definitions
     */
    public static AmmunitionLoadResult ammunitionLoadResult() {
        return ammunitionLoadResult;
    }

    /**
     * @return the content packs discovered during {@link #init()}, in discovery order
     */
    public static List<ContentPack> contentPacks() {
        return contentPacks;
    }

    /**
     * @return the weapon definitions that parsed successfully during {@link #init()}, in discovery order
     */
    public static List<WeaponDefinition> weaponDefinitions() {
        return weaponLoadResult.definitions();
    }

    /**
     * @return the full weapon loading outcome, including rejected definitions
     */
    public static WeaponLoadResult weaponLoadResult() {
        return weaponLoadResult;
    }

    /**
     * Registers the weapon data components. Must be called from the loader's safe registration point and
     * <strong>before</strong> {@link #registerItems()}, because every weapon item declares the
     * {@code arg_weaponry:weapon_state} default: Fabric calls this during mod initialisation, NeoForge
     * from the {@code RegisterEvent} for {@code minecraft:data_component_type}, which it fires before the
     * item registry event.
     */
    public static void registerComponents() {
        if (!initialized) {
            Constants.LOG.error("[ARG] Component registration was requested before initialisation; ignoring "
                    + "the request");
            return;
        }
        ValidationReport report = new ValidationReport();
        WeaponComponents.register(report);
        report.logAll();
    }

    /**
     * Registers the planned weapon items. Must be called from the loader's safe registration point:
     * Fabric during mod initialisation, NeoForge from the item {@code RegisterEvent}.
     */
    public static void registerItems() {
        if (!initialized) {
            Constants.LOG.error("[ARG] Item registration was requested before initialisation; ignoring the request");
            return;
        }
        ValidationReport report = new ValidationReport();
        WeaponRegistration.registerItems(registrationPlan, report);
        report.logAll();
        Constants.LOG.info("[ARG] Weapon items registered: {} ({} planned item(s) were skipped)",
                WeaponRegistration.registeredWeapons().size(),
                registrationPlan.weapons().size() - WeaponRegistration.registeredWeapons().size());

        // Ammunition items are registered in the same registry pass as the weapons: the item registry is only
        // writable here, and registering them together keeps a weapon and the ammunition it accepts in one
        // lifecycle stage on both loaders.
        ValidationReport ammunitionReport = new ValidationReport();
        AmmunitionRegistration.registerItems(ammunitionReport);
        ammunitionReport.logAll();

        // The manifest is frozen here, because this is the only moment at which the set of registered weapon
        // identifiers is final. It includes the pack fingerprints, which walk the pack trees, so it must not be
        // built later on a network thread or per connection.
        WeaponManifestSnapshot.build();
    }

    /**
     * Registers one creative tab per content pack. Must be called from the loader's safe registration
     * point: Fabric during mod initialisation, NeoForge from the creative-tab {@code RegisterEvent}.
     */
    public static void registerTabs() {
        if (!initialized) {
            Constants.LOG.error("[ARG] Creative tab registration was requested before initialisation; ignoring "
                    + "the request");
            return;
        }
        ValidationReport report = new ValidationReport();
        WeaponRegistration.registerTabs(registrationPlan, report);
        report.logAll();
    }

    /**
     * @return the plan that drives item and creative-tab registration
     */
    public static WeaponRegistrationPlan registrationPlan() {
        return registrationPlan;
    }

    private static void logWeapon(WeaponDefinition weapon) {
        Constants.LOG.info("[ARG]   weapon '{}' [{}] name='{}' display={} damage={} capacity={} rpm={} modes={} "
                        + "burst={} continuousBurst={} burstGap={}s sounds={} idle={} holster={} draw={} reload={} "
                        + "file={}",
                weapon.authoritativeId() == null ? "<no weapon_id>" : weapon.authoritativeId(),
                weapon.type().jsonName(),
                weapon.name(),
                weapon.display(),
                weapon.damage(),
                weapon.ammoCapacity() == null ? "-" : weapon.ammoCapacity(),
                weapon.rpm() == null ? "-" : weapon.rpm(),
                com.arg_studios.arg_weaponry.weapon.fire.FiringModes.names(weapon.firingModes()),
                weapon.burstCount(),
                weapon.continuousBurst(),
                weapon.timeBetweenBursts(),
                weapon.sounds().size(),
                weapon.idleAnimations().size(),
                weapon.holsterAnimations().size(),
                weapon.drawAnimations().size(),
                weapon.reloadSequences().size(),
                weapon.fileIdentity());
    }

    private static void logPack(ContentPack pack) {
        PackMetadata metadata = pack.metadata();
        Constants.LOG.info("[ARG] Content pack '{}' [{}] loaded from {}", pack.id(), pack.source().label(), pack.root());
        Constants.LOG.info("[ARG]   resource namespaces: {}",
                pack.resourceNamespaces().isEmpty() ? "<none>" : String.join(", ", pack.resourceNamespaces()));
        Constants.LOG.info("[ARG]   name key: {}", valueOrPlaceholder(metadata.nameKey()));
        Constants.LOG.info("[ARG]   creative icon: {}", valueOrPlaceholder(metadata.creativeIcon()));
        Constants.LOG.info("[ARG]   version: {}", valueOrPlaceholder(metadata.version()));
        Constants.LOG.info("[ARG]   pack format: {}", valueOrPlaceholder(metadata.formatInfo()));
        Constants.LOG.info("[ARG]   description: {}", valueOrPlaceholder(metadata.description()));
    }

    private static String valueOrPlaceholder(String value) {
        return value == null ? "<not specified>" : value;
    }
}
