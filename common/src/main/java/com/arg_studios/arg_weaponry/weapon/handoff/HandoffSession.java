package com.arg_studios.arg_weaponry.weapon.handoff;

/**
 * One running handoff: who is leaving, where the player is going, and when the transition must be over.
 *
 * <p>Immutable, except for the parts the state machine replaces as a session evolves - the destination (retargeting) and
 * the stage - which are expressed as new instances so an older reference can never be mutated by a newer session.</p>
 *
 * <p>{@code generation} is the guard the reference implementation calls {@code transitionGeneration}: every accepted
 * request, retarget, cancellation and reset increments it, and every completion path must present the generation it was
 * created for. A completion that belongs to a superseded session therefore matches nothing and is dropped.</p>
 *
 * @param generation  the generation this session belongs to
 * @param outgoing    the weapon that is leaving the hand
 * @param targetSlot  the slot the player is switching to
 * @param deferred    whether the slot change still has to be applied at the boundary ({@code false} when the change was
 *                    made by something else and this session only repairs the visual)
 * @param stage       the stage the session is in; never {@link HandoffStage#IDLE}
 * @param clipSeconds the authored length of the clip that owns the current stage
 * @param deadline    the wall-clock instant at which the current stage is committed anyway
 */
public record HandoffSession(long generation, WeaponIdentity outgoing, int targetSlot, boolean deferred,
                             HandoffStage stage, double clipSeconds, double deadline) {

    public HandoffSession {
        if (generation < 1L) {
            throw new IllegalArgumentException("A handoff generation must be positive");
        }
        if (outgoing == null) {
            outgoing = WeaponIdentity.NONE;
        }
        if (stage == null || stage == HandoffStage.IDLE) {
            throw new IllegalArgumentException("A session is never idle");
        }
        if (!Double.isFinite(clipSeconds) || clipSeconds < 0.0) {
            clipSeconds = 0.0;
        }
        if (!Double.isFinite(deadline)) {
            deadline = Double.MAX_VALUE;
        }
    }

    /**
     * @param newTargetSlot the latest slot the player asked for
     * @return a copy of this session aimed at that slot, keeping its generation, stage and clip
     */
    public HandoffSession retarget(int newTargetSlot) {
        return new HandoffSession(generation, outgoing, newTargetSlot, true, stage, clipSeconds, deadline);
    }

    /**
     * @param newStage       the stage being entered
     * @param newClipSeconds the authored length of the clip that owns that stage
     * @param now            the current instant on the environment's clock
     * @return a copy of this session in the new stage, with an absolute deadline derived from that clip
     */
    public HandoffSession enter(HandoffStage newStage, double newClipSeconds, double now) {
        return new HandoffSession(generation, outgoing, targetSlot, deferred, newStage, newClipSeconds,
                now + HandoffTiming.deadline(newClipSeconds));
    }

    /**
     * @param newStage       the stage being entered
     * @param newClipSeconds the authored length of the clip that owns that stage
     * @param now            the current instant on the environment's clock
     * @param newTargetSlot  the destination the transition commits to
     * @return a copy of this session in the new stage aimed at that slot
     */
    public HandoffSession enterFor(HandoffStage newStage, double newClipSeconds, double now, int newTargetSlot) {
        return new HandoffSession(generation, outgoing, newTargetSlot, true, newStage, newClipSeconds,
                now + HandoffTiming.deadline(newClipSeconds));
    }

    /**
     * @param now the current instant
     * @return {@code true} when this stage has run out of time
     */
    public boolean isExpired(double now) {
        return now >= deadline;
    }
}
