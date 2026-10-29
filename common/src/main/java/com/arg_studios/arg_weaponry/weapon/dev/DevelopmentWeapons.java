package com.arg_studios.arg_weaponry.weapon.dev;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsonValues;
import com.arg_studios.arg_weaponry.json.JsoncReader;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.pack.ContentPackResources;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.ResourceId;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinitionParser;
import com.arg_studios.arg_weaponry.weapon.WeaponRegistrationPlan;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Optional;


public final class DevelopmentWeapons {

    /** System property that overrides the development-environment default. */
    public static final String ENABLE_PROPERTY = "arg_weaponry.dev_weapons";

    /** Classpath location of the development definition. Outside {@code assets/} on purpose. */
    public static final String FIXTURE_RESOURCE = "/dev/weapon/dev_m100_dmr.json";

    /** File-derived identity used for diagnostics, matching the fixture's own {@code weapon_id}. */
    public static final ResourceId FIXTURE_IDENTITY = new ResourceId(Constants.MOD_ID, "dev_m100_dmr");

    private DevelopmentWeapons() {
    }

    /**
     * @return {@code true} when development weapons should be planned
     */
    public static boolean isEnabled() {
        String override = System.getProperty(ENABLE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override.trim());
        }
        return Services.PLATFORM.isDevelopmentEnvironment();
    }

    /**
     * Plans the development weapon, if it is enabled and its resources can be resolved.
     *
     * @param packs            the discovered content packs, in discovery order
     * @param reservedItemIds  item ids that are already planned for registration; a development weapon
     *                         never competes with a content pack for the same id
     * @param report           the report to collect diagnostics in
     * @return zero or one planned weapon; never {@code null}
     */
    public static List<WeaponRegistrationPlan.PlannedWeapon> plan(List<ContentPack> packs,
                                                                  Collection<String> reservedItemIds,
                                                                  ValidationReport report) {
        if (!isEnabled()) {
            report.info(ValidationReport.frameworkScope(), "development weapons",
                    "Development weapons are disabled (property '" + ENABLE_PROPERTY + "' or production environment)");
            return List.of();
        }

        Optional<String> fixture = readFixture();
        if (fixture.isEmpty()) {
            report.warning(ValidationReport.frameworkScope(), "development weapons",
                    "The development weapon fixture '" + FIXTURE_RESOURCE + "' could not be read; no development "
                            + "weapon was planned");
            return List.of();
        }

        JsonObject document;
        try {
            JsonElement root = JsoncReader.parse(fixture.get());
            if (!(root instanceof JsonObject object)) {
                report.warning(ValidationReport.frameworkScope(), "development weapons",
                        "The development weapon fixture does not contain a JSON object; no development weapon was "
                                + "planned");
                return List.of();
            }
            document = object;
        } catch (JsoncReader.JsoncException e) {
            report.warning(ValidationReport.frameworkScope(), "development weapons",
                    "The development weapon fixture is not valid JSON (" + e.describe() + "); no development weapon "
                            + "was planned");
            return List.of();
        }

        ContentPack owner = findOwningPack(packs, document, report);
        if (owner == null) {
            return List.of();
        }

        String source = "dev/weapon/" + FIXTURE_IDENTITY.path() + ".json";
        WeaponDefinition definition = new WeaponDefinitionParser(owner.id(), source, FIXTURE_IDENTITY, report)
                .parse(document);
        if (definition == null) {
            report.warning(ValidationReport.frameworkScope(), "development weapons",
                    "The development weapon definition was rejected by the parser; no development weapon was planned");
            return List.of();
        }
        if (definition.authoritativeId() == null) {
            report.warning(ValidationReport.frameworkScope(), "development weapons",
                    "The development weapon definition has no usable 'weapon_id'; no development weapon was planned");
            return List.of();
        }
        if (reservedItemIds != null && reservedItemIds.contains(definition.authoritativeId().asString())) {
            report.warning(ValidationReport.frameworkScope(), "development weapons", "The development weapon id '"
                    + definition.authoritativeId() + "' is already claimed by a content pack, so the development "
                    + "weapon was not planned; the pack definition keeps the id");
            return List.of();
        }

        report.info(ValidationReport.frameworkScope(), "development weapons", "Planned the development-only weapon '"
                + definition.authoritativeId() + "' owned by pack '" + owner.id()
                + "', using the display configuration " + definition.display());
        return List.of(new WeaponRegistrationPlan.PlannedWeapon(owner, definition, definition.authoritativeId()));
    }

    /**
     * Finds the content pack that actually provides the fixture's display resource, so the development
     * weapon resolves its model and texture from real pack content instead of inventing resources.
     */
    private static ContentPack findOwningPack(List<ContentPack> packs, JsonObject document, ValidationReport report) {
        JsonElement displayElement = document.get("display");
        if (!JsonValues.isString(displayElement)) {
            report.warning(ValidationReport.frameworkScope(), "development weapons",
                    "The development weapon fixture declares no usable 'display' reference; no development weapon "
                            + "was planned");
            return null;
        }

        ResourceId.Result display = ResourceId.tryParse(displayElement.getAsString());
        if (!display.isValid()) {
            report.warning(ValidationReport.frameworkScope(), "development weapons", "The development weapon's "
                    + "'display' reference '" + displayElement.getAsString() + "' is invalid: " + display.error());
            return null;
        }

        for (ContentPack pack : packs) {
            if (ContentPackResources.find(pack, display.id(), "display",
                    ContentPackResources.JSON_SUFFIXES).isPresent()) {
                return pack;
            }
        }

        report.warning(ValidationReport.frameworkScope(), "development weapons",
                "No discovered content pack provides the display configuration '" + display.id()
                        + "', so the development weapon was not planned. It needs the default pack to be installed");
        return null;
    }

    private static Optional<String> readFixture() {
        try (InputStream stream = DevelopmentWeapons.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            if (stream == null) {
                return Optional.empty();
            }
            return Optional.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            Constants.LOG.warn("[ARG] Failed to read the development weapon fixture: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
