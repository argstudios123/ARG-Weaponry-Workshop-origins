package com.arg_studios.arg_weaponry.weapon.fire;

import com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult;
import com.arg_studios.arg_weaponry.weapon.state.FireMode;

/**
 * The pure scheduling rules of a server-owned trigger session.
 *
 * <p>{@link WeaponFireSessions} owns the registry, the validation and the shot execution; this class owns the
 * arithmetic that decides <em>when</em> a session fires, so that arithmetic can be verified without a running server. It
 * has no Minecraft types at all - a session, a tick number and a lease length are everything it needs.</p>
 *
 * <h2>Timing model</h2>
 * <p>Everything is measured in whole server ticks, the only clock the server simulation has:</p>
 * <ul>
 *   <li>the interval between two shots is {@link WeaponFireRuntime#cooldownTicks(int)}, i.e.
 *   {@code ceil(1200 / rpm)} with a floor of one tick - so 300 RPM is four ticks, 600 RPM two, 90 RPM fourteen. The
 *   real cadence is therefore "at most {@code floor(1200 / cooldown)} shots per minute", never faster than nominal;</li>
 *   <li>the delay between two bursts is {@code ceil(seconds * 20)} ticks with a floor of one tick when the configured
 *   delay is positive ({@link WeaponFireSessions#burstGapTicks(double)}). Because the scheduler runs once per tick, a
 *   configured 0.02 s cannot be honoured at millisecond precision: it becomes one tick (50 ms). The configuration is
 *   never silently interpreted as milliseconds;</li>
 *   <li><strong>no catch-up</strong>: after a shot the next gate is {@code max(previousGate, now) + cooldown}, so a lag
 *   spike is absorbed instead of being repaid as a burst of accumulated shots, and a session fires at most once per
 *   tick.</li>
 * </ul>
 */
public final class FireScheduling {

    private FireScheduling() {
    }

    /** What the scheduler must do with a session on the current tick. */
    public enum Action {
        /** Fire one shot now. */
        FIRE,
        /** Nothing is due yet; check again next tick. */
        WAIT,
        /** {@code continuous_burst} is on and the gap has passed: restart the burst, then fire. */
        START_NEW_BURST,
        /** The burst is complete and must not repeat, so the session is over. */
        FINISH
    }

    /**
     * @param session the session
     * @param now     the current game tick
     * @return whether the session's lease has run out (a lost release or heartbeat)
     */
    public static boolean expired(FireSession session, long now) {
        return now > session.expiresAtTick();
    }

    /**
     * Decides what a session does on this tick.
     *
     * @param session the session
     * @param now     the current game tick
     * @return the action; {@link Action#FINISH} when a non-continuous burst has completed
     */
    public static Action action(FireSession session, long now) {
        if (session.mode() == FireMode.BURST && session.shotsRemaining() <= 0) {
            if (!session.continuousBurst()) {
                return Action.FINISH;
            }
            if (now < session.nextBurstTick()) {
                return Action.WAIT;
            }
            if (now < session.nextShotTick()) {
                return Action.WAIT;
            }
            // A new burst of the same press: reschedule the first shot, which is due immediately.
            return Action.START_NEW_BURST;
        }
        return now < session.nextShotTick() ? Action.WAIT : Action.FIRE;
    }

    /**
     * @param session the session
     * @param now     the current game tick
     * @return a copy whose current burst is refilled, ready to fire
     */
    public static FireSession startNextBurst(FireSession session, long now) {
        return session.withShotsRemaining(session.burstCount());
    }

    /**
     * One accepted shot: the outcome of the scheduling step.
     *
     * @param session  the session after the shot
     * @param finished whether the burst is complete and the session must end
     */
    public record Outcome(FireSession session, boolean finished) {
    }

    /**
     * Advances a session past one accepted shot.
     *
     * @param session   the session before the shot
     * @param now       the game tick the shot was fired on
     * @param leaseTicks the lease length granted to the shot's heartbeat window
     * @return the updated session, or a finished one whose {@link Outcome#finished()} flag is set
     */
    public static Outcome afterShot(FireSession session, long now, int leaseTicks) {
        int remaining = session.mode() == FireMode.BURST ? Math.max(0, session.shotsRemaining() - 1) : 0;
        long nextShot = Math.max(session.nextShotTick(), now) + session.cooldownTicks();
        long nextBurst = session.nextBurstTick();
        int fired = session.shotsFired() + 1;
        if (session.mode() == FireMode.BURST && remaining == 0 && !session.continuousBurst()) {
            return new Outcome(session.advanced(now, now + leaseTicks, nextShot, nextBurst, 0, fired), true);
        }
        if (session.mode() == FireMode.BURST && remaining == 0) {
            // continuous_burst: the next burst may begin once the configured gap has passed. The press sequence is
            // unchanged, so this stays the same press.
            nextBurst = now + session.burstGapTicks();
        }
        return new Outcome(session.advanced(now, now + leaseTicks, nextShot, nextBurst, remaining, fired), false);
    }

    /**
     * @param result the result of a scheduled shot attempt
     * @return whether a running session must end because of it; only the cadence gate is a "try again later" answer
     */
    public static boolean endsSession(WeaponActionResult result) {
        return result != WeaponActionResult.OK && result != WeaponActionResult.CADENCE_BLOCKED;
    }
}
