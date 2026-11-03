package com.arg_studios.arg_weaponry.weapon.reload;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chooses which declared {@code reload_sequences} entry applies to a reload.
 *
 * <p>The parser already stores every entry as a {@link WeaponDefinition.ReloadStep} (phase, animation,
 * duration, raw {@code when}, optional {@code repeat}, optional {@code insert_at}) and deliberately keeps {@code when}
 * unevaluated. This selector supplies the missing evaluation for reloading, using the variable the shipped weapon
 * actually writes:</p>
 * <pre>
 *     reload_count &lt;op&gt; &lt;integer&gt;      op = == != &gt; &gt;= &lt; &lt;=
 * </pre>
 * <p>{@code reload_count} is the number of rounds in the magazine <em>when the reload starts</em>, which is
 * exactly what the M100 DMR means by it: {@code reload_count == 0} selects the empty-reload clip and
 * {@code reload_count > 0} the standard one. It is not the number of rounds inserted, and it is not
 * re-evaluated during the reload. {@link ReloadConditions} additionally accepts the {@code ammoCount} and
 * {@code ammo_count} spellings of the same value, so a pack cannot lose an animation to a spelling mismatch; the value
 * it is compared against is identical in every case.</p>
 *
 * <h2>Condition support (deliberately narrow)</h2>
 * <p>An absent or blank condition, or {@code *}, always applies. The comparison grammar itself lives in
 * {@link ReloadConditions}; anything outside it is reported in {@link Selection#unsupportedConditions()} and can never
 * match — it is not silently treated as true or false.</p>
 *
 * <h2>Staged reloads</h2>
 * <p>This class answers "which single clip represents this reload", which is all a definition written before the staged
 * vocabulary needs. A definition that declares {@code stage} entries ({@code prepare}, {@code initial_load},
 * {@code repeat_load}, {@code finish}, {@code chamber_load}) is planned by {@link ReloadTimeline} instead, which uses
 * this selector only for the single-clip fallback. Keeping both means a pack is never forced to migrate and the legacy
 * timing cannot change.</p>
 *
 * <h2>Fallback</h2>
 * <p>When a weapon declares sequences but none matches (typically because every condition is outside the
 * grammar), the <em>first</em> declared entry is used and {@link Selection#usedFallback()} says so. That keeps
 * an unusual declaration animated instead of silently losing its animation. When no sequences are declared at
 * all, the selection is empty and {@link #durationSeconds(Selection)} returns
 * {@link ReloadRules#DEFAULT_RELOAD_SECONDS}.</p>
 */
public final class ReloadSequenceSelector {

    /** The only variable the reload condition evaluator understands. */
    public static final String RELOAD_COUNT_VARIABLE = "reload_count";

    private ReloadSequenceSelector() {
    }

    /**
     * The outcome of one selection pass.
     *
     * @param step                  the entry to play, or empty when the weapon declares no sequences
     * @param unsupportedConditions the {@code when} expressions this build cannot evaluate, in file order
     * @param usedFallback          whether {@link #step} was chosen by the fallback rule rather than by a match
     */
    public record Selection(Optional<WeaponDefinition.ReloadStep> step, List<String> unsupportedConditions,
                            boolean usedFallback) {

        public Selection {
            step = step == null ? Optional.empty() : step;
            unsupportedConditions = unsupportedConditions == null ? List.of() : List.copyOf(unsupportedConditions);
        }

        /** @return {@code true} when at least one declared condition could not be evaluated. */
        public boolean hasUnsupportedConditions() {
            return !unsupportedConditions.isEmpty();
        }
    }

    /**
     * @param selection a selection, may be {@code null}
     * @return the animation name to play, or {@code null} when the definition declares none
     */
    public static String animationName(Selection selection) {
        return selection == null ? null : selection.step().map(WeaponDefinition.ReloadStep::animation).orElse(null);
    }

    /**
     * @param selection a selection, may be {@code null}
     * @return the authored duration in seconds, or {@link ReloadRules#DEFAULT_RELOAD_SECONDS} when no usable
     *         sequence or no positive duration is declared
     */
    public static double durationSeconds(Selection selection) {
        double authored = selection == null ? 0.0
                : selection.step().map(WeaponDefinition.ReloadStep::duration).orElse(0.0);
        return authored > 0.0 && Double.isFinite(authored) ? authored : ReloadRules.DEFAULT_RELOAD_SECONDS;
    }

    /**
     * @param steps        the declared {@code reload_sequences}, in file order; may be {@code null}
     * @param magazineAmmo the magazine count at reload start, the value of {@code reload_count}
     * @return the selection; never {@code null}
     */
    public static Selection select(List<WeaponDefinition.ReloadStep> steps, int magazineAmmo) {
        if (steps == null || steps.isEmpty()) {
            return new Selection(Optional.empty(), List.of(), false);
        }

        List<String> unsupported = new ArrayList<>();
        for (WeaponDefinition.ReloadStep step : steps) {
            if (step == null || step.animation() == null || step.animation().isBlank()) {
                continue;
            }
            Boolean matches = evaluate(step.when(), magazineAmmo);
            if (matches == null) {
                unsupported.add(step.when() == null ? "<missing condition>" : step.when());
                continue;
            }
            if (matches) {
                return new Selection(Optional.of(step), unsupported, false);
            }
        }
        return new Selection(firstPlayable(steps), unsupported, true);
    }

    private static Optional<WeaponDefinition.ReloadStep> firstPlayable(List<WeaponDefinition.ReloadStep> steps) {
        for (WeaponDefinition.ReloadStep step : steps) {
            if (step != null && step.animation() != null && !step.animation().isBlank()) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }

    /**
     * @param condition   the raw {@code when} text
     * @param reloadCount the magazine count at reload start
     * @return {@code TRUE}/{@code FALSE} for an understood condition, or {@code null} when the expression is
     *         outside the supported grammar
     */
    public static Boolean evaluate(String condition, int reloadCount) {
        // The grammar lives in ReloadConditions so the idle selector, this selector and the staged reload planner
        // cannot drift apart. A legacy definition has one value, so every variable sees the magazine count.
        return ReloadConditions.evaluateAgainst(condition, reloadCount);
    }
}
