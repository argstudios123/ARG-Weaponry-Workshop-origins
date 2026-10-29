package com.arg_studios.arg_weaponry.weapon.handoff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A hand and its inventory, without a game.
 *
 * <p>Records everything the state machine does to the outside world - slot commits, server notifications, and the clock -
 * so a test can assert the exact number of each instead of inferring it from the visual result.</p>
 */
final class FakeHandoffEnvironment implements HandoffEnvironment {

    boolean canTransition = true;
    boolean actionBusy;
    int hotbarSize = 9;
    int selectedSlot;

    /** Weapon identity per hotbar slot; a slot that is absent holds no weapon. */
    final Map<Integer, String> weapons = new HashMap<>();

    final List<Integer> committedSlots = new ArrayList<>();
    final List<Integer> switchStarted = new ArrayList<>();
    int switchCancelled;
    double now = 100.0;

    @Override
    public boolean canTransition() {
        return canTransition;
    }

    @Override
    public int hotbarSize() {
        return hotbarSize;
    }

    @Override
    public int selectedSlot() {
        return selectedSlot;
    }

    @Override
    public WeaponIdentity identityAt(int slot) {
        String itemId = weapons.get(slot);
        return itemId == null ? WeaponIdentity.NONE : new WeaponIdentity(itemId, slot);
    }

    @Override
    public WeaponIdentity selectedIdentity() {
        return identityAt(selectedSlot);
    }

    @Override
    public void selectSlot(int slot) {
        committedSlots.add(slot);
        selectedSlot = slot;
    }

    @Override
    public void notifySwitchStarted(int targetSlot) {
        switchStarted.add(targetSlot);
    }

    @Override
    public void notifySwitchCancelled() {
        switchCancelled++;
    }

    @Override
    public double nowSeconds() {
        return now;
    }

    @Override
    public boolean actionOwnsPlayback() {
        return actionBusy;
    }
}
