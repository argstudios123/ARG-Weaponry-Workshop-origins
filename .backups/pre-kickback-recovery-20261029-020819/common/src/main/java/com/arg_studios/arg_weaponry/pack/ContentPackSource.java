package com.arg_studios.arg_weaponry.pack;

/**
 * Where a discovered content pack came from.
 *
 * <p>Used for logging, diagnostics and precedence: an installed pack shadows the bundled copy of the
 * same pack, so the bundled copy is never loaded a second time.</p>
 */
public enum ContentPackSource {

    /** A pack folder inside {@code <gameDir>/arg_weaponry/}. */
    INSTALLED("installed"),

    /** A pack bundled inside the mod itself, below {@code assets/arg_weaponry/weapon_packs/}. */
    BUNDLED("bundled");

    private final String label;

    ContentPackSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
