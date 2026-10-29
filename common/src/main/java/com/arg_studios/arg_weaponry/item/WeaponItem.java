package com.arg_studios.arg_weaponry.item;

import com.arg_studios.arg_weaponry.pack.ContentPack;
import com.arg_studios.arg_weaponry.weapon.AmmoCapacity;
import com.arg_studios.arg_weaponry.weapon.AmmunitionPolicy;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.state.FireMode;
import com.arg_studios.arg_weaponry.weapon.state.WeaponComponents;
import com.arg_studios.arg_weaponry.weapon.state.WeaponState;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The Minecraft item that represents one weapon definition.
 *
 * <p>The item carries only what is shared by every stack of that weapon: the owning content pack and the
 * parsed definition. Per-stack values live in the {@code arg_weaponry:weapon_state} data component and
 * are read through the helpers below, so two stacks of the same weapon are independent and no mutable
 * ammunition or fire-mode field exists on the item.</p>
 *
 * <p>No gameplay behaviour is implemented yet; the helpers only read and construct state.</p>
 */
public class WeaponItem extends Item {

    private final ContentPack pack;
    private final WeaponDefinition definition;

    public WeaponItem(ContentPack pack, WeaponDefinition definition, Item.Properties properties) {
        super(properties);
        this.pack = pack;
        this.definition = definition;
    }

    /** @return the content pack that supplied this weapon. */
    public ContentPack pack() {
        return pack;
    }

    /** @return the identity of the content pack that supplied this weapon. */
    public String packId() {
        return pack.id();
    }

    /** @return the parsed definition behind this item. */
    public WeaponDefinition definition() {
        return definition;
    }

    // --- weapon state ---

    /**
     * Derives the ammunition capacity of a definition.
     *
     * <p>The definition is the only source of capacity; nothing here knows about a specific weapon. A
     * weapon type that declares no {@code ammo_capacity} (for example a melee weapon) deliberately yields
     * {@code null}: the fallback does not invent a magazine.</p>
     *
     * @param definition the definition to read, may be {@code null}
     * @return the declared capacity - finite or unlimited - or {@code null} when none is declared
     */
    public static AmmoCapacity ammoCapacity(WeaponDefinition definition) {
        return AmmunitionPolicy.capacityOf(definition);
    }

    /**
     * The numeric bound a stored ammunition count is clamped into.
     *
     * <p>Finite weapons report their magazine size, unlimited weapons report
     * {@link Integer#MAX_VALUE} (see {@link AmmoCapacity#clampBound()}) and a weapon without a declared capacity
     * reports {@code 0}, which keeps every stored count at zero. This is a clamping bound only: whether firing or
     * reloading is allowed is decided by {@code WeaponActionRules} from the {@link AmmoCapacity} itself, never from
     * this number.</p>
     *
     * @param definition the definition to read, may be {@code null}
     * @return the clamp bound, never negative
     */
    public static int magazineCapacity(WeaponDefinition definition) {
        return AmmunitionPolicy.clampBound(definition);
    }

    /**
     * @param definition the definition to derive the state from, may be {@code null}
     * @return the state a fresh stack of that weapon starts with (full magazine, {@link FireMode#SINGLE}); an
     *         unlimited weapon starts at zero rounds because it never needs any
     */
    public static WeaponState defaultStateFor(WeaponDefinition definition) {
        return AmmunitionPolicy.initialState(definition);
    }

    /**
     * The ammunition count an animation condition must see for a stack.
     *
     * <p>{@code idle_anims} and {@code fire_anims} are selected with {@code ammoCount <op> <n>} expressions. For a
     * finite weapon the count is the stored one; for an unlimited weapon - which can never be empty - it is
     * {@link AmmoCapacity#UNLIMITED_ANIMATION_COUNT}, so the non-empty clips are selected and the empty clips never
     * are. A weapon without a declared capacity reports its stored count, which is zero.</p>
     *
     * @param definition the definition of the held weapon, may be {@code null}
     * @param state      the stored state, may be {@code null}
     * @return the count to hand to the animation selectors
     */
    public static int animationAmmoCount(WeaponDefinition definition, WeaponState state) {
        return AmmunitionPolicy.animationCount(definition, state);
    }

    /** @return the ammunition capacity declared by this weapon's definition, or {@code null} when none is. */
    public AmmoCapacity ammoCapacity() {
        return ammoCapacity(definition);
    }

    /** @return the clamp bound of this weapon's capacity; see {@link #magazineCapacity(WeaponDefinition)}. */
    public int magazineCapacity() {
        return magazineCapacity(definition);
    }

    /** @return the ammunition count the animation selectors should see for this stack. */
    public int animationAmmoCount(ItemStack stack) {
        return animationAmmoCount(definition, state(stack));
    }

    /** @return the state a fresh stack of this weapon starts with. */
    public WeaponState defaultState() {
        return defaultStateFor(definition);
    }

    /**
     * Reads the stored state of a stack.
     *
     * <p>Uses the registered component, so the value is whatever the stack carries; when the stack has no
     * stored component, vanilla resolves the item's declared default, which is exactly the state this
     * weapon was registered with. The result is never {@code null} for a weapon item.</p>
     *
     * @param stack the stack to read
     * @return the current state, or this weapon's default when the stack carries none
     */
    public WeaponState state(ItemStack stack) {
        WeaponState stored = WeaponComponents.get(stack);
        return stored == null ? defaultState() : stored;
    }

    /**
     * Builds a replacement state with a different ammunition count.
     *
     * <p>The count is clamped into {@code [0, magazineCapacity()]}, i.e. into the declared magazine size for a finite
     * weapon and into the whole {@code int} range for an unlimited one. This constructs a value; it does not write to
     * the stack.</p>
     *
     * @param stack the stack whose other state fields are kept
     * @param ammo  the requested ammunition count
     * @return the replacement state
     */
    public WeaponState stateWithAmmo(ItemStack stack, int ammo) {
        return state(stack).withAmmo(Math.clamp(ammo, 0, magazineCapacity()));
    }

    /**
     * Builds a replacement state with a different fire mode.
     *
     * @param stack    the stack whose other state fields are kept
     * @param fireMode the new fire mode
     * @return the replacement state
     */
    public WeaponState stateWithFireMode(ItemStack stack, FireMode fireMode) {
        return state(stack).withFireMode(fireMode);
    }
}
