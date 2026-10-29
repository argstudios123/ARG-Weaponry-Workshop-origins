package com.arg_studios.arg_weaponry.weapon.handoff;

import java.util.ArrayList;
import java.util.List;

/**
 * The visual weapon side, without a renderer.
 *
 * <p>It enforces the invariant the real registry enforces - exactly one visual weapon, and clips only ever start on the weapon
 * that is currently visual - and records the order of the calls, which is what makes "the incoming draw starts after the
 * incoming weapon was adopted" a testable property rather than a comment.</p>
 */
final class FakeHandoffVisuals implements HandoffVisuals {

    /** The authored holster length; {@code 0} means "this weapon has no usable holster clip". */
    double holsterClipSeconds = 0.7;
    /** The authored draw length; {@code 0} means "this weapon has no usable draw clip". */
    double drawClipSeconds = 0.9;

    boolean holsterDone;
    boolean drawDone;

    private WeaponIdentity active = WeaponIdentity.NONE;
    private WeaponIdentity holsterOwner;
    private WeaponIdentity drawOwner;

    final List<String> calls = new ArrayList<>();
    final List<WeaponIdentity> adoptions = new ArrayList<>();
    int releases;
    int holsterStarts;
    int drawStarts;
    int stopHolsterCalls;
    int settleDrawCalls;

    @Override
    public WeaponIdentity activeIdentity() {
        return active;
    }

    @Override
    public void adopt(WeaponIdentity identity) {
        calls.add("adopt:" + identity.itemId() + "@" + identity.slot());
        adoptions.add(identity);
        active = identity == null ? WeaponIdentity.NONE : identity;
        holsterDone = false;
        drawDone = false;
        holsterOwner = null;
        drawOwner = null;
    }

    @Override
    public void release() {
        calls.add("release");
        releases++;
        active = WeaponIdentity.NONE;
    }

    @Override
    public double playHolster() {
        calls.add("holster");
        if (holsterClipSeconds <= 0.0) {
            return 0.0;
        }
        holsterStarts++;
        holsterOwner = active;
        holsterDone = false;
        return holsterClipSeconds;
    }

    @Override
    public boolean holsterFinished() {
        return holsterDone;
    }

    @Override
    public void stopHolster() {
        calls.add("stopHolster");
        stopHolsterCalls++;
        if (holsterOwner != null && !holsterOwner.sameAs(active)) {
            throw new IllegalStateException("The holster of another weapon was stopped");
        }
        holsterOwner = null;
    }

    @Override
    public double playDraw() {
        calls.add("draw");
        if (drawClipSeconds <= 0.0) {
            return 0.0;
        }
        drawStarts++;
        drawOwner = active;
        drawDone = false;
        return drawClipSeconds;
    }

    @Override
    public boolean drawFinished() {
        return drawDone;
    }

    @Override
    public void settleDraw() {
        calls.add("settleDraw");
        settleDrawCalls++;
        drawOwner = null;
    }

    /** Makes the running holster clip reach its end. */
    void finishHolster() {
        if (holsterOwner == null) {
            throw new IllegalStateException("No holster clip is running");
        }
        holsterDone = true;
    }

    /** Makes the running draw clip reach its end. */
    void finishDraw() {
        if (drawOwner == null) {
            throw new IllegalStateException("No draw clip is running");
        }
        drawDone = true;
    }

    /** @return the weapon the running draw clip belongs to, or {@code null}. */
    WeaponIdentity drawOwner() {
        return drawOwner;
    }
}
