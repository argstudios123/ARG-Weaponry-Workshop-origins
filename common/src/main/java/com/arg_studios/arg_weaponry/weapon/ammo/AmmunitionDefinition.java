package com.arg_studios.arg_weaponry.weapon.ammo;

import com.arg_studios.arg_weaponry.weapon.ResourceId;

/**
 * A parsed ammunition definition from a content pack.
 *
 * <p>The schema is the one the packs already use, and every field name is preserved verbatim:</p>
 * <pre>{@code
 * {
 *   "ammo_name": "dmr_ammo",
 *   "ammo_id":   "arg_pack:dmr_ammo",
 *   "type":      "ammo",
 *   "display":   "arg_pack:ammunition/dmrs_ammo/dmr_ammo"
 * }
 * }</pre>
 *
 * <h2>Two identities, deliberately separate</h2>
 * <ul>
 *   <li>{@link #ammoName()} is the <em>logical</em> compatibility identity. Weapons reference it from the
 *       existing {@code ammo_to_use} array, exactly as the M100 DMR does with {@code "dmr_ammo"}.</li>
 *   <li>{@link #ammoId()} is the <em>registry</em> identity: the Minecraft item id the ammunition item is
 *       actually registered under.</li>
 * </ul>
 * <p>Neither is derived from the other and neither is a weapon identity. Keeping them apart is what lets
 * several weapons share one ammunition definition while the item keeps a stable registry id.</p>
 *
 * <h2>Optional display</h2>
 * <p>{@link #display()} is a resource identifier that points at a <em>separate</em> display definition (see
 * {@code AmmunitionDisplayLoader}); it is not a model or texture path. A definition without one is valid:
 * its item simply uses the documented fallback item model instead of custom artwork.</p>
 *
 * <p>This record is immutable and holds no Minecraft types, so it can be parsed, indexed and tested without
 * a running game. Mutable state (registered items, caches) lives elsewhere.</p>
 *
 * @param fileIdentity the identity derived from the definition's file location, for example
 *                     {@code arg_pack:ammunition/dmrs_ammo/dmr_ammo}; used for diagnostics only
 * @param ammoName     the logical ammunition name referenced by weapons
 * @param ammoId       the Minecraft item id this ammunition is registered under
 * @param type         the declared {@code type}; always {@code "ammo"} for an accepted definition
 * @param display      the display-definition reference, or {@code null} when none is declared
 */
public record AmmunitionDefinition(
        ResourceId fileIdentity,
        String ammoName,
        ResourceId ammoId,
        String type,
        ResourceId display) {

    /** The only {@code type} value this schema accepts. */
    public static final String TYPE_AMMO = "ammo";

    public AmmunitionDefinition {
        if (ammoName == null || ammoName.isBlank()) {
            throw new IllegalArgumentException("An ammunition definition needs a logical name");
        }
        if (ammoId == null) {
            throw new IllegalArgumentException("An ammunition definition needs a registry item id");
        }
        if (!TYPE_AMMO.equals(type)) {
            throw new IllegalArgumentException("An ammunition definition must declare type '" + TYPE_AMMO
                    + "' but declares '" + type + "'");
        }
    }

    /** @return {@code true} when this definition declares a display definition to render from. */
    public boolean hasDisplay() {
        return display != null;
    }
}
