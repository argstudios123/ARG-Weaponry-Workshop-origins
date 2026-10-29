package com.arg_studios.arg_weaponry.weapon.fire;

import com.arg_studios.arg_weaponry.Constants;
import com.arg_studios.arg_weaponry.compat.WeaponCompatibility;
import com.arg_studios.arg_weaponry.item.WeaponItem;
import com.arg_studios.arg_weaponry.weapon.AmmoCapacity;
import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionResult;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActionRules;
import com.arg_studios.arg_weaponry.weapon.audio.WeaponSoundBroadcast;
import com.arg_studios.arg_weaponry.weapon.inspect.WeaponInspectService;
import com.arg_studios.arg_weaponry.weapon.action.WeaponActions;
import com.arg_studios.arg_weaponry.weapon.state.FireMode;
import com.arg_studios.arg_weaponry.weapon.reload.WeaponReloadRuntime;
import com.arg_studios.arg_weaponry.weapon.state.WeaponComponents;
import com.arg_studios.arg_weaponry.weapon.state.WeaponState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The server-authoritative single-shot firing operation.
 *
 * <p>This is the only place in the mod that fires a weapon. It resolves everything from server state — the
 * player, the held {@link ItemStack}, the registered {@link WeaponDefinition} and the world — and never reads a
 * weapon identifier, damage value, cadence, ammunition count or target from a request.</p>
 *
 * <h2>Order of operations</h2>
 * <p>The steps run in exactly this order, and the order is the contract:</p>
 * <ol>
 *   <li>action validation ({@link WeaponActions#validateFire}) — player context, held weapon, readable state,
 *       magazine present, ammunition present, fire mode executable;</li>
 *   <li>content compatibility ({@link WeaponCompatibility#isUsable}) — a connection whose manifest was never
 *       verified, or was blocked, may not fire;</li>
 *   <li>cadence and anti-replay ({@link WeaponFireRuntime#tryAccept}) — rejected here, nothing is spent;</li>
 *   <li><strong>consume one round</strong> and write the new component - except for a weapon whose
 *       {@code ammo_capacity} is {@code "unlimited"}, which consumes nothing and keeps its stored count;</li>
 *   <li><strong>then</strong> raycast and apply damage.</li>
 * </ol>
 * <p>Ammunition is consumed before the shot on purpose: a shot can never happen without a round being spent, so
 * a failure while resolving the ray cannot hand out a free shot, and because the round is written exactly once,
 * before anything else runs, no later step can consume a second one. A rejected request never reaches step 4.</p>
 *
 * <h2>What is not here</h2>
 * <p>No automatic or burst fire, no spread, no penetration, no projectiles, no recoil, no reload and no sound.
 * The configured {@code sounds.fire} entry is validated by the content-pack pipeline, but there is still no
 * content-pack sound playback system, so this milestone plays no audio at all.</p>
 */
public final class WeaponFireService {

    /** How far the entity query is widened around the ray, in blocks. */
    private static final double ENTITY_SEARCH_INFLATION = 1.0;

    private WeaponFireService() {
    }

    /**
     * One firing attempt.
     *
     * @param result   the verdict; {@link WeaponActionResult#OK} means the shot was executed
     * @param hit      the entity that was hit, or {@code null} for a miss
     * @param distance the distance to the hit in blocks, {@code 0} for a miss
     * @param shotId   the server-assigned, monotonically increasing id of this accepted shot, {@code 0} when the
     *                 attempt was refused; the client animates exactly the shots it is told about, whichever path
     *                 produced them
     * @param headshot whether the hit landed in the entity's head region, never true for a miss
     * @param killed   whether the hit killed the entity, never true for a miss
     */
    public record FireResult(WeaponActionResult result, EntityHitResult hit, double distance, int shotId,
                             boolean headshot, boolean killed) {

        /** @return whether a round was consumed and the shot was executed */
        public boolean fired() {
            return result.isAllowed();
        }
    }

    /**
     * Validates and, when everything allows it, executes one semi-automatic shot.
     *
     * <p>This is the discrete-press path: one request is one press, and only a weapon whose resolved mode is
     * {@link FireMode#SINGLE} may use it. Automatic and burst fire are driven by {@link WeaponFireSessions}, which calls
     * {@link #fireFromSession} instead; letting a client send one request per shot in those modes would hand it the
     * firing rate.</p>
     *
     * <p>Must be called on the server thread: it reads and writes the held stack and damages entities.</p>
     *
     * @param player   the server-side player requesting the shot
     * @param hand     the hand the request refers to
     * @param sequence the request's sequence number, used for anti-replay
     * @return what happened, never {@code null}
     */
    public static FireResult fire(ServerPlayer player, InteractionHand hand, int sequence) {
        return shoot(player, hand, FireMode.SINGLE, sequence);
    }

    /**
     * Executes one shot on behalf of a server-owned firing session.
     *
     * <p>Identical to {@link #fire} except that the cadence gate carries no client sequence: the shot was not requested
     * by a packet, so there is nothing to replay-protect. Everything else - the mode check, ammunition, compatibility,
     * hitscan, damage - is the same code.</p>
     *
     * @param player the server-side player whose session is firing
     * @param hand   the hand the session belongs to
     * @param mode   the mode the session was started with
     * @return what happened, never {@code null}
     */
    public static FireResult fireFromSession(ServerPlayer player, InteractionHand hand, FireMode mode) {
        return shoot(player, hand, mode, 0);
    }

    /**
     * The one authoritative shot path every firing mode uses.
     *
     * @param player         the server-side player
     * @param hand           the hand
     * @param requiredMode   the mode the shot is allowed to be fired in
     * @param clientSequence the client request's sequence number, or {@code 0} for a server-scheduled shot
     * @return what happened, never {@code null}
     */
    private static FireResult shoot(ServerPlayer player, InteractionHand hand, FireMode requiredMode,
                                    int clientSequence) {
        if (player == null || hand == null || requiredMode == null) {
            return reject(WeaponActionResult.INVALID_CONTEXT);
        }
        // [DrawHolster hook] One query blocks every shot while the weapon is being drawn or holstered; a held trigger is
        // resumed by the normal firing path once the window ends.
        if (com.arg_studios.arg_weaponry.weapon.equip.DrawHolsterManager.isEquipBusy(player)) {
            return reject(WeaponActionResult.EQUIPPING);
        }

        // A reload owns the weapon while it runs: its magazine must not change and its reserve ammunition must
        // not be spent by a shot, so firing is refused before anything else is decided.
        if (WeaponReloadRuntime.isReloading(player.getUUID(), hand)) {
            return reject(WeaponActionResult.RELOAD_IN_PROGRESS);
        }

        // The shared validation entry point: player context, held weapon, readable state, declared modes and ammunition.
        // Everything it decides is derived from the server's own player and registry, never from the request.
        WeaponActionResult validation = WeaponActions.validateFire(player, hand);
        if (!validation.isAllowed()) {
            return reject(validation);
        }

        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof WeaponItem weapon)) {
            // Unreachable after validateFire, kept so the reads below are obviously safe.
            return reject(WeaponActionResult.NOT_A_WEAPON);
        }

        WeaponDefinition definition = weapon.definition();
        WeaponState state = weapon.state(stack);
        if (WeaponActionRules.effectiveFireMode(state, definition.firingModes()) != requiredMode) {
            // The weapon is not in the mode this shot belongs to. That is what keeps a discrete press from firing an
            // automatic weapon, and a stale session from firing a weapon whose mode changed.
            return reject(WeaponActionResult.FIRE_MODE_UNSUPPORTED);
        }

        if (!WeaponCompatibility.isUsable(player.getUUID())) {
            return reject(WeaponActionResult.CONTENT_MISMATCH);
        }

        ServerLevel level = player.level();
        int rpm = definition.rpm() == null ? 0 : definition.rpm();
        String itemId = String.valueOf(BuiltInRegistries.ITEM.getKey(weapon));
        boolean accepted = clientSequence > 0
                ? WeaponFireRuntime.tryAccept(player.getUUID(), hand, itemId, rpm, clientSequence, level.getGameTime())
                : WeaponFireRuntime.tryAcceptScheduled(player.getUUID(), hand, itemId, rpm, level.getGameTime());
        if (!accepted) {
            return reject(WeaponActionResult.CADENCE_BLOCKED);
        }

        // Consume exactly one round, once, before the shot. An unlimited weapon consumes none, so its stored count is
        // left exactly as it is and no component write happens at all.
        WeaponState afterShot = afterShot(state, definition.ammoCapacity());
        if (afterShot != state) {
            stack.set(WeaponComponents.WEAPON_STATE, afterShot);
        }

        EntityHitResult hit = hitscan(player, definition.fireRange());
        boolean headshot = false;
        boolean killed = false;
        if (hit != null && hit.getEntity() instanceof LivingEntity target) {
            // The hit facts are derived from the authoritative ray/entity result and the real damage application, so the
            // client can only ever be told what the server already decided.
            AABB box = target.getBoundingBox();
            headshot = HitFacts.isHeadshot(box.minY, box.getYsize(), hit.getLocation().y);
            double healthBefore = target.getHealth();
            target.hurtServer(level, level.damageSources().playerAttack(player), (float) definition.damage());
            killed = target.isDeadOrDying() || HitFacts.isLethal(healthBefore, definition.damage());
        }

        // Other players hear the gunshot positionally; the shooter's own client plays it from the accepted-shot result,
        // so passing the shooter as the excluded entity is what keeps the shot from being heard twice.
        WeaponSoundBroadcast.shot(player, definition);

        // A shot outranks an inspection: the authoritative session ends here, and the client drops the clip when it
        // receives the accepted-shot result.
        WeaponInspectService.cancel(player.getUUID(), hand, WeaponActionResult.OK);

        int shotId = WeaponFireRuntime.nextShotId(player.getUUID(), hand);
        double distance = hit == null ? 0.0 : hit.getLocation().distanceTo(player.getEyePosition());
        Constants.LOG.debug("[ARG] {} fired '{}' in {} ({}): {} at {} block(s), {} round(s) left",
                player.getName().getString(), definition.name(), hand, requiredMode,
                hit == null ? "miss" : "hit " + hit.getEntity().getName().getString(),
                String.format(java.util.Locale.ROOT, "%.2f", distance), afterShot.ammo());
        return new FireResult(WeaponActionResult.OK, hit, distance, shotId, headshot, killed);
    }

    /**
     * The ammunition write of an accepted shot.
     *
     * <p>Pure and separate so the exact amount can be verified without a world: one round fewer, never negative
     * (validation guarantees at least one round for a finite weapon and {@link WeaponState#withAmmo} rejects a
     * negative count). Nothing is clamped, refilled or normalised; a stored count above the current capacity simply
     * decreases by one.</p>
     *
     * <p><strong>Unlimited ammunition</strong> (Milestone 10P): the state is returned unchanged - the very same
     * instance - so the stored count is not decremented, the weapon never becomes empty and the component write can be
     * skipped entirely. This is the only place a shot touches ammunition, which is why the mode is implemented here
     * and not by refilling a counter elsewhere.</p>
     *
     * @param state    the authoritative state before the shot
     * @param capacity the weapon's ammunition capacity, {@code null} when it declares none
     * @return the state the stack must carry afterwards; the argument itself when ammunition is unlimited
     */
    public static WeaponState afterShot(WeaponState state, AmmoCapacity capacity) {
        if (capacity != null && capacity.isUnlimited()) {
            return state;
        }
        return state.withAmmo(state.ammo() - 1);
    }

    /**
     * The server-side hitscan: a straight ray from the shooter's eyes along the shooter's own look direction.
     *
     * <p>The ray is clipped against block colliders first and the entity search stops at that impact point, so a
     * wall always wins over whatever stands behind it. Only entities whose bounding box intersects the ray are
     * candidates; the engine's spatial query restricts the search to the ray's volume and the nearest
     * intersection wins.</p>
     *
     * @param player the shooter
     * @param range  the ray length in blocks
     * @return the entity that was hit, or {@code null}
     */
    private static EntityHitResult hitscan(ServerPlayer player, double range) {
        ServerLevel level = player.level();
        Vec3 start = player.getEyePosition();
        Vec3 direction = player.getViewVector(1.0F);
        Vec3 end = start.add(direction.scale(range));

        BlockHitResult blockHit = level.clip(
                new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 limit = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();

        AABB search = new AABB(start, limit).inflate(ENTITY_SEARCH_INFLATION);
        return ProjectileUtil.getEntityHitResult(level, player, start, limit, search,
                WeaponFireService::isValidTarget, ProjectileUtil.DEFAULT_ENTITY_HIT_RESULT_MARGIN);
    }

    /**
     * @param entity a candidate
     * @return whether the shot may hit it; spectators, dead and unpickable entities are ignored
     */
    private static boolean isValidTarget(Entity entity) {
        return entity.isPickable() && entity.isAlive() && !entity.isSpectator();
    }

    private static FireResult reject(WeaponActionResult result) {
        return new FireResult(result, null, 0.0, 0, false, false);
    }
}
