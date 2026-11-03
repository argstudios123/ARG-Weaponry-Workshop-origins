package com.arg_studios.arg_weaponry.client.render;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.display.WeaponDisplay;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.model.WeaponModel;
import com.arg_studios.arg_weaponry.weapon.muzzle.MuzzleFlashAttachment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The client's muzzle-flash state: one animation per hand, started by an <em>accepted</em> shot.
 *
 * <p>The flash is a reaction, never a prediction, so the only entry point is
 * {@link com.arg_studios.arg_weaponry.client.input.WeaponFireInput#onAcceptedShot} - the same place that already starts
 * the firing animation, the shot sound and the recoil. That handler is reached only when the server accepted the shot,
 * and it is reached for semi-automatic, automatic and burst fire alike because every accepted shot produces a
 * {@code WeaponFireResultPayload}. A refused request produces no payload at all, so a rejected shot cannot flash.</p>
 *
 * <h2>One independent animation per shot</h2>
 * <p>Every accepted shot records its own flash - its own start time and its own random seed - and the flashes of one hand
 * coexist until they expire, which is the reference implementation's model: it appends a newly built effect to the gun
 * state's effect list and removes it once {@code isExpired()} reports it over. A fast weapon therefore shows overlapping
 * flashes instead of restarting one, and two shots never share random state.</p>
 * <p>Records are pruned when they are queried and when a new shot arrives, so the state cannot grow without bound and
 * nothing has to be cleaned up on a frame that draws nothing.</p>
 *
 * <h2>Which attachment point</h2>
 * <p>The configured bone list is filtered against the weapon's parsed model when the shot happens
 * ({@link MuzzleFlashAttachment}), so a name the model does not contain can never be chosen. One usable point is used
 * every shot; several are chosen at random, one per shot. The choice is made once per shot and stored, so a single
 * animation cannot jump between attachment points.</p>
 */
public final class MuzzleFlash {

    /**
     * One running flash.
     *
     * @param bone           the attachment bone the flash is drawn at
     * @param elapsedSeconds how long ago the shot happened, in seconds
     * @param seed           the random seed this shot drew, so a {@code random} sheet picks its own sprite sequence
     */
    public record Active(String bone, double elapsedSeconds, int seed) {
    }

    /**
     * The flashes running on each hand.
     *
     * <p>{@link MuzzleFlashState} owns the list, the lifetime and the cleanup; this class only decides which bone a shot
     * uses and what the renderer needs to know. Keeping the lifecycle free of Minecraft types is what makes it testable
     * without a client.</p>
     */
    private static final MuzzleFlashState<InteractionHand, WeaponItem> SHOTS = new MuzzleFlashState<>();

    /** Report markers, so a broken configuration is explained once instead of once per shot. */
    private static final Set<String> REPORTED = new HashSet<>();

    /** The per-shot randomness used to choose between several attachment points. */
    private static final Random RANDOM = new Random();

    private MuzzleFlash() {
    }

    /**
     * Starts one muzzle flash for an accepted shot.
     *
     * <p>Called on the client thread from the accepted-shot handler. Everything that can prevent a flash is checked
     * here, once per shot rather than once per frame: the weapon must exist, its {@code muzzle_flash} block must be
     * usable, and at least one configured attachment point must exist in the model.</p>
     *
     * <p>Each shot adds its own flash; it does not restart the previous one, so a weapon firing faster than the flash
     * lasts shows the overlapping flashes the reference implementation shows.</p>
     *
     * @param hand the hand that fired
     */
    public static synchronized void onAcceptedShot(InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft == null ? null : minecraft.player;
        if (player == null || hand == null) {
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem item)) {
            return;
        }
        WeaponDefinition.MuzzleFlash configuration = item.definition().muzzleFlash();
        if (configuration == null || !configuration.isUsable()) {
            return;
        }

        WeaponModel model = modelFor(item);
        if (model == null) {
            reportOnce(item, "model", "The weapon '" + item.definition().name() + "' declares a muzzle flash but its "
                    + "model could not be resolved, so no flash can be placed");
            return;
        }

        for (String bone : MuzzleFlashAttachment.missing(configuration.bones(), model)) {
            reportOnce(item, "bone|" + bone, "The muzzle flash attachment bone '" + bone + "' of weapon '"
                    + item.definition().name() + "' is not part of model '" + model.id() + "'; it is ignored");
        }

        String bone = MuzzleFlashAttachment.select(MuzzleFlashAttachment.present(configuration.bones(), model), RANDOM);
        if (bone == null) {
            reportOnce(item, "no-bone", "The weapon '" + item.definition().name() + "' declares no muzzle flash "
                    + "attachment bone that exists in its model, so no flash can be drawn");
            return;
        }

        // One independent flash per shot, exactly like the reference implementation, where every shot builds a new
        // effect and every effect draws its own random seed. The seed is drawn here - once per shot - so a `random`
        // sprite sheet shows a different sequence for every shot while staying stable within one frame interval.
        //
        // The lifetime is the configured `duration_ms` when the pack declares one, so the flash stays on screen for that
        // long no matter how many frames the sheet has or how fast they advance; a pack that declares none keeps the
        // historical behaviour, where the animation's own frames decide.
        double lifetimeSeconds = configuration.hasDuration()
                ? configuration.durationSeconds()
                : MuzzleFlashState.DEFAULT_LIFETIME_SECONDS;
        record(hand, item, bone, System.nanoTime(), RANDOM.nextInt(), lifetimeSeconds);
    }

    /**
     * Remembers one flash and forgets the ones that have expired.
     *
     * <p>Package-private and time-explicit so the lifecycle can be verified without a running client; production reaches
     * it through {@link #onAcceptedShot(InteractionHand)}.</p>
     *
     * @param hand       the hand that fired
     * @param item       the weapon that fired
     * @param bone       the attachment bone chosen for this shot
     * @param startNanos when the animation starts, in {@link System#nanoTime()} units
     * @param seed            the seed this shot's sprite selection will use
     * @param lifetimeSeconds how long the flash stays visible, in seconds
     */
    static synchronized void record(InteractionHand hand, WeaponItem item, String bone, long startNanos, int seed,
                                    double lifetimeSeconds) {
        SHOTS.add(hand, item, bone, startNanos, seed, lifetimeSeconds);
    }

    /**
     * Returns the running flashes of one hand, if they belong to the weapon being drawn.
     *
     * <p>Overlapping shots each keep their own start time and seed, so a fast weapon shows two independent flashes
     * instead of restarting one - which is what the reference implementation does by keeping a list of effects per gun
     * state. Entries that have outlived their own lifetime are dropped here, so a frame that draws nothing still costs
     * nothing afterwards.</p>
     *
     * @param hand the hand being rendered
     * @param item the weapon being drawn in that hand
     * @return the active flashes, oldest first; empty when there is nothing to draw
     */
    public static synchronized List<Active> activeFor(InteractionHand hand, WeaponItem item) {
        return activeAt(hand, item, System.nanoTime());
    }

    /**
     * @param hand     the hand being rendered
     * @param item     the weapon being drawn in that hand
     * @param nowNanos the frame's time, in {@link System#nanoTime()} units
     * @return the flashes that are still running for that weapon, oldest first
     */
    static synchronized List<Active> activeAt(InteractionHand hand, WeaponItem item, long nowNanos) {
        List<MuzzleFlashState.Flash<WeaponItem>> flashes = SHOTS.active(hand, item, nowNanos);
        if (flashes.isEmpty()) {
            return List.of();
        }
        List<Active> active = new ArrayList<>(flashes.size());
        for (MuzzleFlashState.Flash<WeaponItem> flash : flashes) {
            double elapsedSeconds = (nowNanos - flash.startNanos()) / 1.0E9D;
            active.add(new Active(flash.bone(), elapsedSeconds, flash.seed()));
        }
        return active;
    }

    /** Stops every flash and forgets the report markers; called on a resource reload. */
    public static synchronized void invalidate() {
        SHOTS.clear();
        REPORTED.clear();
    }

    /** Forgets the state of both hands; called when the client leaves a world. */
    public static synchronized void reset() {
        SHOTS.clear();
    }

    /**
     * Resolves the model of a weapon the same way the renderer does: display configuration, then geometry.
     *
     * @param item the weapon item
     * @return the parsed model, or {@code null} when either resource is missing
     */
    private static WeaponModel modelFor(WeaponItem item) {
        WeaponDisplay display = WeaponDisplayCache.instance().get(item.pack(), item.definition()).orElse(null);
        if (display == null) {
            return null;
        }
        return WeaponModelCache.instance().get(item.pack(), display.model());
    }

    private static void reportOnce(WeaponItem item, String key, String message) {
        if (REPORTED.add(item.pack().id() + "|" + item.definition().name() + "|" + key)) {
            Constants.LOG.warn("[ARG] {}", message);
        }
    }
}
