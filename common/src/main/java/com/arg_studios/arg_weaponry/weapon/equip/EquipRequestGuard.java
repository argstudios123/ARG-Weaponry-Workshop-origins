package com.arg_studios.arg_weaponry.weapon.equip;

/**
 * The anti-replay and rate-limit rule of the equip request payload, as a pure value.
 *
 * <p>A client tells the server that a hotbar switch is starting, and the server has to accept that message without letting a
 * replayed, reordered or flooding payload start windows at will. The rule is deliberately tiny and free of Minecraft
 * types:</p>
 * <ul>
 *   <li>the first request of a session is always accepted;</li>
 *   <li>a request must carry a sequence number strictly higher than the last accepted one, so a duplicate or an
 *   out-of-order replay changes nothing;</li>
 *   <li>two accepted requests must be at least {@link #COOLDOWN_TICKS} apart, so a burst cannot open several windows;</li>
 *   <li>a clock that went backwards (a new world, a server reload) invalidates the stamp, so the next request is
 *   accepted.</li>
 * </ul>
 *
 * @param tick     the game time of the last accepted request
 * @param sequence its sequence number
 */
public record EquipRequestGuard(long tick, int sequence) {

    /** Minimum server ticks between two accepted equip requests from the same player. */
    public static final int COOLDOWN_TICKS = 2;

    /**
     * The pure rule.
     *
     * @param previous the last accepted request, or {@code null} when there is none
     * @param now      the current game time
     * @param sequence the sequence number of the request being judged
     * @return whether the request may start a window
     */
    public static boolean accepts(EquipRequestGuard previous, long now, int sequence) {
        if (previous == null) {
            return true;
        }
        if (sequence <= previous.sequence()) {
            return false;
        }
        if (now < previous.tick()) {
            return true;
        }
        return now - previous.tick() >= COOLDOWN_TICKS;
    }

    /**
     * @param now      the game time of the request that was just accepted
     * @param sequence its sequence number
     * @return the stamp that replaces this one
     */
    public EquipRequestGuard accepted(long now, int sequence) {
        return new EquipRequestGuard(now, sequence);
    }
}
