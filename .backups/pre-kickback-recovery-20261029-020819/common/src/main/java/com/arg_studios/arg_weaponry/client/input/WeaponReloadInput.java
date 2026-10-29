package com.arg_studios.arg_weaponry.client.input;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.client.animation.WeaponViewControllers;
import com.arg_studios.arg_weaponry.client.audio.WeaponActionSounds;
import com.arg_studios.arg_weaponry.client.audio.WeaponReloadSounds;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.network.WeaponReloadPayload;
import com.arg_studios.arg_weaponry.network.WeaponReloadResultPayload;
import com.arg_studios.arg_weaponry.platform.Services;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult;
import com.arg_studios.arg_weaponry.weapon.reload.ReloadRequestTracker;
import com.arg_studios.arg_weaponry.weapon.reload.ReloadRules;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Client side of reloading: turns the reload key into a request, and a server outcome into animation and sound.
 *
 * <p>Nothing here predicts or decides anything. The client may ask for a reload, and it animates and sounds a
 * reload only after the server accepted one. It never sends an ammunition count, an item id, a duration or an
 * animation name, and it never writes {@code WeaponState}.</p>
 *
 * <h2>One request at a time, any number of times</h2>
 * <p>Vanilla queues at most one click per press for a {@code KeyMapping}, and the key handler drains that queue
 * with {@code consumeClick()}, so holding R produces one request. While a request is in flight no second one is
 * sent. Which requests are in flight is decided by {@link ReloadRequestTracker}, which keys the start and the end
 * of a reload separately: both carry the <em>same</em> sequence number, and Milestone 10M's single
 * "last sequence" guard discarded the end as a duplicate, leaving the client unable to reload again for the rest
 * of the session. See that class for the corrected rules and for the expiry that keeps a lost outcome from
 * blocking reloading forever.</p>
 *
 * <h2>Which hand</h2>
 * <p>Firing is main-hand only, because that is the hand the attack key attacks with. Reloading has no such
 * constraint, so this class uses a deterministic policy: the <strong>main hand</strong> if it holds a weapon with
 * a magazine, otherwise the <strong>off-hand</strong> if that one does, otherwise nothing. At most one hand is
 * ever reloading on the client, which keeps animation and sound ownership unambiguous.</p>
 */
public final class WeaponReloadInput {

    /** The request/outcome state machine; see its class documentation for the sequence rules. */
    private static final ReloadRequestTracker TRACKER = new ReloadRequestTracker();

    private static int nextSequence;

    private WeaponReloadInput() {
    }

    /**
     * @param player the local player, may be {@code null}
     * @param hand   the hand to inspect
     * @return whether that hand holds a registered weapon with a finite magazine; a weapon whose ammunition is
     *         unlimited has nothing to reload, so no request is ever sent for it
     */
    public static boolean holdsReloadableWeapon(LocalPlayer player, InteractionHand hand) {
        if (player == null || hand == null) {
            return false;
        }
        ItemStack stack = player.getItemInHand(hand);
        return stack.getItem() instanceof WeaponItem weapon
                && weapon.ammoCapacity() != null && weapon.ammoCapacity().isFinite();
    }

    /**
     * @param player the local player, may be {@code null}
     * @return the hand a reload should be requested for, or {@code null} when neither hand holds a weapon
     */
    public static InteractionHand reloadHand(LocalPlayer player) {
        if (holdsReloadableWeapon(player, InteractionHand.MAIN_HAND)) {
            return InteractionHand.MAIN_HAND;
        }
        if (holdsReloadableWeapon(player, InteractionHand.OFF_HAND)) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    /**
     * Sends one reloading request for the policy-selected hand, if one applies.
     *
     * @param player the local player, may be {@code null}
     */
    public static void requestReload(LocalPlayer player) {
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // Drop a request whose outcome never arrived, so a lost payload cannot block reloading forever.
        TRACKER.expire(now);
        if (TRACKER.activeHand() != null) {
            // A reload is already running; the key press is answered by the state the player already has.
            return;
        }
        InteractionHand hand = reloadHand(player);
        if (hand == null) {
            return;
        }
        Services.NETWORK.sendToServer(new WeaponReloadPayload(hand, ++nextSequence));
    }

    /**
     * Applies one server outcome. Called on the client thread from the networking layer.
     *
     * <p>The outcome is first classified by {@link ReloadRequestTracker}: a start, an end or a refusal, each with
     * the sequence number it answers. Only an outcome the tracker accepts changes anything, so replays and stale
     * terminals are dropped while the <em>end</em> of the request that is actually in flight is always applied -
     * that is the state transition Milestone 10M lost, and the reason a second reload was impossible.</p>
     *
     * @param payload the outcome
     */
    public static void onResult(WeaponReloadResultPayload payload) {
        if (payload == null) {
            return;
        }
        InteractionHand hand = payload.hand();
        long now = System.currentTimeMillis();
        ReloadRequestTracker.Kind kind = payload.started()
                ? ReloadRequestTracker.Kind.STARTED
                : payload.endsReload() ? ReloadRequestTracker.Kind.ENDED
                : ReloadRequestTracker.Kind.REFUSED;

        ReloadRequestTracker.Decision decision = TRACKER.accept(hand, payload.sequence(), kind, now);
        switch (decision) {
            case APPLY_START -> {
                long durationMillis = Math.round(payload.durationTicks() * 1000.0 / ReloadRules.TICKS_PER_SECOND);
                TRACKER.armExpiry(durationMillis, now);
                double seconds = payload.durationTicks() / (double) ReloadRules.TICKS_PER_SECOND;
                if (!WeaponViewControllers.instance().playReloadAnimation(hand, payload.animation(), seconds)) {
                    Constants.LOG.debug("[ARG] The reload in {} was accepted but no reload animation started",
                            hand);
                }
                // A reload replaces an inspection, so the inspection's remaining audio is stopped with its clip.
                WeaponActionSounds.stopInspection(hand);
                WeaponInspectInput.cancelActive();
                WeaponReloadSounds.play(hand, payload.emptyMagazine());
            }
            case APPLY_END -> {
                WeaponViewControllers.instance().finishReloadAnimation(hand);
                if (payload.result() != WeaponActionResult.RELOAD_COMPLETED) {
                    // Only a reload that ended early stops its audio; a completed reload's sound is allowed to ring out.
                    WeaponActionSounds.stopReload(hand);
                }
                Constants.LOG.debug("[ARG] The reload in {} ended: {}", hand, payload.result());
            }
            case APPLY_REFUSAL -> Constants.LOG.debug("[ARG] The reload request in {} was refused: {}", hand,
                    payload.result());
            case IGNORE -> Constants.LOG.debug("[ARG] Ignored a stale or duplicated reload outcome {} for {}",
                    payload.sequence(), hand);
            default -> {
                // Unreachable: every decision is handled above.
            }
        }
    }

    /** @return the hand with an accepted reload in flight, or {@code null}. */
    public static InteractionHand activeHand() {
        return TRACKER.activeHand();
    }

    /**
     * @param hand the hand to test
     * @return whether a reload of that hand is in flight or running according to the server's answers
     */
    public static boolean isReloading(InteractionHand hand) {
        return TRACKER.inFlight(hand);
    }

    /**
     * Forgets the client-side reload state when leaving a world.
     *
     * <p>The sequence counter keeps increasing across connections, which is harmless because the server clears its
     * per-connection record on disconnect.</p>
     */
    public static void reset() {
        TRACKER.reset();
    }
}
