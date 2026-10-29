package com.arg_studios.arg_weaponry.weapon.equip;

import com.arg_studios.arg_weaponry.client.animation.IdleAnimationSelector;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses the draw or holster clip of one weapon. Pure: no Minecraft types, no playback, no state.
 *
 * <h2>The rule, and why it is two rules</h2>
 * <p>A {@code draw_anims} list can contain a <em>first-draw</em> entry, which must never act as the ordinary draw clip.
 * Because that entry usually declares no {@code when} at all, a plain "first entry whose condition matches wins" pass
 * would always pick it - so the first-draw clip is selected by a separate pass that only runs while the stack has not been
 * drawn yet, and the ordinary pass skips every first-draw entry explicitly:</p>
 * <pre>
 *   draw(..., firstDraw = true)   first-draw entries (in file order), then the ordinary entries
 *   draw(..., firstDraw = false)  ordinary entries only - a first-draw entry is invisible here
 * </pre>
 * <p>The fallback matters: a weapon with no first-draw entry, or whose first-draw entry has a {@code when} that does not
 * hold, draws with the ordinary clip exactly as a weapon without the property would.</p>
 *
 * <h2>Conditions and empty magazines</h2>
 * <p>{@code when} is evaluated with the existing grammar and the existing evaluator
 * ({@link IdleAnimationSelector#matches(String, int)}), entries are considered in file order, the first match wins, and a
 * missing or {@code *} condition always matches. An expression the evaluator does not understand never matches and is
 * reported in {@link Selection#unsupportedConditions()} so the caller can log it once instead of guessing.
 * {@link Selection#emptyMagazine()} is the same {@code ammoCount <= 0} fact the sound rules use, so the clip and the
 * sound of one action can never disagree.</p>
 *
 * <h2>Primary selection versus playback candidates</h2>
 * <p>The pure selection answers one question - <em>which clip does the configuration select for this state?</em> - and it is
 * what the server uses for the authoritative window and the sound, because the server has no access to the client's
 * animation documents.</p>
 * <p>The client has one more fact: whether the selected clip actually exists in the weapon's animation resource. A pack can
 * declare {@code anim.model.draw_empty} in {@code draw_anims} while the animation document only contains
 * {@code anim.model.draw}. Playing nothing in that case is worse than playing the general variant, so
 * {@link #drawCandidates} and {@link #holsterCandidates} return the applicable clip first and the <em>remaining</em>
 * declared clips of the same kind after it, in file order. The client walks that list and plays the first clip its
 * animation resource can resolve; the primary selection stays the first element, so the two views can never disagree about
 * which entry is meant.</p>
 *
 * <h2>Two entry points per action</h2>
 * <p>Each action has a pure core that takes the already-parsed entry list and a convenience overload that takes the
 * definition. The cores are what the tests drive, so the selection rules are verified without building a whole weapon
 * definition.</p>
 */
public final class EquipAnimationResolver {

    private EquipAnimationResolver() {
    }

    /**
     * One resolved clip.
     *
     * @param clip                  the animation name, or {@code null} when nothing applies
     * @param durationSeconds       the authored duration of that entry, in seconds; {@code 0} when nothing applies
     * @param firstDraw             {@code true} when this is the stack's first draw - this is what
     *                              {@code WeaponSounds.drawKey} needs in order to choose the {@code draw_first} sound,
     *                              whether or not the weapon declares a first-draw clip
     * @param emptyMagazine         {@code true} when the magazine the action was chosen with is empty
     * @param unsupportedConditions the {@code when} expressions this build cannot evaluate, in file order
     * @param firstDrawEntry        {@code true} only when the chosen entry is itself the {@code first_draw: true} entry,
     *                              i.e. when this draw really consumes the weapon's first-draw animation. A first draw of a
     *                              weapon that declares no such entry is {@code false} here even though
     *                              {@link #firstDraw()} is {@code true}.
     */
    public record Selection(String clip, double durationSeconds, boolean firstDraw, boolean emptyMagazine,
                            List<String> unsupportedConditions, boolean firstDrawEntry) {

        /** Nothing applies. */
        public static final Selection NONE = new Selection(null, 0.0, false, false, List.of(), false);

        public Selection {
            unsupportedConditions = unsupportedConditions == null ? List.of() : List.copyOf(unsupportedConditions);
        }

        /** @return whether a clip was chosen. */
        public boolean isPresent() {
            return clip != null && !clip.isBlank();
        }
    }

    /**
     * The two passes of one action for one state.
     *
     * @param applicable  the entries whose {@code when} applies, in file order
     * @param fallbacks   the declared entries that did not apply, in file order; never a first-draw entry
     * @param unsupported the conditions this build cannot evaluate, in file order
     */
    private record Candidates(List<Selection> applicable, List<Selection> fallbacks, List<String> unsupported) {

        /** @return the primary selection, i.e. the first applicable entry, or {@code null} when none applies */
        Selection primary() {
            return applicable.isEmpty() ? null : applicable.get(0);
        }

        /** @return every clip the client may try, best first */
        List<Selection> playbackOrder() {
            if (fallbacks.isEmpty()) {
                return applicable;
            }
            List<Selection> all = new ArrayList<>(applicable.size() + fallbacks.size());
            all.addAll(applicable);
            all.addAll(fallbacks);
            return List.copyOf(all);
        }
    }

    // ------------------------------------------------------------------ draw

    /**
     * @param definition the weapon definition, may be {@code null}
     * @param ammoCount  the stack's animation ammunition count (see {@code WeaponItem#animationAmmoCount})
     * @param firstDraw  whether the stack's persistent first-draw flag was unset, i.e. this is its first draw
     * @return the selection, never {@code null}; {@link Selection#isPresent()} is {@code false} when the weapon declares no
     *         applicable draw clip at all
     */
    public static Selection selectDraw(WeaponDefinition definition, int ammoCount, boolean firstDraw) {
        if (definition == null) {
            return Selection.NONE;
        }
        return selectDraw(definition.drawAnimations(), ammoCount, firstDraw);
    }

    /**
     * The pure draw selection: the first entry whose condition applies, in the documented order.
     *
     * @param entries   the declared {@code draw_anims}, in file order; may be {@code null} or empty
     * @param ammoCount the animation ammunition count
     * @param firstDraw whether this is the stack's first draw
     * @return the selection, never {@code null}
     */
    public static Selection selectDraw(List<WeaponDefinition.DrawSelection> entries, int ammoCount,
                                       boolean firstDraw) {
        Candidates candidates = resolveDraw(entries, ammoCount, firstDraw);
        Selection primary = candidates.primary();
        return primary != null ? primary
                : new Selection(null, 0.0, firstDraw, ammoCount <= 0, candidates.unsupported(), false);
    }

    /**
     * Every draw clip the client may try for this state, best first.
     *
     * <p>Element {@code 0} is exactly what {@link #selectDraw} returns when it is present, so the client's playback order and
     * the server's authoritative selection can never disagree about which clip is meant; the remaining elements are the
     * declared draw clips that did not apply, used only when the earlier clips cannot be resolved by the weapon's animation
     * resource.</p>
     *
     * @param definition the weapon definition, may be {@code null}
     * @param ammoCount  the animation ammunition count
     * @param firstDraw  whether this is the stack's first draw
     * @return the ordered candidates, possibly empty, never {@code null}
     */
    public static List<Selection> drawCandidates(WeaponDefinition definition, int ammoCount, boolean firstDraw) {
        if (definition == null) {
            return List.of();
        }
        return drawCandidates(definition.drawAnimations(), ammoCount, firstDraw);
    }

    /**
     * The pure draw candidate list.
     *
     * @param entries   the declared {@code draw_anims}, in file order; may be {@code null} or empty
     * @param ammoCount the animation ammunition count
     * @param firstDraw whether this is the stack's first draw
     * @return the ordered candidates, possibly empty, never {@code null}
     */
    public static List<Selection> drawCandidates(List<WeaponDefinition.DrawSelection> entries, int ammoCount,
                                                boolean firstDraw) {
        return resolveDraw(entries, ammoCount, firstDraw).playbackOrder();
    }

    /**
     * @param definition the weapon definition, may be {@code null}
     * @return whether the weapon declares at least one usable {@code first_draw} clip, i.e. whether the persistent first-draw
     *         flag can ever change which animation plays
     */
    public static boolean hasFirstDrawEntry(WeaponDefinition definition) {
        return definition != null && hasFirstDrawEntry(definition.drawAnimations());
    }

    /**
     * The pure form of {@link #hasFirstDrawEntry(WeaponDefinition)}.
     *
     * @param entries the declared {@code draw_anims}, in file order; may be {@code null} or empty
     * @return whether at least one of them is a usable first-draw entry
     */
    public static boolean hasFirstDrawEntry(List<WeaponDefinition.DrawSelection> entries) {
        if (entries == null) {
            return false;
        }
        for (WeaponDefinition.DrawSelection entry : entries) {
            if (entry != null && entry.firstDraw() && hasClip(entry.name())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ holster

    /**
     * @param definition the weapon definition, may be {@code null}
     * @param ammoCount  the stack's animation ammunition count at the moment the holster starts
     * @return the selection, never {@code null}
     */
    public static Selection selectHolster(WeaponDefinition definition, int ammoCount) {
        if (definition == null) {
            return Selection.NONE;
        }
        return selectHolster(definition.holsterAnimations(), ammoCount);
    }

    /**
     * The pure holster selection.
     *
     * @param entries   the declared {@code holster_anims}, in file order; may be {@code null} or empty
     * @param ammoCount the animation ammunition count
     * @return the selection, never {@code null}
     */
    public static Selection selectHolster(List<WeaponDefinition.AnimationSelection> entries, int ammoCount) {
        Candidates candidates = resolveHolster(entries, ammoCount);
        Selection primary = candidates.primary();
        return primary != null ? primary
                : new Selection(null, 0.0, false, ammoCount <= 0, candidates.unsupported(), false);
    }

    /**
     * Every holster clip the client may try for this state, best first.
     *
     * <p>Same shape as {@link #drawCandidates}: the empty variant first, the general variant after it, so a pack that declares
     * a {@code holster_empty} its animation resource does not contain still puts the weapon away visibly.</p>
     *
     * @param definition the weapon definition, may be {@code null}
     * @param ammoCount  the animation ammunition count at the moment the holster starts
     * @return the ordered candidates, possibly empty, never {@code null}
     */
    public static List<Selection> holsterCandidates(WeaponDefinition definition, int ammoCount) {
        if (definition == null) {
            return List.of();
        }
        return holsterCandidates(definition.holsterAnimations(), ammoCount);
    }

    /**
     * The pure holster candidate list.
     *
     * @param entries   the declared {@code holster_anims}, in file order; may be {@code null} or empty
     * @param ammoCount the animation ammunition count
     * @return the ordered candidates, possibly empty, never {@code null}
     */
    public static List<Selection> holsterCandidates(List<WeaponDefinition.AnimationSelection> entries, int ammoCount) {
        return resolveHolster(entries, ammoCount).playbackOrder();
    }

    // ------------------------------------------------------------------ capability

    /**
     * @param definition the weapon definition, may be {@code null}
     * @return whether the weapon declares at least one draw or holster clip, i.e. whether the equip feature applies to it
     */
    public static boolean handles(WeaponDefinition definition) {
        return definition != null && handles(definition.drawAnimations(), definition.holsterAnimations());
    }

    /**
     * @param draws    the declared {@code draw_anims}, may be {@code null}
     * @param holsters the declared {@code holster_anims}, may be {@code null}
     * @return whether at least one clip of either kind is declared
     */
    public static boolean handles(List<WeaponDefinition.DrawSelection> draws,
                                  List<WeaponDefinition.AnimationSelection> holsters) {
        return (draws != null && !draws.isEmpty()) || (holsters != null && !holsters.isEmpty());
    }

    /**
     * @param definition the weapon definition, may be {@code null}
     * @return whether the weapon declares at least one holster clip, which is what a delayed slot switch requires
     */
    public static boolean hasHolsterClip(WeaponDefinition definition) {
        return definition != null && !definition.holsterAnimations().isEmpty();
    }

    // ------------------------------------------------------------------ internals

    /**
     * Builds both passes of the draw rules for one state.
     *
     * @param entries   the declared entries, in file order
     * @param ammoCount the animation ammunition count
     * @param firstDraw whether this is the stack's first draw
     * @return the applicable and fallback selections, never {@code null}
     */
    private static Candidates resolveDraw(List<WeaponDefinition.DrawSelection> entries, int ammoCount,
                                          boolean firstDraw) {
        if (entries == null || entries.isEmpty()) {
            return new Candidates(List.of(), List.of(), List.of());
        }
        List<Selection> applicable = new ArrayList<>(2);
        List<Selection> fallbacks = new ArrayList<>(2);
        List<String> unsupported = new ArrayList<>(0);

        // Pass 1: the first-draw clip, only while the stack has not been drawn. An entry whose condition fails is not a
        // fallback: playing the first-draw clip as a substitute for the ordinary one is exactly what this pass prevents.
        if (firstDraw) {
            for (WeaponDefinition.DrawSelection entry : entries) {
                if (entry == null || !entry.firstDraw() || !hasClip(entry.name())) {
                    continue;
                }
                Boolean applies = IdleAnimationSelector.matches(entry.when(), ammoCount);
                if (applies == null) {
                    unsupported.add(describe(entry.when()));
                    continue;
                }
                if (applies) {
                    applicable.add(withEntry(entry, ammoCount, true, true, unsupported));
                }
            }
        }

        // Pass 2: the ordinary clips whose condition applies, and pass 3: the ones it does not, in the same file order.
        for (WeaponDefinition.DrawSelection entry : entries) {
            if (entry == null || entry.firstDraw() || !hasClip(entry.name())) {
                continue;
            }
            Boolean applies = IdleAnimationSelector.matches(entry.when(), ammoCount);
            if (applies == null) {
                unsupported.add(describe(entry.when()));
                continue;
            }
            if (applies) {
                applicable.add(withEntry(entry, ammoCount, firstDraw, false, unsupported));
            } else {
                fallbacks.add(withEntry(entry, ammoCount, firstDraw, false, unsupported));
            }
        }
        return new Candidates(List.copyOf(applicable), List.copyOf(fallbacks), List.copyOf(unsupported));
    }

    /**
     * Builds both passes of the holster rules for one state.
     *
     * @param entries   the declared entries, in file order
     * @param ammoCount the animation ammunition count
     * @return the applicable and fallback selections, never {@code null}
     */
    private static Candidates resolveHolster(List<WeaponDefinition.AnimationSelection> entries, int ammoCount) {
        if (entries == null || entries.isEmpty()) {
            return new Candidates(List.of(), List.of(), List.of());
        }
        List<Selection> applicable = new ArrayList<>(2);
        List<Selection> fallbacks = new ArrayList<>(2);
        List<String> unsupported = new ArrayList<>(0);
        for (WeaponDefinition.AnimationSelection entry : entries) {
            if (entry == null || !hasClip(entry.name())) {
                continue;
            }
            Boolean applies = IdleAnimationSelector.matches(entry.when(), ammoCount);
            if (applies == null) {
                unsupported.add(describe(entry.when()));
                continue;
            }
            Selection selection = new Selection(entry.name(), entry.duration(), false, ammoCount <= 0,
                    List.copyOf(unsupported), false);
            if (applies) {
                applicable.add(selection);
            } else {
                fallbacks.add(selection);
            }
        }
        return new Candidates(List.copyOf(applicable), List.copyOf(fallbacks), List.copyOf(unsupported));
    }

    private static Selection withEntry(WeaponDefinition.DrawSelection entry, int ammoCount, boolean firstDraw,
                                       boolean firstDrawEntry, List<String> unsupported) {
        return new Selection(entry.name(), entry.duration(), firstDraw, ammoCount <= 0, List.copyOf(unsupported),
                firstDrawEntry);
    }

    private static boolean hasClip(String name) {
        return name != null && !name.isBlank();
    }

    private static String describe(String when) {
        return when == null ? "<missing condition>" : when;
    }
}
