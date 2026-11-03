package com.arg_studios.arg_weaponry.weapon.reload;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the {@code when} conditions of reload entries.
 *
 * <p>The parser keeps {@code when} as raw text on purpose, so every selector that needs it evaluates it here instead
 * of growing a second expression language. The grammar is deliberately one comparison:</p>
 * <pre>
 *     &lt;variable&gt; &lt;op&gt; &lt;integer&gt;        op = == != &gt; &gt;= &lt; &lt;=
 * </pre>
 * <p>Several comparisons may be joined with {@code &amp;&amp;} (all must hold). An absent, blank or {@code *} condition
 * always applies. Anything else - a variable this build does not know, a fraction, a disjunction - is
 * <em>unsupported</em>: it yields {@code null} rather than being read as true or false, so the limitation stays
 * visible instead of silently changing what a pack means.</p>
 *
 * <h2>Variables</h2>
 * <ul>
 *     <li>{@code reload_count}, {@code ammoCount} - the magazine count <em>when the reload starts</em>, zero-based. These
 *     are the original reload spelling and the idle/fire selector spelling, and the shipped content writes
 *     {@code ammoCount == 0} for an empty magazine, so they stay a plain count.</li>
 *     <li>{@code ammo_count} - the same magazine count in the <em>staged reload</em> spelling, reported the way the
 *     staged schema (and the reference implementation) spell "nothing loaded": {@code -1} when the magazine held no
 *     rounds, the count otherwise. That is what makes
 *     {@code prepare … "when": "ammo_count == -1"} the empty-magazine opening clip and
 *     {@code "when": "ammo_count >= 0"} the standard one - two entries of one stage that cannot both match.</li>
 *     <li>{@code reload_inserted_count} - the reload iteration counter, spelled as it is in the schema. Its lifecycle is
 *     the reference implementation's: preparation seeds it with {@code magazineAmmo - 1}, every load iteration increments
 *     it <em>before</em> that iteration's condition is evaluated and its rounds are inserted, and completion resets it to
 *     {@code 0}. With one round per iteration it therefore reports the rounds the magazine held when the iteration
 *     began - {@code 0} for the first shell of an empty magazine, {@code 1} for the second, and {@code 3} for the first
 *     shell of a magazine that already held three. It is <em>not</em> the number of rounds this reload has inserted: a
 *     partially loaded magazine never starts at {@code 0}, which is what makes the AA12/M950-style
 *     {@code == 0} / {@code == 1} / {@code > 1} ladders work.</li>
 * </ul>
 *
 * <p>The three magazine spellings are therefore one value with one documented difference in <em>empty</em>: the staged
 * spelling answers {@code -1}, the other two answer {@code 0}. A pack must not mix the spellings inside one condition,
 * because a comparison against {@code 0} and one against {@code -1} mean different things.</p>
 *
 * <h2>Compatibility</h2>
 * <p>{@link ReloadSequenceSelector} keeps its public API and its documented meaning; it simply delegates the parsing to
 * this class and passes the same value for both variables, which is exactly what it did before.</p>
 */
public final class ReloadConditions {

    /** The magazine count at reload start, spelled the way the original schema spells it. */
    public static final String RELOAD_COUNT_VARIABLE = "reload_count";

    /** The magazine count at reload start, spelled the way the idle/fire selectors spell it. */
    public static final String AMMO_COUNT_VARIABLE = "ammoCount";

    /** The magazine count at reload start, snake_case spelling used by the staged reload examples. */
    public static final String AMMO_COUNT_SNAKE_VARIABLE = "ammo_count";

    /** How many rounds the running reload has already inserted. */
    public static final String INSERTED_COUNT_VARIABLE = "reload_inserted_count";

    private static final String CONJUNCTION = "&&";

    private ReloadConditions() {
    }

    /**
     * @param condition      the raw {@code when} text, may be {@code null}
     * @param magazineAmmo   the magazine count at reload start
     * @param reloadIteration the value of {@code reload_inserted_count} for the stage being selected: the reload
     *                        iteration counter, which the planner seeds with {@code magazineAmmo - 1}, increments before
     *                        every load iteration and resets to {@code 0} for the closing stages
     * @return {@link Boolean#TRUE}/{@link Boolean#FALSE} for an understood condition, or {@code null} when it is outside
     *         the supported grammar
     */
    public static Boolean evaluate(String condition, int magazineAmmo, int reloadIteration) {
        return evaluateInternal(condition, magazineAmmo, reloadIteration);
    }

    private static Boolean evaluateInternal(String condition, int magazineAmmo, Integer insertedRounds) {
        if (condition == null) {
            return Boolean.TRUE;
        }
        String trimmed = condition.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return Boolean.TRUE;
        }

        Boolean result = null;
        for (String part : trimmed.split(CONJUNCTION)) {
            Boolean single = evaluateSingle(part.trim(), magazineAmmo, insertedRounds);
            if (single == null) {
                // One unsupported comparison makes the whole condition unsupported, never partially true.
                return null;
            }
            if (!single) {
                result = Boolean.FALSE;
            } else if (result == null) {
                result = Boolean.TRUE;
            }
        }
        return result;
    }

    /**
     * Evaluates one condition against a single value, the shape the idle and fire selectors use.
     *
     * <p>The insertion count is deliberately not available here: a single-clip reload has no insertion cycles, so
     * {@code reload_inserted_count} stays an unsupported expression instead of being confused with the magazine count.</p>
     *
     * @param condition the raw expression
     * @param value     the magazine count the condition is evaluated against
     * @return the result, or {@code null} when the expression is unsupported
     */
    public static Boolean evaluateAgainst(String condition, int value) {
        return evaluateInternal(condition, value, null);
    }

    private static Boolean evaluateSingle(String condition, int magazineAmmo, Integer insertedRounds) {
        String[] parts = condition.split("\\s+");
        if (parts.length != 3) {
            return null;
        }
        Integer left = valueOf(parts[0], magazineAmmo, insertedRounds);
        if (left == null) {
            return null;
        }
        int right;
        try {
            right = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        return switch (parts[1]) {
            case "==" -> left == right;
            case "!=" -> left != right;
            case ">" -> left > right;
            case ">=" -> left >= right;
            case "<" -> left < right;
            case "<=" -> left <= right;
            default -> null;
        };
    }

    private static Integer valueOf(String variable, int magazineAmmo, Integer insertedRounds) {
        return switch (variable) {
            case RELOAD_COUNT_VARIABLE, AMMO_COUNT_VARIABLE -> magazineAmmo;
            case AMMO_COUNT_SNAKE_VARIABLE -> loadedAmmo(magazineAmmo);
            case INSERTED_COUNT_VARIABLE -> insertedRounds;
            default -> null;
        };
    }

    /**
     * The value the staged spelling {@code ammo_count} reports.
     *
     * <p>A staged reload needs to tell "this weapon is empty" apart from "it holds some rounds" with one comparison, and
     * the staged schema spells empty as {@code -1} (the reference implementation's "nothing loaded" sentinel). The
     * camelCase spelling keeps the plain count because the idle and fire selectors compare it against {@code 0}.</p>
     *
     * @param magazineAmmo the rounds in the magazine at reload start
     * @return {@code -1} when the magazine held nothing, otherwise the round count
     */
    private static int loadedAmmo(int magazineAmmo) {
        return magazineAmmo <= 0 ? -1 : magazineAmmo;
    }

    /**
     * @param condition the raw text
     * @return the variables the condition mentions, in file order; useful for diagnostics
     */
    public static List<String> variables(String condition) {
        List<String> variables = new ArrayList<>();
        if (condition == null) {
            return variables;
        }
        for (String part : condition.trim().split(CONJUNCTION)) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] pieces = trimmed.split("\\s+");
            if (pieces.length == 3) {
                variables.add(pieces[0]);
            }
        }
        return List.copyOf(variables);
    }
}
