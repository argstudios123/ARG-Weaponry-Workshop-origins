package com.arg_studios.arg_weaponry.weapon.fire;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.state.FireMode;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules that decide which firing modes a weapon supports and which one a stack is using.
 *
 * <p>Two different things are combined here, and they are deliberately kept apart:</p>
 * <ul>
 *   <li>the <strong>supported modes</strong> come from the content pack's ordered {@code firing_mode} array
 *       ({@code ["semi", "auto", "burst"]}) and are a property of the <em>weapon definition</em>;</li>
 *   <li>the <strong>selected mode</strong> is a property of one <em>ItemStack</em>, stored in the
 *       {@code weapon_state} component as a {@link FireMode}.</li>
 * </ul>
 * <p>Nothing here mutates a definition, and no selection is ever stored on the definition: the definition only
 * describes what is possible, the component records what the player chose. That is what lets two stacks of the
 * same weapon hold different modes at the same time.</p>
 *
 * <h2>Resolution and fallback</h2>
 * <p>{@link #resolve(List, FireMode)} answers "which mode should actually be used". A stored mode that the
 * definition supports is taken as it is. A stored mode that it does not support (a pack removed {@code auto}, a
 * save from a build that allowed more modes, a hand-built state) falls back deterministically:
 * {@link FireMode#SINGLE} when the weapon supports it, otherwise the first mode the weapon declares in its
 * authored order. A definition that declares no modes at all resolves to {@code null}, which callers must treat as
 * "this weapon cannot fire" rather than guessing a mode.</p>
 *
 * <h2>Cycling</h2>
 * <p>{@link #next(List, FireMode)} walks the authored array in declaration order and wraps around. The server
 * computes it from its own copy of the definition; the B keybinding only asks for "the next one", it never sends a
 * mode identifier, so a client cannot select a mode its weapon does not support.</p>
 */
public final class FiringModes {

    /** The JSON token that selects {@link FireMode#SINGLE}; never renamed to {@code "single"}. */
    public static final String SEMI_TOKEN = "semi";

    private FiringModes() {
    }

    /**
     * @param definition a parsed definition, may be {@code null}
     * @return the supported modes in authored order; never {@code null}, empty when the weapon declares none
     */
    public static List<FireMode> supported(WeaponDefinition definition) {
        return definition == null ? List.of() : definition.firingModes();
    }

    /**
     * @param supported the supported modes, may be {@code null}
     * @param mode      a mode to test, may be {@code null}
     * @return whether the weapon declares that mode
     */
    public static boolean supports(List<FireMode> supported, FireMode mode) {
        return supported != null && mode != null && supported.contains(mode);
    }

    /**
     * The mode that must actually be executed for a stack.
     *
     * @param supported the weapon's supported modes in authored order, may be {@code null}
     * @param selected  the mode stored on the stack, may be {@code null}
     * @return the selected mode when it is supported, otherwise the documented fallback, or {@code null} when the
     *         weapon supports no mode at all
     */
    public static FireMode resolve(List<FireMode> supported, FireMode selected) {
        if (supported == null || supported.isEmpty()) {
            return null;
        }
        if (selected != null && supported.contains(selected)) {
            return selected;
        }
        if (supported.contains(FireMode.SINGLE)) {
            return FireMode.SINGLE;
        }
        return supported.get(0);
    }

    /**
     * @param supported the weapon's supported modes in authored order
     * @param current   the mode to advance from, may be {@code null}
     * @return the next supported mode, wrapping to the first; the only mode itself when there is exactly one, and
     *         {@code null} when the weapon supports nothing
     */
    public static FireMode next(List<FireMode> supported, FireMode current) {
        if (supported == null || supported.isEmpty()) {
            return null;
        }
        if (supported.size() == 1) {
            return supported.get(0);
        }
        int index = current == null ? -1 : supported.indexOf(current);
        return supported.get((index + 1) % supported.size());
    }

    /**
     * @param definition a parsed definition
     * @return whether switching modes would change anything, i.e. whether more than one mode is declared
     */
    public static boolean hasChoice(WeaponDefinition definition) {
        return supported(definition).size() > 1;
    }

    /**
     * @param supported the supported modes in authored order
     * @return the persistent names, in the same order, for diagnostics and the canonical digest
     */
    public static List<String> names(List<FireMode> supported) {
        if (supported == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>(supported.size());
        for (FireMode mode : supported) {
            names.add(mode.serializedName());
        }
        return List.copyOf(names);
    }
}
