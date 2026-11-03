package com.arg_studios.arg_weaponry.weapon.handoff;

import java.util.List;
import java.util.Optional;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.client.render.WeaponDisplayCache;
import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.animation.BedrockAnimation;
import com.arg_studios.arg_weaponry.weapon.animation.ReusableAnimations;
import com.arg_studios.arg_weaponry.weapon.equip.EquipAnimationResolver;

/**
 * Resolves the two clips a handoff needs, together with the length the transition must use.
 *
 * <p>This is the adapter over the existing, unchanged declaration vocabulary: {@code holster_anims} and {@code draw_anims},
 * with their {@code when} conditions and the {@code first_draw} entry, are still resolved by
 * {@link EquipAnimationResolver}. What is new is the second half of the answer: the <em>authored length</em> taken from the
 * animation resource itself, which is what the transition window is built from.</p>
 *
 * <p>Declared-but-missing clips are treated as absent. A pack that lists a clip its animation document does not contain
 * therefore produces "no holster clip" and the state machine takes the immediate-handoff path, instead of waiting for an
 * animation that will never play.</p>
 */
public final class EquipClips {

    /**
     * One resolved clip.
     *
     * @param name      the clip name in the animation document
     * @param seconds   the authored length of that clip
     * @param firstDraw whether this clip is the weapon's first draw, so the caller can consume the first-draw flag
     */
    public record Clip(String name, double seconds, boolean firstDraw) {

        public Clip {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("A clip must name itself");
            }
            if (!Double.isFinite(seconds) || seconds <= 0.0) {
                throw new IllegalArgumentException("A clip must have a positive length");
            }
        }
    }

    private EquipClips() {
    }

    /**
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon whose holster clip is wanted
     * @param ammoCount  the stack's ammunition count, the declared selection input
     * @return the first declared holster clip the animation resource can actually provide
     */
    public static Optional<Clip> holster(ContentPack pack, WeaponDefinition definition, int ammoCount) {
        if (pack == null || definition == null) {
            return Optional.empty();
        }
        return firstAvailable(pack, definition, EquipAnimationResolver.holsterCandidates(definition, ammoCount));
    }

    /**
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon whose draw clip is wanted
     * @param ammoCount  the stack's ammunition count, the declared selection input
     * @param firstDraw  whether the weapon still has to play its first draw
     * @return the first declared draw clip the animation resource can actually provide
     */
    public static Optional<Clip> draw(ContentPack pack, WeaponDefinition definition, int ammoCount, boolean firstDraw) {
        if (pack == null || definition == null) {
            return Optional.empty();
        }
        return firstAvailable(pack, definition, EquipAnimationResolver.drawCandidates(definition, ammoCount, firstDraw),
                firstDraw);
    }

    /**
     * @param pack       the content pack that owns the weapon
     * @param definition the weapon
     * @param clipName   the clip to measure
     * @return the authored length of that clip, or {@code 0} when the resource does not contain it
     */
    public static double authoredSeconds(ContentPack pack, WeaponDefinition definition, String clipName) {
        if (pack == null || definition == null || clipName == null || clipName.isBlank()) {
            return 0.0;
        }
        WeaponDisplay display = WeaponDisplayCache.instance().get(pack, definition).orElse(null);
        if (display == null
                || (display.animations() == null && definition.reusableDefaultAnimations().isEmpty())) {
            return 0.0;
        }
        Optional<BedrockAnimation> animation =
                ReusableAnimations.animation(pack, definition, display.animations(), clipName);
        return animation.map(BedrockAnimation::lengthSeconds).orElse(0.0);
    }

    private static Optional<Clip> firstAvailable(ContentPack pack, WeaponDefinition definition,
                                                 List<EquipAnimationResolver.Selection> candidates) {
        return firstAvailable(pack, definition, candidates, false);
    }

    private static Optional<Clip> firstAvailable(ContentPack pack, WeaponDefinition definition,
                                                 List<EquipAnimationResolver.Selection> candidates,
                                                 boolean firstDrawCandidate) {
        for (EquipAnimationResolver.Selection candidate : candidates) {
            if (candidate == null || candidate.clip() == null || candidate.clip().isBlank()) {
                Constants.LOG.debug("[ARG] EquipClips: skipping a null or blank candidate");
                continue;
            }
            double seconds = authoredSeconds(pack, definition, candidate.clip());
            Constants.LOG.debug("[ARG] EquipClips: candidate '{}' authoredSeconds={}", candidate.clip(), seconds);
            if (seconds > 0.0) {
                return Optional.of(new Clip(candidate.clip(), seconds, firstDrawCandidate || candidate.firstDrawEntry()));
            }
        }
        Constants.LOG.debug("[ARG] EquipClips: no candidate resolved a positive length");
        return Optional.empty();
    }
}
