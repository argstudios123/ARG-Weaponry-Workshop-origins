package com.arg_studios.arg_weaponry.weapon;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.pack.ContentPack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link WeaponRegistrationPlan} from the loaded content packs and weapon definitions.
 *
 * <p>This class never touches a Minecraft registry: it only decides what would be registered. Item
 * ids are taken verbatim from the authoritative {@code weapon_id}, a default tab is planned for every
 * discovered pack, and definitions without a usable identity are counted and skipped.
 * </p>
 */
public final class WeaponRegistrationPlanner {

    /** Prefix used for creative tab ids, below the mod namespace. */
    public static final String TAB_ID_PREFIX = "pack/";

    private WeaponRegistrationPlanner() {
    }

    /**
     * @param packs      the discovered content packs, in discovery order
     * @param loadResult the loaded weapon definitions
     * @param report     the report to collect diagnostics in
     * @return the registration plan; never {@code null}
     */
    public static WeaponRegistrationPlan plan(List<ContentPack> packs, WeaponLoadResult loadResult,
                                              ValidationReport report) {
        List<WeaponRegistrationPlan.PlannedWeapon> planned = new ArrayList<>();
        int skippedWithoutIdentity = 0;

        for (WeaponLoadResult.LoadedWeapon loaded : loadResult.weapons()) {
            WeaponDefinition definition = loaded.definition();
            ResourceId authoritativeId = definition.authoritativeId();
            if (authoritativeId == null) {
                skippedWithoutIdentity++;
                continue;
            }
            planned.add(new WeaponRegistrationPlan.PlannedWeapon(loaded.pack(), definition, authoritativeId));
        }

        planned.sort(Comparator.comparing(weapon -> weapon.itemId().asString()));

        if (skippedWithoutIdentity > 0) {
            report.warning(ValidationReport.frameworkScope(), "weapon definitions",
                    skippedWithoutIdentity + " parsed definition(s) have no usable 'weapon_id' and no Minecraft item "
                            + "will be registered for them");
        }

        Map<String, List<WeaponRegistrationPlan.PlannedWeapon>> byPack = new LinkedHashMap<>();
        for (ContentPack pack : packs) {
            byPack.put(pack.id(), new ArrayList<>());
        }
        for (WeaponRegistrationPlan.PlannedWeapon weapon : planned) {
            List<WeaponRegistrationPlan.PlannedWeapon> weapons = byPack.get(weapon.pack().id());
            if (weapons == null) {
                report.error(weapon.pack().id(), weapon.itemId().toString(),
                        "The weapon's content pack is not part of the discovered pack list; it is not planned for "
                                + "registration");
                continue;
            }
            weapons.add(weapon);
        }

        List<WeaponRegistrationPlan.PackTab> tabs = new ArrayList<>();
        for (ContentPack pack : packs) {
            String tabPath = TAB_ID_PREFIX + pack.id();
            ResourceId.Result tabId = ResourceId.tryParse(Constants.MOD_ID + ":" + tabPath);
            if (!tabId.isValid()) {
                report.error(pack.id(), "creative tab", "Could not derive a creative tab id for this pack: "
                        + tabId.error());
                continue;
            }
            tabs.add(new WeaponRegistrationPlan.PackTab(pack, tabId.id(), byPack.get(pack.id())));
        }

        return new WeaponRegistrationPlan(planned, tabs, skippedWithoutIdentity);
    }
}
