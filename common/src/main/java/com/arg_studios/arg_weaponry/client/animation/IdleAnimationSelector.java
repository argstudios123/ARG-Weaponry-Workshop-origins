package com.arg_studios.arg_weaponry.client.animation;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chooses an idle animation from the {@code idle_anims} entries a weapon definition declares.
 *
 * <p>Nothing here is hard-coded per weapon: the names, the order and the conditions all come from the
 * content pack. The selector only decides which declared entry currently applies.</p>
 *
 * <h2>Condition support (deliberately narrow)</h2>
 * <p>The schema stores {@code when} as a raw expression and the parser deliberately keeps it unevaluated
 * because the final condition vocabulary is not decided yet. This selector understands exactly one
 * shape:</p>
 * <pre>
 *     ammoCount &lt;op&gt; &lt;integer&gt;      op = == != &gt; &gt;= &lt; &lt;=
 * </pre>
 * <p>An absent or blank condition, or {@code *}, always applies. Anything else is reported as
 * <em>unsupported</em> in {@link Selection#unsupportedConditions()} and never matches — it is not
 * silently treated as true, false, or as another expression. That keeps the current limitation visible
 * instead of pretending a condition language exists.</p>
 */
public final class IdleAnimationSelector {

    /** The only variable the condition evaluator understands. */
    public static final String AMMO_VARIABLE = "ammoCount";

    private IdleAnimationSelector() {
    }

    /**
     * The outcome of one selection pass.
     *
     * @param animation              the animation to play, or empty when no declared entry applies
     * @param unsupportedConditions  the {@code when} expressions this build cannot evaluate, in file order
     */
    public record Selection(Optional<String> animation, List<String> unsupportedConditions) {

        public Selection {
            animation = animation == null ? Optional.empty() : animation;
            unsupportedConditions = unsupportedConditions == null ? List.of() : List.copyOf(unsupportedConditions);
        }

        /** @return {@code true} when at least one declared condition could not be evaluated. */
        public boolean hasUnsupportedConditions() {
            return !unsupportedConditions.isEmpty();
        }
    }

    /**
     * @param idleAnimations the declared {@code idle_anims}, in file order; may be {@code null} or empty
     * @param ammoCount      the current ammunition count, the value of {@value #AMMO_VARIABLE}
     * @return the first entry whose condition applies, plus any conditions that could not be evaluated
     */
    public static Selection select(List<WeaponDefinition.AnimationSelection> idleAnimations, int ammoCount) {
        if (idleAnimations == null || idleAnimations.isEmpty()) {
            return new Selection(Optional.empty(), List.of());
        }

        List<String> unsupported = new ArrayList<>();
        Optional<String> selected = Optional.empty();
        for (WeaponDefinition.AnimationSelection selection : idleAnimations) {
            if (selection == null || selection.name() == null || selection.name().isBlank()) {
                continue;
            }
            Boolean matches = evaluate(selection.when(), ammoCount);
            if (matches == null) {
                unsupported.add(selection.when() == null ? "<missing condition>" : selection.when());
                continue;
            }
            if (matches && selected.isEmpty()) {
                selected = Optional.of(selection.name());
            }
        }
        return new Selection(selected, unsupported);
    }

    /**
     * @param condition the raw {@code when} text
     * @param ammoCount the current ammunition count
     * @return {@code TRUE}/{@code FALSE} for an understood condition, or {@code null} when the expression
     *         is outside the supported grammar
     */
    /**
     * Evaluates one {@code when} expression.
     *
     * <p>Public since Milestone 10R so the inspection selector can use exactly the same condition grammar instead of
     * growing a second one. The semantics are unchanged: an absent, empty or {@code *} condition always applies, and an
     * expression outside the documented shape is reported as {@code null} (unsupported) rather than guessed.</p>
     *
     * @param condition the raw {@code when} text
     * @param ammoCount the current ammunition count
     * @return {@code TRUE}/{@code FALSE} for an understood condition, or {@code null} when it is unsupported
     */
    public static Boolean matches(String condition, int ammoCount) {
        return evaluate(condition, ammoCount);
    }

    private static Boolean evaluate(String condition, int ammoCount) {
        if (condition == null) {
            return Boolean.TRUE;
        }
        String trimmed = condition.trim();
        if (trimmed.isEmpty() || "*".equals(trimmed)) {
            return Boolean.TRUE;
        }

        String[] parts = trimmed.split("\\s+");
        if (parts.length != 3 || !AMMO_VARIABLE.equals(parts[0])) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        return switch (parts[1]) {
            case "==" -> ammoCount == value;
            case "!=" -> ammoCount != value;
            case ">" -> ammoCount > value;
            case ">=" -> ammoCount >= value;
            case "<" -> ammoCount < value;
            case "<=" -> ammoCount <= value;
            default -> null;
        };
    }
}
