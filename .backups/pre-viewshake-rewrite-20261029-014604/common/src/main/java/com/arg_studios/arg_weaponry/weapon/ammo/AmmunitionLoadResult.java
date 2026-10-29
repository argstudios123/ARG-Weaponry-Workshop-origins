package com.arg_studios.arg_weaponry.weapon.ammo;

import com.arg_studios.arg_weaponry.pack.ContentPack;

import java.util.List;

/**
 * The outcome of loading ammunition definitions from the discovered content packs.
 *
 * <p>One definition can fail without affecting the others: reading, JSON syntax, missing or invalid
 * required properties, and duplicate names or registry ids are all reported per file, and every accepted
 * definition keeps its owning pack so later stages can resolve its resources.</p>
 *
 * @param definitions the accepted definitions, in deterministic discovery order
 * @param rejected    the definitions that could not be used, with a short reason
 */
public record AmmunitionLoadResult(List<LoadedAmmunition> definitions, List<RejectedDefinition> rejected) {

    public AmmunitionLoadResult {
        definitions = List.copyOf(definitions);
        rejected = List.copyOf(rejected);
    }

    /** @return an empty result. */
    public static AmmunitionLoadResult empty() {
        return new AmmunitionLoadResult(List.of(), List.of());
    }

    /** @return the accepted definitions without their owning packs. */
    public List<AmmunitionDefinition> ammunitionDefinitions() {
        return definitions.stream().map(LoadedAmmunition::definition).toList();
    }

    /**
     * One accepted ammunition definition.
     *
     * @param pack       the content pack that supplied it
     * @param definition the parsed definition
     */
    public record LoadedAmmunition(ContentPack pack, AmmunitionDefinition definition) {
    }

    /**
     * One rejected ammunition definition.
     *
     * @param packId the pack the file belongs to
     * @param source the file, relative to the pack root
     * @param reason a short reason, suitable for a log line
     */
    public record RejectedDefinition(String packId, String source, String reason) {
    }
}
