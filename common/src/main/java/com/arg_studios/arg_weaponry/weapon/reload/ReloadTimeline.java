package com.arg_studios.arg_weaponry.weapon.reload;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * The planned execution of one reload: which clips play, when they play and at which tick each round is inserted.
 *
 * <p>This class is pure - no player, no inventory, no item stack, no clock - which is what makes the whole
 * shell-by-shell behaviour verifiable without a running game. The service supplies four numbers taken from the server's
 * own authority (magazine, capacity, available reserve, the definition's {@code shell_amount_reload}) and receives a
 * timeline it can execute tick by tick; the client is sent the same stages so it can play the same clips.</p>
 *
 * <h2>Two shapes, one planner</h2>
 * <ul>
 *     <li><b>Staged</b> - the definition declares at least one entry whose {@code stage} (or legacy {@code phase}) is
 *     part of {@link ReloadStage}'s vocabulary. The planner then builds {@code prepare}, one or more load cycles and
 *     {@code finish}, plus an optional {@code chamber_load}.</li>
 *     <li><b>Whole</b> - no entry uses a recognizable stage name. The reload stays exactly what it was before staged
 *     reloading existed: one clip chosen by {@link ReloadSequenceSelector} and one transfer when it ends. Every pack
 *     written against the old schema therefore keeps its timing, its animation and its single transfer.</li>
 * </ul>
 *
 * <h2>Where the ammunition comes from</h2>
 * <p>Each load cycle asks for at most {@code shell_amount_reload} rounds and is clamped twice: to the room left in the
 * magazine ({@link ReloadRules#room(int, int)}) and to the reserve ammunition that actually exists. A cycle that would
 * insert nothing ends the cycle loop, so a timeline can never schedule an insertion the inventory cannot pay for.</p>
 *
 * <h2>Condition evaluation and the reload iteration counter</h2>
 * <p>Conditions are evaluated once per stage, at plan time, with the counter value that stage will run at - not with a
 * frozen value taken at the start of the reload. The counter follows the reference implementation's lifecycle exactly:</p>
 * <ul>
 *   <li>preparation seeds it with {@code magazineAmmo - 1}, so the opening clip is chosen from the rounds the weapon
 *   already held;</li>
 *   <li>every load iteration increments it <em>before</em> that iteration's conditions are evaluated and its rounds are
 *   inserted, so a magazine holding three rounds starts at three and an empty one at zero;</li>
 *   <li>the closing stages see it reset to {@code 0}, because the reference implementation resets it when it enters its
 *   completion phase.</li>
 * </ul>
 * <p>Every {@link Stage} therefore carries the counter value it was selected with ({@link Stage#iterationIndex()}), and a
 * stage can never be planned against a stale counter. The plan itself is deterministic: it is built from the server's own
 * magazine, capacity, reserve and {@code shell_amount_reload}, so re-evaluating the same numbers always produces the same
 * counter sequence.</p>
 *
 * <h2>Insertion point</h2>
 * <p>A round is inserted {@link WeaponDefinition.ReloadStep#insertOffsetSeconds()} seconds into its own entry, which is
 * the entry's end unless the pack declares {@code insert_at}. Nothing is inserted "when the animation starts": the
 * tick is a property of the stage, which is what lets a pack put the insertion at the moment the shell is seated.</p>
 *
 * @param stages        the stages in play order; never {@code null}
 * @param totalTicks    how long the whole reload lasts, at least one tick
 * @param plannedAmount how many rounds the timeline inserts in total
 */
public record ReloadTimeline(List<Stage> stages, int totalTicks, int plannedAmount) {

    /**
     * Upper bound on insertion cycles, so a definition with an enormous magazine cannot build an enormous plan.
     * Every cycle inserts at least one round, so a finite magazine can never need more cycles than its capacity.
     */
    public static final int MAX_LOAD_CYCLES = 256;

    /**
     * The reload iteration counter reported to a closing stage.
     *
     * <p>The reference implementation resets its counter to zero when the reload enters its completion phase, so a
     * {@code finish} condition is evaluated against {@code 0} and never against the last load iteration's value.</p>
     */
    private static final int COMPLETION_ITERATION_INDEX = 0;

    public ReloadTimeline {
        stages = stages == null ? List.of() : List.copyOf(stages);
        totalTicks = Math.max(1, totalTicks);
        plannedAmount = Math.max(0, plannedAmount);
    }

    /**
     * One planned stage.
     *
     * @param kind           the stage that owns the clip
     * @param animation      the clip to play, or {@code null} for a stage without an animation
     * @param startTick      the tick, relative to the reload start, at which the stage begins
     * @param endTick        the tick at which the stage ends, never below {@code startTick}
     * @param insertTick     the tick at which this stage inserts its rounds, {@code endTick} when it inserts at its end
     * @param insertAmount   the rounds this stage inserts, {@code 0} for a stage that inserts nothing
     * @param iterationIndex the reload iteration counter value this stage was selected with, i.e. what
     *                       {@code reload_inserted_count} saw when the entry's condition was evaluated
     */
    public record Stage(ReloadStage kind, String animation, int startTick, int endTick, int insertTick,
                        int insertAmount, int iterationIndex, int transitionMillis) {

        public Stage {
            startTick = Math.max(0, startTick);
            endTick = Math.max(startTick, endTick);
            insertTick = Math.max(startTick, Math.min(Math.max(startTick, insertTick), endTick));
            insertAmount = Math.max(0, insertAmount);
            transitionMillis = Math.max(0, transitionMillis);
        }

        /**
         * Backwards-compatible construction of a stage that uses the default cross-fade.
         *
         * @param kind           the stage type
         * @param animation      the clip, or {@code null}
         * @param startTick      when the stage starts
         * @param endTick        when the stage ends
         * @param insertTick     when the stage inserts
         * @param insertAmount   how much the stage inserts
         * @param iterationIndex the reload iteration counter the stage was selected with
         */
        public Stage(ReloadStage kind, String animation, int startTick, int endTick, int insertTick,
                     int insertAmount, int iterationIndex) {
            this(kind, animation, startTick, endTick, insertTick, insertAmount, iterationIndex,
                    ReloadRules.transitionMillis(null));
        }

        /** @return the stage length in ticks. */
        public int durationTicks() {
            return endTick - startTick;
        }

        /** @return whether this stage inserts ammunition. */
        public boolean inserts() {
            return insertAmount > 0;
        }

        /** @return whether this stage has a clip to play. */
        public boolean animated() {
            return animation != null && !animation.isBlank();
        }

        /** @return the cross-fade used when this stage's clip starts, in seconds; {@code 0} disables blending. */
        public double transitionSeconds() {
            return transitionMillis / 1000.0;
        }
    }

    /** @return an empty timeline: nothing to play, nothing to insert; never {@code null}. */
    public static ReloadTimeline empty() {
        return new ReloadTimeline(List.of(), 1, 0);
    }

    /** @return whether the timeline declares at least one insertion. */
    public boolean insertsAnything() {
        return plannedAmount > 0;
    }

    /** @return whether any stage is a staged (non-{@link ReloadStage#WHOLE}) stage. */
    public boolean staged() {
        return stages.stream().anyMatch(stage -> stage.kind() != ReloadStage.WHOLE);
    }

    /** @return the stages that have a clip, in play order. */
    public List<Stage> animatedStages() {
        return stages.stream().filter(Stage::animated).toList();
    }

    /**
     * @return the animated stages as {@link ReloadClip}s, the form both the network payload and the client schedule use
     */
    public List<ReloadClip> animatedClips() {
        List<ReloadClip> clips = new ArrayList<>();
        for (Stage stage : animatedStages()) {
            clips.add(new ReloadClip(stage.animation(), stage.startTick(), stage.durationTicks(),
                    stage.transitionMillis()));
        }
        return List.copyOf(clips);
    }

    /**
     * @param elapsedTicks ticks since the reload started
     * @return how many rounds the timeline has scheduled by that tick, cumulative
     */
    public int roundsDueBy(int elapsedTicks) {
        int due = 0;
        for (Stage stage : stages) {
            if (stage.inserts() && stage.insertTick() <= elapsedTicks) {
                due += stage.insertAmount();
            }
        }
        return Math.min(due, plannedAmount);
    }

    /**
     * How many rounds the executor still has to move at this tick.
     *
     * <p>Derived from what the weapon's own state says was already inserted, so the call is idempotent: replaying the
     * same tick cannot insert twice, and a tick that was skipped (a lagging server) inserts everything that is due.</p>
     *
     * @param elapsedTicks    ticks since the reload started
     * @param alreadyInserted rounds this reload has already put in the magazine
     * @return the rounds still to insert now, {@code 0} when nothing is due
     */
    public int dueAmount(int elapsedTicks, int alreadyInserted) {
        return Math.max(0, roundsDueBy(elapsedTicks) - Math.max(0, alreadyInserted));
    }

    // --- planning ---

    /**
     * Plans one reload.
     *
     * @param steps            the declared {@code reload_sequences}, in file order; may be {@code null} or empty
     * @param magazineAmmo     the magazine count at reload start
     * @param capacity         the finite magazine capacity
     * @param reserveAvailable the rounds the inventory can actually supply (Creative supply already folded in)
     * @param shellAmount      the definition's {@code shell_amount_reload}
     * @return the timeline; never {@code null}
     */
    public static ReloadTimeline plan(List<WeaponDefinition.ReloadStep> steps, int magazineAmmo, int capacity,
                                      int reserveAvailable, int shellAmount) {
        List<WeaponDefinition.ReloadStep> entries = steps == null ? List.of() : steps;
        int perCycle = Math.max(1, shellAmount);

        boolean staged = entries.stream()
                .anyMatch(entry -> entry != null && ReloadStage.lookup(entry.phase()) != null);
        if (!staged) {
            return legacy(entries, magazineAmmo, capacity, reserveAvailable);
        }

        ReloadTimeline timeline = staged(entries, magazineAmmo, capacity, reserveAvailable, perCycle);
        // A definition that names stages but declares no insertion stage at all still deserves its transfer: fall back
        // to the single-clip form instead of starting a reload that can never load a round.
        if (!timeline.insertsAnything() && !declaresLoadStage(entries)) {
            return legacy(entries, magazineAmmo, capacity, reserveAvailable);
        }
        return timeline;
    }

    /**
     * The single-clip form: one clip, one transfer when it ends.
     *
     * <p>Identical to the behaviour every content pack had before staged reloading existed, which is why it is also the
     * fallback for a definition that only names stages without declaring an insertion stage.</p>
     */
    private static ReloadTimeline legacy(List<WeaponDefinition.ReloadStep> entries, int magazineAmmo, int capacity,
                                         int reserveAvailable) {
        ReloadSequenceSelector.Selection selection = ReloadSequenceSelector.select(entries, magazineAmmo);
        String animation = ReloadSequenceSelector.animationName(selection);
        int durationTicks = ReloadRules.ticksFor(ReloadSequenceSelector.durationSeconds(selection));
        int amount = ReloadRules.transfer(capacity, magazineAmmo, reserveAvailable);
        List<Stage> stages = List.of(new Stage(ReloadStage.WHOLE, animation, 0, durationTicks, durationTicks, amount,
                magazineAmmo - 1));
        return new ReloadTimeline(stages, durationTicks, amount);
    }

    private static boolean declaresLoadStage(List<WeaponDefinition.ReloadStep> entries) {
        for (WeaponDefinition.ReloadStep entry : entries) {
            if (entry == null) {
                continue;
            }
            ReloadStage stage = ReloadStage.lookup(entry.phase());
            if (stage != null && stage.insertsAmmunition()) {
                return true;
            }
        }
        return false;
    }

    private static ReloadTimeline staged(List<WeaponDefinition.ReloadStep> entries, int magazineAmmo, int capacity,
                                         int reserveAvailable, int shellAmount) {
        List<Stage> stages = new ArrayList<>();
        int cursor = 0;
        int inserted = 0;
        int reserve = Math.max(0, reserveAvailable);

        // The reload iteration counter, with the reference implementation's lifecycle: preparation seeds it with one
        // less than the rounds already in the magazine, every iteration increments it before that iteration's
        // conditions are evaluated and its rounds are inserted, and completion resets it to zero. With one round per
        // iteration it therefore reports the rounds the magazine held when the iteration began, which is exactly what
        // makes "first shell" (0) and "further shells" (> 0) different clips for a magazine that started empty.
        int prepareIndex = magazineAmmo - 1;
        WeaponDefinition.ReloadStep prepare = pick(entries, ReloadStage.PREPARE, magazineAmmo, prepareIndex);
        if (prepare != null) {
            cursor = append(stages, ReloadStage.PREPARE, prepare, cursor, 0, prepareIndex);
        }

        int cycles = 0;
        int maxCycles = Math.min(MAX_LOAD_CYCLES, Math.max(1, capacity));
        while (cycles < maxCycles) {
            int room = ReloadRules.room(capacity, magazineAmmo + inserted);
            if (room <= 0 || reserve <= 0) {
                break;
            }
            int iterationIndex = prepareIndex + cycles + 1;
            WeaponDefinition.ReloadStep entry = pick(entries, ReloadStage.INITIAL_LOAD, magazineAmmo, iterationIndex);
            ReloadStage kind = ReloadStage.INITIAL_LOAD;
            if (entry == null) {
                // A pack may declare only the repeating clip; it then also performs the first insertion.
                entry = pick(entries, ReloadStage.REPEAT_LOAD, magazineAmmo, iterationIndex);
                kind = ReloadStage.REPEAT_LOAD;
            }
            if (entry == null) {
                break;
            }
            int amount = Math.min(Math.min(shellAmount, room), reserve);
            if (amount <= 0) {
                break;
            }
            cursor = append(stages, kind, entry, cursor, amount, iterationIndex);
            inserted += amount;
            reserve -= amount;
            cycles++;
            // "repeat" is the reload step's own flag, not a loop hint: a repeat stage that declares
            // "repeat": false inserts that one shell and hands over to the closing stage. Absent means repeat, which is
            // what the stage name has always meant, so existing content is unaffected.
            if (kind == ReloadStage.REPEAT_LOAD && Boolean.FALSE.equals(entry.repeat())) {
                break;
            }
        }

        // The closing stages are selected the way the reference implementation selects its completion phase: the
        // counter is reset to zero first, so a completion condition never sees the last iteration's value.
        WeaponDefinition.ReloadStep finish = pick(entries, ReloadStage.FINISH, magazineAmmo, COMPLETION_ITERATION_INDEX);
        if (finish != null) {
            cursor = append(stages, ReloadStage.FINISH, finish, cursor, 0, COMPLETION_ITERATION_INDEX);
        }

        WeaponDefinition.ReloadStep chamber =
                pick(entries, ReloadStage.CHAMBER_LOAD, magazineAmmo, COMPLETION_ITERATION_INDEX);
        if (chamber != null) {
            // A chambered round sits beyond the magazine: a stored count above the capacity is legal in this project (a
            // pack may lower ammo_capacity under a loaded stack, and this is the same deliberate state). It is still
            // clamped to the reserve that exists, so chambering can never create ammunition.
            int amount = Math.min(shellAmount, reserve);
            cursor = append(stages, ReloadStage.CHAMBER_LOAD, chamber, cursor, amount, COMPLETION_ITERATION_INDEX);
            inserted += amount;
            reserve -= amount;
        }

        return new ReloadTimeline(stages, Math.max(1, cursor), inserted);
    }

    /** Appends one stage and returns the tick at which the next stage starts. */
    private static int append(List<Stage> stages, ReloadStage kind, WeaponDefinition.ReloadStep entry, int startTick,
                              int insertAmount, int iterationIndex) {
        int durationTicks = ReloadRules.ticksFor(entry.duration());
        int endTick = startTick + durationTicks;
        int insertTick = startTick + ReloadRules.ticksFor(entry.insertOffsetSeconds());
        stages.add(new Stage(kind, entry.animation(), startTick, endTick, insertTick, insertAmount, iterationIndex,
                entry.transitionMillis()));
        return endTick;
    }

    /**
     * Picks the entry of a stage that applies, by the reference implementation's rule.
     *
     * <p>Every entry of the stage is tested in file order and the <em>last</em> one whose {@code when} holds wins. That
     * lets a pack put a general entry first and a specific override after it - the pattern the staged examples use for
     * the two opening clips - instead of the result depending on which line happened to come first. A condition this
     * build cannot evaluate never matches, exactly like every other selector in the project: an unsupported expression is
     * reported elsewhere and must not silently become "true".</p>
     *
     * <p>An entry does not have to name an animation: the clip is a label, while the stage's duration, its insertion
     * point and its condition are what the reload is actually built from. A stage without a clip therefore still spans
     * its authored time, and {@link Stage#animated()} is {@code false} for it.</p>
     *
     * @param entries        the declared entries
     * @param kind           the stage to look for
     * @param magazineAmmo   the magazine count at reload start
     * @param iterationIndex the reload iteration counter this stage will run at, i.e. the value
     *                       {@code reload_inserted_count} must see when the condition is evaluated
     * @return the matching entry, or {@code null} when the definition declares none that applies
     */
    private static WeaponDefinition.ReloadStep pick(List<WeaponDefinition.ReloadStep> entries, ReloadStage kind,
                                                    int magazineAmmo, int iterationIndex) {
        WeaponDefinition.ReloadStep chosen = null;
        for (WeaponDefinition.ReloadStep entry : entries) {
            if (entry == null || ReloadStage.lookup(entry.phase()) != kind) {
                continue;
            }
            Boolean matches = ReloadConditions.evaluate(entry.when(), magazineAmmo, iterationIndex);
            if (Boolean.TRUE.equals(matches)) {
                chosen = entry;
            }
        }
        return chosen;
    }
}
