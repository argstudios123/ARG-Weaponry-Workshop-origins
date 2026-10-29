package com.arg_studios.arg_weaponry.client.audio;

import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundKey;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The per-action sound entry points.
 *
 * <p>Each method resolves the weapon held in a hand, asks {@link WeaponSounds} which key this action of this weapon uses and
 * hands the result to {@link WeaponAudio}. Nothing here knows a sound identifier, and nothing here decides whether the
 * action happened: every method is called <em>after</em> the server accepted the action, so a rejected action cannot
 * produce audio.</p>
 *
 * <h2>Local versus world</h2>
 * <p>The player's own gunshot is a positional world sound at their eyes; the view-model actions (reload, inspect, draw,
 * holster) and all hit feedback are non-positional UI sounds. Other players' shots are broadcast by the server and never
 * pass through this class, which is what keeps a shot from being heard twice.</p>
 */
public final class WeaponActionSounds {

    private WeaponActionSounds() {
    }

    /**
     * Plays the firing sound the local player's weapon declares, plus the hit feedback for an accepted hit.
     *
     * @param hand      the hand that fired
     * @param hit       whether the shot hit an entity
     * @param headshot  whether the hit was a headshot
     * @param killed    whether the hit killed the entity
     */
    public static void shot(InteractionHand hand, boolean hit, boolean headshot, boolean killed) {
        WeaponDefinition definition = heldDefinition(hand);
        if (definition == null) {
            return;
        }
        WeaponSoundKey firing = WeaponSounds.firingKey(definition.sounds(), false);
        if (firing != null) {
            WeaponAudio.playWorld(definition, firing);
        }
        if (hit) {
            WeaponSoundKey feedback = WeaponSounds.hitKey(definition.sounds(), headshot, killed);
            if (feedback != null) {
                WeaponAudio.playFirstPerson(definition, feedback);
            }
        }
    }

    /**
     * Plays the reload sound of the held weapon.
     *
     * @param hand          the hand that reloaded
     * @param emptyMagazine whether the magazine was empty when the reload started
     */
    public static void reload(InteractionHand hand, boolean emptyMagazine) {
        WeaponDefinition definition = heldDefinition(hand);
        if (definition == null) {
            return;
        }
        // Tracked: a reload cancelled by another action must stop its own remaining audio.
        ActionSoundHandles.track(ActionSoundHandles.Action.RELOAD, hand,
                WeaponAudio.playFirstPersonTracked(definition, WeaponSounds.reloadKey(emptyMagazine)));
    }

    /**
     * Plays the inspection sound of the held weapon.
     *
     * @param hand          the hand that inspected
     * @param emptyMagazine whether the magazine was empty
     */
    public static void inspect(InteractionHand hand, boolean emptyMagazine) {
        WeaponDefinition definition = heldDefinition(hand);
        if (definition == null) {
            return;
        }
        // Tracked: firing, a reload or a weapon change interrupts an inspection, and the interrupted inspection must not
        // keep playing its remaining audio.
        ActionSoundHandles.track(ActionSoundHandles.Action.INSPECT, hand,
                WeaponAudio.playFirstPersonTracked(definition, WeaponSounds.inspectKey(emptyMagazine)));
    }

    /**
     * Stops the audio of the actions a shot or another dominating action just interrupted.
     *
     * @param hand the hand whose inspection ended
     */
    public static void stopInspection(InteractionHand hand) {
        ActionSoundHandles.stop(ActionSoundHandles.Action.INSPECT, hand);
    }

    /**
     * Stops the audio of a cancelled reload.
     *
     * @param hand the hand whose reload was cancelled
     */
    public static void stopReload(InteractionHand hand) {
        ActionSoundHandles.stop(ActionSoundHandles.Action.RELOAD, hand);
    }

    /**
     * Plays the draw sound of a weapon that has just been taken out.
     *
     * @param definition the weapon being drawn, may be {@code null}
     * @param firstDraw  whether this is its first draw in this session
     * @param emptyMagazine whether its magazine is empty
     */
    public static void draw(WeaponDefinition definition, boolean firstDraw, boolean emptyMagazine) {
        if (definition == null) {
            return;
        }
        WeaponSoundKey key = WeaponSounds.drawKey(definition.sounds(), firstDraw, emptyMagazine);
        if (key != null) {
            WeaponAudio.playFirstPerson(definition, key);
        }
    }

    /**
     * Plays the holster sound of a weapon that has just been put away.
     *
     * @param definition    the weapon being holstered, may be {@code null}
     * @param emptyMagazine whether its magazine was empty
     */
    public static void holster(WeaponDefinition definition, boolean emptyMagazine) {
        if (definition == null) {
            return;
        }
        WeaponSoundKey key = WeaponSounds.holsterKey(definition.sounds(), emptyMagazine);
        if (key != null) {
            WeaponAudio.playFirstPerson(definition, key);
        }
    }

    /**
     * @param hand the hand to inspect
     * @return the definition of the weapon held there, or {@code null}
     */
    public static WeaponDefinition heldDefinition(InteractionHand hand) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || hand == null) {
            return null;
        }
        ItemStack stack = player.getItemInHand(hand);
        return stack.getItem() instanceof WeaponItem weapon ? weapon.definition() : null;
    }
}
