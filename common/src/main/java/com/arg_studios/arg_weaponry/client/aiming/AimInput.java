package com.arg_studios.arg_weaponry.client.aiming;


public final class AimInput {

    private boolean latched;
    private boolean wasDown;

    /**
     * Set when an inspection cancels aiming, cleared when the aim button is released.
     *
     * <p>Without it, a player who keeps the aim button held while inspecting would aim again on the tick the
     * inspection ends, which is the "aiming resumes on its own" behaviour an inspection must not have. The
     * suppression therefore outlives the inspection by exactly one release.</p>
     */
    private boolean suppressedUntilRelease;

    // Controls how much shity ass Aiming works
    public boolean update(boolean keyDown, boolean toggleMode, AimBlockReason reason) {
        AimBlockReason current = reason == null ? AimBlockReason.NONE : reason;
        boolean pressed = keyDown && !wasDown;
        wasDown = keyDown;

        if (current == AimBlockReason.INSPECTING) {
            // An inspection cancels aiming and keeps it cancelled: the intent is dropped, and the button has to be
            // released before aiming can start again.
            suppressedUntilRelease = true;
            latched = false;
            return false;
        }
        if (suppressedUntilRelease) {
            if (!keyDown) {
                suppressedUntilRelease = false;
            }
            latched = false;
            return false;
        }
        if (current.clearsAimIntent()) {
            latched = false;
            return false;
        }
        if (!toggleMode) {
            // Hold mode owns no latch: the button itself is the state.
            latched = false;
            return keyDown;
        }
        if (pressed) {
            latched = !latched;
        }
        return latched;
    }

    /** @return whether toggle mode currently holds a latched aim request. */
    public boolean latched() {
        return latched;
    }

    /** @return whether an inspection cancelled aiming and the button has not been released since. */
    public boolean suppressedUntilRelease() {
        return suppressedUntilRelease;
    }


    public static boolean blocksSprint(boolean holdingAim, boolean latched) {
        return blocksSprint(holdingAim, latched, false);
    }


    public static boolean blocksSprint(boolean holdingAim, boolean latched, boolean aimRequested) {
        return blocksSprint(holdingAim, latched, aimRequested, false);
    }

    /**
     * The single sprint rule the client guards use.
     *
     * <p>Aiming blocks a sprint (the weapon has to be steady), and so does a reload: the reload is a two-handed action
     * with its own animation, so the player cannot keep sprinting through it. Both facts are read as <em>intents</em>, so
     * the rule is already true on the tick the action starts and false on the tick it ends.</p>
     *
     * @param holdingAim   whether the accepted aim state is on
     * @param latched      whether toggle mode holds a latched aim request
     * @param aimRequested whether the player is asking to aim, even if aiming is refused right now
     * @param reloading    whether a reload of the held weapon is running
     * @return whether a sprint must not start, and a running one must be stopped
     */
    public static boolean blocksSprint(boolean holdingAim, boolean latched, boolean aimRequested, boolean reloading) {
        return aimRequested || holdingAim || latched || reloading;
    }

    public boolean wasDown() {
        return wasDown;
    }

    public void reset() {
        latched = false;
        wasDown = false;
        suppressedUntilRelease = false;
    }
}
