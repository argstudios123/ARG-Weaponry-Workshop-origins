package com.arg_studios.arg_weaponry.weapon.reload;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The stage a {@code reload_sequences} entry belongs to, as declared by its {@code stage} (or legacy {@code phase})
 * property.
 *
 * <p>The staged vocabulary is what makes a shell-by-shell reload expressible in data instead of in code:</p>
 * <ul>
 *     <li>{@link #PREPARE} - the clip that starts the reload. A pack usually declares two entries here and selects
 *     between them with {@code ammo_count}, so an empty weapon has its own opening clip;</li>
 *     <li>{@link #INITIAL_LOAD} - the clip that inserts the <em>first</em> round, selected by
 *     {@code reload_inserted_count == 0};</li>
 *     <li>{@link #REPEAT_LOAD} - the clip that inserts every further round, selected by
 *     {@code reload_inserted_count > 0}. It is played once per insertion cycle, which is why the stage is called
 *     "repeat" even though the entry itself does not have to set {@code repeat}. Setting {@code "repeat": false} on such
 *     an entry makes it a single step: it inserts its rounds once and the reload moves on to {@link #FINISH}, so the flag
 *     decides how often the step runs and never whether ammunition is inserted;</li>
 *     <li>{@link #FINISH} - the clip that closes the reload;</li>
 *     <li>{@link #CHAMBER_LOAD} - the optional clip that chambers one round after the magazine is filled.</li>
 * </ul>
 *
 * <h2>Legacy definitions</h2>
 * <p>A definition written before the staged vocabulary exists (the shipped M100 DMR writes
 * {@code "phase": "reloading"}) uses no recognizable stage name. Such a definition is
 * {@link #WHOLE}: one clip, one transfer at the end, exactly the behaviour it had before this stage system existed.
 * {@link #lookup(String)} returning {@code null} is what identifies that case, so no pack is ever forced to migrate and
 * an unknown name never silently becomes a load stage.</p>
 */
public enum ReloadStage {

    /** Opens the reload. Played once. */
    PREPARE("prepare"),

    /** Inserts the first round. Played once. */
    INITIAL_LOAD("initial_load"),

    /** Inserts one round per cycle. Played once per additional insertion. */
    REPEAT_LOAD("repeat_load"),

    /** Closes the reload. Played once. */
    FINISH("finish"),

    /** Optionally chambers one round after the magazine is full. Played once. */
    CHAMBER_LOAD("chamber_load"),

    /**
     * The single-clip form: the whole reload is one entry and the transfer happens when it ends.
     *
     * <p>Not a declared name; {@link #lookup(String)} never returns it. It is produced by
     * {@link ReloadTimeline} for definitions that declare no recognized stage.</p>
     */
    WHOLE("whole");

    private final String jsonName;

    ReloadStage(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    /** @return whether this stage inserts ammunition when it reaches its insertion point. */
    public boolean insertsAmmunition() {
        return this == INITIAL_LOAD || this == REPEAT_LOAD || this == CHAMBER_LOAD;
    }

    /**
     * Looks up a declared stage name.
     *
     * <p>Comparison is case-insensitive and tolerates the separators a creator is likely to type ({@code -} and
     * spaces become {@code _}), so {@code Initial Load} and {@code initial-load} both mean
     * {@link #INITIAL_LOAD}. Nothing else is forgiven: an unrecognized name yields {@code null} and the caller treats
     * the definition as a legacy single-clip reload.</p>
     *
     * @param declared the authored {@code stage} or {@code phase} value, may be {@code null}
     * @return the matching stage, or {@code null} when the name is not part of the vocabulary
     */
    public static ReloadStage lookup(String declared) {
        if (declared == null) {
            return null;
        }
        String normalised = declared.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (ReloadStage stage : values()) {
            if (stage != WHOLE && stage.jsonName.equals(normalised)) {
                return stage;
            }
        }
        return null;
    }

    /** @return the declared stage names, for diagnostics. */
    public static String knownNames() {
        return Arrays.stream(values()).filter(stage -> stage != WHOLE).map(ReloadStage::jsonName)
                .collect(Collectors.joining(", "));
    }
}
