package com.arg_studios.arg_weaponry.client.audio;

import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Set;

/**
 * The draw and holster lifecycle that the {@code draw*}/{@code holster*} sounds hang off.
 *
 * <h2>The event this defines</h2>
 * <p>The project has no dedicated draw/holster action, so this class defines the event explicitly and in the only way a
 * first-person weapon actually has one: <strong>a weapon becomes the main-hand held item, or stops being it</strong>. The
 * comparison is on the item's registered identifier, so moving the same weapon to another slot is not a draw, and
 * switching between two weapons is one holster of the old one and one draw of the new one.</p>
 *
 * <h2>First draw</h2>
 * <p>{@code draw_first} is deliberately limited: it plays only the first time a given weapon item is drawn since the
 * client joined the world. Every later draw of that item uses {@code draw} (or {@code draw_empty} on an empty magazine),
 * which is the semantics the schema's name implies and the behaviour this class documents. The set of seen weapons is
 * cleared whenever the client leaves the world, so a rejoin starts over.</p>
 *
 * <h2>Magazine state</h2>
 * <p>The empty variants use the stack's authoritative ammunition at the moment of the event: for a draw that is the
 * magazine when the weapon is taken out, for a holster the magazine at the moment it is put away. An unlimited-capacity
 * weapon is never empty, which is exactly what {@link WeaponItem#animationAmmoCount(ItemStack)} reports.</p>
 */
public final class WeaponHeldLifecycle {

    private static final Set<String> DRAWN_THIS_SESSION = new HashSet<>();

    private static String lastItemId;
    private static WeaponDefinition lastDefinition;
    private static boolean lastEmpty;

    private WeaponHeldLifecycle() {
    }

    /**
     * Advances the lifecycle by one client tick.
     *
     * <p>Called once per client tick from both loaders' client entry points. It does nothing while the player is
     * unavailable or dead, because a death or a disconnect is not a holster.</p>
     *
     * @param minecraft the client
     */
    public static void clientTick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || !player.isAlive() || minecraft.level == null) {
            return;
        }
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        String currentId = null;
        WeaponDefinition currentDefinition = null;
        boolean currentEmpty = false;
        if (stack.getItem() instanceof WeaponItem weapon) {
            currentId = String.valueOf(BuiltInRegistries.ITEM.getKey(weapon));
            currentDefinition = weapon.definition();
            currentEmpty = weapon.animationAmmoCount(stack) <= 0;
        }

        if (currentId == null ? lastItemId == null : currentId.equals(lastItemId)) {
            // Same held item: keep the remembered magazine state current so a holster uses the right variant.
            lastEmpty = currentEmpty;
            return;
        }

        if (lastItemId != null) {
            // The weapon is no longer held, so its actions end: their tracked audio stops here instead of outliving the
            // weapon it belonged to.
            ActionSoundHandles.stopAll(InteractionHand.MAIN_HAND, "the held item changed");
            WeaponActionSounds.holster(lastDefinition, lastEmpty);
        }
        if (currentId != null) {
            boolean firstDraw = DRAWN_THIS_SESSION.add(currentId);
            WeaponActionSounds.draw(currentDefinition, firstDraw, currentEmpty);
        }

        lastItemId = currentId;
        lastDefinition = currentDefinition;
        lastEmpty = currentEmpty;
    }

    /**
     * Forgets the lifecycle; called when the client leaves a world so a rejoin starts with a clean first-draw state and
     * without holstering a weapon the player no longer holds.
     */
    public static void reset() {
        DRAWN_THIS_SESSION.clear();
        lastItemId = null;
        lastDefinition = null;
        lastEmpty = false;
    }
}
