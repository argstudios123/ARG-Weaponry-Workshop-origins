package com.arg_studios.arg_weaponry.client.animation;

import java.util.List;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.audio.WeaponActionSounds;
import com.arg_studios.arg_weaponry.weapon.equip.EquipAnimationResolver;
import com.arg_studios.arg_weaponry.weapon.equip.FirstDrawFlag;
import com.arg_studios.arg_weaponry.weapon.equip.FirstDrawGuard;
import com.arg_studios.arg_weaponry.weapon.handoff.HandoffVisuals;
import com.arg_studios.arg_weaponry.weapon.handoff.WeaponIdentity;
import net.minecraft.world.InteractionHand;

/**
 * The client implementation of {@link HandoffVisuals}: the bridge between the handoff state machine and the visual weapon
 * registry.
 *
 * <p>Everything that is Minecraft-aware about the handoff lives here and in {@link ClientHandoffEnvironment}: resolving the
 * clip for a weapon, starting it on that weapon's own animator, and reporting whether it has finished. The state machine
 * itself never sees a stack.</p>
 */
public final class ClientHandoffVisuals implements HandoffVisuals {

    private final InteractionHand hand;

    /**
     * @param hand the hand this port drives
     */
    public ClientHandoffVisuals(InteractionHand hand) {
        this.hand = hand;
    }

    @Override
    public WeaponIdentity activeIdentity() {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        return weapon == null ? WeaponIdentity.NONE : weapon.identity();
    }

    @Override
    public void adopt(WeaponIdentity identity) {
        VisualWeapons.instance().adopt(hand, identity);
    }

    @Override
    public void release() {
        VisualWeapons.instance().release(hand);
    }

    @Override
    public double playHolster() {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        if (weapon == null) {
            return 0.0;
        }
        // The sound belongs to the border of the transition, not to the inventory change: the outgoing weapon is still in the
        // hand while its holster plays, and the legacy held-item lifecycle stays silent for a weapon this feature owns. It is
        // played even when the weapon declares no holster clip, because the weapon is put away either way.
        WeaponActionSounds.holster(weapon.definition(), weapon.ammoAtAdoption() <= 0);
        EquipAnimationResolver.Selection played = playFirst(weapon,
                EquipAnimationResolver.holsterCandidates(weapon.definition(), weapon.ammoAtAdoption()));
        return played == null ? 0.0 : played.durationSeconds();
    }

    @Override
    public boolean holsterFinished() {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        return weapon != null && weapon.equipClipFinished();
    }

    @Override
    public void stopHolster() {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        if (weapon != null) {
            // Ends the holster clip and returns to idle through the animator's own cross-fade, so the weapon settles
            // instead of jumping back to its first idle frame.
            weapon.finishEquip();
        }
    }

    @Override
    public double playDraw() {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        if (weapon == null) {
            Constants.LOG.debug("[ARG] Handoff playDraw: no visual weapon for {}", hand);
            return 0.0;
        }
        boolean firstDraw = FirstDrawFlag.shouldUseFirstDraw(weapon.stack(), weapon.identity().slot(),
                weapon.identity().itemId());
        EquipAnimationResolver.Selection played = playFirst(weapon,
                EquipAnimationResolver.drawCandidates(weapon.definition(), weapon.ammoAtAdoption(), firstDraw));
        if (played == null) {
            Constants.LOG.debug("[ARG] Handoff playDraw: no draw clip for '{}' ammo={} firstDraw={}",
                    weapon.identity().itemId(), weapon.ammoAtAdoption(), firstDraw);
            return 0.0;
        }
        WeaponActionSounds.draw(weapon.definition(), played.firstDraw(), weapon.ammoAtAdoption() <= 0);
        if (played.firstDrawEntry()) {
            // The first-draw animation has been consumed on this client; the guard keeps a fast switch from replaying it
            // before the server's persistent flag arrives.
            FirstDrawGuard.markPlayed(weapon.identity().slot(), weapon.identity().itemId());
        }
        return played.durationSeconds();
    }

    @Override
    public boolean drawFinished() {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        return weapon != null && weapon.equipClipFinished();
    }

    @Override
    public void settleDraw() {
        VisualWeapon weapon = VisualWeapons.instance().active(hand);
        if (weapon != null) {
            weapon.finishEquip();
        }
    }

    /**
     * Starts the first candidate the weapon's animation resource can actually play.
     *
     * <p>This mirrors the previous working implementation's {@code playFirst}: each declared clip is tried directly on the
     * weapon's animator, so a clip the animation document does not contain simply falls through to the next candidate. The
     * failed rewrite instead resolved the clip first ({@code EquipClips}) and only then played it, which let the incoming
     * weapon stay in idle when the resolve step silently disagreed with playback.</p>
     */
    private static EquipAnimationResolver.Selection playFirst(VisualWeapon weapon,
                                                              List<EquipAnimationResolver.Selection> candidates) {
        for (EquipAnimationResolver.Selection candidate : candidates) {
            if (candidate == null || candidate.clip() == null || candidate.clip().isBlank()) {
                continue;
            }
            if (weapon.playEquip(candidate.clip(), candidate.durationSeconds())) {
                return candidate;
            }
        }
        return null;
    }
}
