package com.arg_studios.arg_weaponry.weapon.inspect;

import com.arg_studios.arg_weaponry.client.animation.IdleAnimationSelector;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chooses the inspection animation of one weapon from its {@code inspect_anims} entries.
 *
 * <p>The rule is the established animation-selection rule, not a new one: the entries are considered in file order and the
 * first one whose condition applies wins, evaluated with the same {@code ammoCount} grammar the idle and firing
 * selections use ({@link IdleAnimationSelector#matches(String, int)}). Nothing is hard-coded per weapon, and no fallback
 * is invented: a weapon with no {@code inspect_anims}, or whose entries all fail their condition, simply has nothing to
 * inspect, and the caller must then do nothing at all.</p>
 */
public final class InspectionSelection {

    private InspectionSelection() {
    }

    /**
     * The outcome of one selection pass.
     *
     * @param selection             the matching entry, or empty when none applies
     * @param unsupportedConditions the {@code when} expressions this build cannot evaluate, in file order
     */
    public record Result(Optional<WeaponDefinition.AnimationSelection> selection, List<String> unsupportedConditions) {

        public Result {
            selection = selection == null ? Optional.empty() : selection;
            unsupportedConditions = unsupportedConditions == null ? List.of() : List.copyOf(unsupportedConditions);
        }

        /** @return whether an entry applies */
        public boolean isPresent() {
            return selection.isPresent();
        }

        /** @return the selected entry, or {@code null} */
        public WeaponDefinition.AnimationSelection orElseNull() {
            return selection.orElse(null);
        }
    }

    /**
     * @param definition the weapon definition, may be {@code null}
     * @param ammoCount  the stack's authoritative ammunition count
     * @return the matching inspection entry, never {@code null}
     */
    public static Result select(WeaponDefinition definition, int ammoCount) {
        if (definition == null || definition.inspectAnimations().isEmpty()) {
            return new Result(Optional.empty(), List.of());
        }
        List<String> unsupported = new ArrayList<>();
        for (WeaponDefinition.AnimationSelection entry : definition.inspectAnimations()) {
            if (entry == null || entry.name() == null || entry.name().isBlank()) {
                continue;
            }
            Boolean applies = IdleAnimationSelector.matches(entry.when(), ammoCount);
            if (applies == null) {
                unsupported.add(entry.when() == null ? "<missing condition>" : entry.when());
                continue;
            }
            if (applies) {
                return new Result(Optional.of(entry), List.copyOf(unsupported));
            }
        }
        return new Result(Optional.empty(), List.copyOf(unsupported));
    }
}
