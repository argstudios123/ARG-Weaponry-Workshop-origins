package com.arg_studios.arg_weaponry.weapon.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arg_studios.arg_weaponry.weapon.WeaponDefinition;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The reload planner: the whole shell-by-shell behaviour, verified without a game.
 *
 * <p>The inputs are the four numbers the server owns (magazine, capacity, reserve, {@code shell_amount_reload}) plus the
 * declared sequence; the output is a timeline the server executes and the client replays. Every test below therefore
 * states a gameplay rule - how many shells one reload loads, which clip opens it, when a round appears - as an
 * assertion on data.</p>
 */
class ReloadTimelineTest {

    private static final double PREPARE_EMPTY_SECONDS = 0.38;
    private static final double PREPARE_SECONDS = 0.42;
    private static final double LOAD_FIRST_SECONDS = 1.33;
    private static final double LOAD_LOOP_SECONDS = 0.75;
    private static final double FINISH_SECONDS = 0.67;

    @Test
    void aLegacySingleClipDefinitionKeepsItsExactBehaviour() {
        List<WeaponDefinition.ReloadStep> steps = List.of(
                new WeaponDefinition.ReloadStep("reloading", "anim.model.reload_empty", 3.0, "reload_count == 0", null),
                new WeaponDefinition.ReloadStep("reloading", "anim.model.reload", 2.9, "reload_count > 0", null));

        ReloadTimeline timeline = ReloadTimeline.plan(steps, 0, 20, 40, 1);

        assertFalse(timeline.staged(), "an unknown phase name must stay a single-clip reload");
        assertEquals(1, timeline.stages().size());
        ReloadTimeline.Stage stage = timeline.stages().getFirst();
        assertEquals(ReloadStage.WHOLE, stage.kind());
        assertEquals("anim.model.reload_empty", stage.animation(), "reload_count == 0 selects the empty clip");
        assertEquals(60, timeline.totalTicks(), "3.0 s is sixty ticks, exactly as before");
        assertEquals(20, stage.insertAmount(), "the single transfer still fills the magazine");
        assertEquals(20, timeline.plannedAmount());
        assertEquals(0, timeline.dueAmount(0, 0), "a single-clip reload inserts nothing at its start");
        assertEquals(20, timeline.dueAmount(60, 0), "it inserts everything when it ends");
    }

    @Test
    void aDefinitionWithoutSequencesKeepsTheDocumentedDefault() {
        ReloadTimeline timeline = ReloadTimeline.plan(List.of(), 4, 10, 30, 1);

        assertEquals(1, timeline.stages().size());
        assertEquals(ReloadRules.ticksFor(ReloadRules.DEFAULT_RELOAD_SECONDS), timeline.totalTicks());
        assertEquals(6, timeline.plannedAmount(), "room is capacity minus the rounds already loaded");
    }

    @Test
    void theShotgunSequenceLoadsOneShellPerCycleUntilTheMagazineIsFull() {
        ReloadTimeline timeline = ReloadTimeline.plan(shotgunSteps(), 0, 8, 28, 1);

        assertTrue(timeline.staged());
        assertEquals(List.of(ReloadStage.PREPARE, ReloadStage.INITIAL_LOAD, ReloadStage.REPEAT_LOAD,
                        ReloadStage.REPEAT_LOAD, ReloadStage.REPEAT_LOAD, ReloadStage.REPEAT_LOAD,
                        ReloadStage.REPEAT_LOAD, ReloadStage.REPEAT_LOAD, ReloadStage.REPEAT_LOAD, ReloadStage.FINISH),
                kinds(timeline), "one opening clip, one first shell, seven repeats and the closing clip");
        assertEquals(8, timeline.plannedAmount(), "a capacity of eight takes eight shells");
        assertTrue(timeline.stages().stream().filter(ReloadTimeline.Stage::inserts)
                        .allMatch(stage -> stage.insertAmount() == 1),
                "shell_amount_reload 1 inserts exactly one round per cycle");
    }

    @Test
    void theEmptyPrepareClipIsChosenWhenTheMagazineIsEmptyAndTheStandardOneOtherwise() {
        assertEquals("anim.model.reload_start_empty",
                ReloadTimeline.plan(shotgunSteps(), 0, 8, 28, 1).stages().getFirst().animation());
        assertEquals("anim.model.reload_start",
                ReloadTimeline.plan(shotgunSteps(), 3, 8, 28, 1).stages().getFirst().animation());
    }

    @Test
    void aRoundIsInsertedAtTheEndOfItsOwnClipNotWhenItStarts() {
        ReloadTimeline timeline = ReloadTimeline.plan(shotgunSteps(), 0, 8, 28, 1);

        ReloadTimeline.Stage prepare = timeline.stages().get(0);
        ReloadTimeline.Stage firstShell = timeline.stages().get(1);
        int prepareTicks = ReloadRules.ticksFor(PREPARE_EMPTY_SECONDS);

        assertEquals(0, prepare.startTick());
        assertEquals(prepareTicks, prepare.endTick());
        assertEquals(prepareTicks, firstShell.startTick(), "the first load begins when the prepare clip ends");
        assertEquals(firstShell.endTick(), firstShell.insertTick(), "a shell only exists once its clip ends");
        assertEquals(0, timeline.dueAmount(firstShell.startTick(), 0), "nothing is inserted mid-clip");
        assertEquals(1, timeline.dueAmount(firstShell.endTick(), 0), "the shell is inserted when the clip ends");
    }

    @Test
    void anAuthoredInsertAtMovesTheInsertionInsideTheClip() {
        List<WeaponDefinition.ReloadStep> steps = List.of(
                new WeaponDefinition.ReloadStep("initial_load", "anim.model.load_first", 1.0, null, null, 0.5));

        ReloadTimeline timeline = ReloadTimeline.plan(steps, 0, 4, 8, 1);

        ReloadTimeline.Stage stage = timeline.stages().getFirst();
        assertEquals(20, stage.endTick(), "a 1.0 s clip lasts twenty ticks");
        assertEquals(ReloadRules.ticksFor(0.5), stage.insertTick(), "insert_at 0.5 s is honoured");
        assertEquals(1, timeline.dueAmount(stage.insertTick(), 0));
    }

    @Test
    void shellAmountReloadLoadsSeveralRoundsPerCycle() {
        ReloadTimeline timeline = ReloadTimeline.plan(shotgunSteps(), 0, 8, 20, 2);

        assertEquals(4, timeline.stages().stream().filter(ReloadTimeline.Stage::inserts).count(),
                "four cycles of two rounds fill a magazine of eight");
        assertEquals(8, timeline.plannedAmount());
    }

    @Test
    void theReserveAmmunitionLimitsTheInsertions() {
        ReloadTimeline timeline = ReloadTimeline.plan(shotgunSteps(), 0, 8, 3, 1);

        assertEquals(3, timeline.plannedAmount(), "three available shells can only fill three rounds");
        assertEquals(3, timeline.stages().stream().filter(ReloadTimeline.Stage::inserts).count(),
                "one first load and two further cycles");
    }

    @Test
    void theRoomLeftInTheMagazineLimitsTheInsertions() {
        ReloadTimeline timeline = ReloadTimeline.plan(shotgunSteps(), 6, 8, 20, 1);

        assertEquals(2, timeline.plannedAmount(), "two rounds of room, two insertions");
        // A magazine that already holds six rounds starts its first iteration at counter 6, so the first-shell entry
        // (reload_inserted_count == 0) cannot match: every iteration of this reload uses the repeating clip.
        assertEquals(List.of(ReloadStage.PREPARE, ReloadStage.REPEAT_LOAD, ReloadStage.REPEAT_LOAD,
                ReloadStage.FINISH), kinds(timeline));
        assertEquals(List.of(6, 7), timeline.stages().stream()
                .filter(ReloadTimeline.Stage::inserts).map(ReloadTimeline.Stage::iterationIndex).toList(),
                "the counter starts at the rounds already loaded and increments per iteration");
    }

    @Test
    void aWeaponWithNoReserveStillPlaysItsClips() {
        ReloadTimeline timeline = ReloadTimeline.plan(shotgunSteps(), 0, 8, 0, 1);

        assertEquals(0, timeline.plannedAmount());
        assertEquals(List.of(ReloadStage.PREPARE, ReloadStage.FINISH), kinds(timeline),
                "without ammunition only the opening and closing clips remain");
    }

    @Test
    void aChamberLoadAddsOneRoundBeyondTheMagazine() {
        List<WeaponDefinition.ReloadStep> steps = new ArrayList<>(shotgunSteps());
        steps.add(new WeaponDefinition.ReloadStep("chamber_load", "anim.model.chamber_load", 0.85, null, null));

        ReloadTimeline timeline = ReloadTimeline.plan(steps, 0, 8, 20, 1);

        assertEquals(ReloadStage.CHAMBER_LOAD, timeline.stages().getLast().kind());
        assertEquals(1, timeline.stages().getLast().insertAmount());
        assertEquals(9, timeline.plannedAmount(), "eight in the magazine and one in the chamber");
    }

    @Test
    void aStagedDefinitionWithoutAnyLoadStageFallsBackToTheWholeClipTransfer() {
        List<WeaponDefinition.ReloadStep> steps = List.of(
                new WeaponDefinition.ReloadStep("prepare", "anim.model.reload_start", 0.5, null, null),
                new WeaponDefinition.ReloadStep("finish", "anim.model.reload_finish", 0.5, null, null));

        ReloadTimeline timeline = ReloadTimeline.plan(steps, 0, 8, 20, 1);

        assertFalse(timeline.staged(), "naming stages without a load stage must not break the reload");
        assertEquals(8, timeline.plannedAmount());
    }

    @Test
    void aRepeatOnlyDefinitionAlsoPerformsTheFirstInsertion() {
        List<WeaponDefinition.ReloadStep> steps = List.of(
                new WeaponDefinition.ReloadStep("repeat_load", "anim.model.reload_loop", 0.5, null, true));

        ReloadTimeline timeline = ReloadTimeline.plan(steps, 0, 3, 20, 1);

        assertEquals(3, timeline.plannedAmount());
        assertTrue(timeline.stages().stream().allMatch(stage -> stage.kind() == ReloadStage.REPEAT_LOAD));
    }

    @Test
    void anUnsupportedConditionNeverMatches() {
        List<WeaponDefinition.ReloadStep> steps = List.of(
                new WeaponDefinition.ReloadStep("initial_load", "anim.model.load_first", 1.0,
                        "reload_inserted_count == zero", null));

        ReloadTimeline timeline = ReloadTimeline.plan(steps, 0, 4, 8, 1);

        assertEquals(0, timeline.plannedAmount(), "an expression this build cannot evaluate is not guessed");
    }

    @Test
    void theTimelineIsIdempotentAcrossRepeatedTicks() {
        ReloadTimeline timeline = ReloadTimeline.plan(shotgunSteps(), 0, 8, 28, 1);
        ReloadTimeline.Stage firstShell = timeline.stages().get(1);
        ReloadTimeline.Stage secondShell = timeline.stages().get(2);

        assertEquals(1, timeline.dueAmount(firstShell.insertTick(), 0));
        assertEquals(0, timeline.dueAmount(firstShell.insertTick(), 1), "the same tick cannot insert twice");
        assertEquals(2, timeline.dueAmount(secondShell.insertTick(), 0),
                "a skipped tick inserts every shell that is due");
        assertEquals(1, timeline.dueAmount(secondShell.insertTick(), 1), "only the shells still owed");
        assertEquals(0, timeline.dueAmount(secondShell.insertTick(), 2));
    }

    @Test
    void theAnimatedClipsCarryTheSameTimingAsTheStages() {
        ReloadTimeline timeline = ReloadTimeline.plan(shotgunSteps(), 0, 8, 28, 1);

        List<ReloadClip> clips = timeline.animatedClips();

        assertEquals(timeline.stages().size(), clips.size());
        for (int index = 0; index < clips.size(); index++) {
            assertEquals(timeline.stages().get(index).startTick(), clips.get(index).startTick());
            assertEquals(timeline.stages().get(index).durationTicks(), clips.get(index).durationTicks());
        }
    }

    @Test
    void theLastMatchingEntryOfAStageWins() {
        // A general entry first, a specific override after it: the override has to win, exactly like the reference
        // implementation's ordered scan. With first-match selection the generic clip would always play.
        List<WeaponDefinition.ReloadStep> steps = List.of(
                new WeaponDefinition.ReloadStep("prepare", "anim.model.reload_start", 0.4, null, null),
                new WeaponDefinition.ReloadStep("prepare", "anim.model.reload_start_empty", 0.4,
                        "ammo_count == -1", null),
                new WeaponDefinition.ReloadStep("initial_load", "anim.model.load_first", 1.0, null, null));

        assertEquals("anim.model.reload_start_empty",
                ReloadTimeline.plan(steps, 0, 4, 20, 1).stages().getFirst().animation(),
                "the later entry that matches an empty magazine is the one that plays");
        assertEquals("anim.model.reload_start",
                ReloadTimeline.plan(steps, 2, 4, 20, 1).stages().getFirst().animation(),
                "with rounds loaded only the general entry matches, so it plays");
    }

    @Test
    void aStageWithoutAnAnimationStillSpendsItsDuration() {
        // The clip is a label. A pack may declare a timed beat with no animation at all, and it must still hold the
        // reload for its duration and still insert its round - otherwise the reload silently shortens.
        List<WeaponDefinition.ReloadStep> steps = List.of(
                new WeaponDefinition.ReloadStep("prepare", null, 0.5, null, null),
                new WeaponDefinition.ReloadStep("initial_load", "anim.model.load_first", 1.0,
                        "reload_inserted_count == 0", null),
                new WeaponDefinition.ReloadStep("repeat_load", "anim.model.reload_loop", 0.5,
                        "reload_inserted_count > 0", true),
                new WeaponDefinition.ReloadStep("finish", "anim.model.reload_finish", 0.5, null, null));

        ReloadTimeline timeline = ReloadTimeline.plan(steps, 0, 3, 10, 1);

        ReloadTimeline.Stage prepare = timeline.stages().getFirst();
        assertEquals(ReloadStage.PREPARE, prepare.kind());
        assertNull(prepare.animation(), "the stage declares no clip");
        assertFalse(prepare.animated());
        assertEquals(10, prepare.endTick(), "it still lasts its authored half second");
        assertEquals(60, timeline.totalTicks(), "0.5 + 1.0 + 0.5 + 0.5 + 0.5 seconds, every authored duration kept");
        assertEquals(3, timeline.plannedAmount(), "rounds are inserted whether or not a clip plays");
        assertEquals(timeline.stages().size() - 1, timeline.animatedClips().size(),
                "the silent opening beat is the only stage without a clip");
    }

    @Test
    void aRepeatStageThatDeclaresNoRepeatInsertsOnceAndFinishes() {
        // "repeat": false is a single step, not a loop: the reload inserts one shell in that stage and moves on instead of
        // repeating it until the magazine is full.
        List<WeaponDefinition.ReloadStep> steps = List.of(
                new WeaponDefinition.ReloadStep("initial_load", "anim.model.load_first", 1.0,
                        "reload_inserted_count == 0", null),
                new WeaponDefinition.ReloadStep("repeat_load", "anim.model.reload_loop", 0.75,
                        "reload_inserted_count > 0", false),
                new WeaponDefinition.ReloadStep("finish", "anim.model.reload_finish", 0.5, null, null));

        ReloadTimeline timeline = ReloadTimeline.plan(steps, 0, 8, 28, 1);

        assertEquals(List.of(ReloadStage.INITIAL_LOAD, ReloadStage.REPEAT_LOAD, ReloadStage.FINISH), kinds(timeline));
        assertEquals(2, timeline.plannedAmount(), "one first shell and one repeated step, then the closing clip");

        // The shipped content declares "repeat": true and keeps loading until the magazine is full.
        assertEquals(8, ReloadTimeline.plan(shotgunSteps(), 0, 8, 28, 1).plannedAmount());
    }

    @Test
    void anEmptyMagazineStartsTheLadderAtZeroAndAPartiallyLoadedOneDoesNot() {
        // The shipped sequence: the first-shell clip is keyed on reload_inserted_count == 0, the repeating clip on > 0.
        ReloadTimeline empty = ReloadTimeline.plan(shotgunSteps(), 0, 8, 28, 1);
        assertEquals("anim.model.load_first", empty.stages().get(1).animation(),
                "an empty magazine runs its first iteration at counter 0");
        assertEquals(0, empty.stages().get(1).iterationIndex());

        ReloadTimeline partial = ReloadTimeline.plan(shotgunSteps(), 3, 8, 28, 1);
        assertFalse(partial.stages().stream().anyMatch(stage -> stage.kind() == ReloadStage.INITIAL_LOAD),
                "a magazine that already holds rounds never matches the first-shell entry");
        assertEquals("anim.model.reload_loop", partial.stages().get(1).animation(),
                "it continues with the repeating clip from its very first iteration");
        assertEquals(3, partial.stages().get(1).iterationIndex());
    }

    @Test
    void everyIterationSeesItsOwnUpdatedIterationCounter() {
        ReloadTimeline timeline = ReloadTimeline.plan(shotgunSteps(), 0, 4, 20, 1);

        assertEquals(List.of(0, 1, 2, 3), timeline.stages().stream()
                        .filter(ReloadTimeline.Stage::inserts).map(ReloadTimeline.Stage::iterationIndex).toList(),
                "the counter increments before each iteration is selected, exactly like the reference implementation");
        assertEquals(List.of(1, 1, 1, 1), timeline.stages().stream()
                        .filter(ReloadTimeline.Stage::inserts).map(ReloadTimeline.Stage::insertAmount).toList(),
                "each iteration inserts exactly one round");
        assertEquals(List.of(1, 2, 3, 4), List.of(1, 2, 3, 4).stream()
                        .map(tick -> timeline.roundsDueBy(timeline.stages().get(tick).insertTick())).toList(),
                "the magazine grows by one round per iteration and never by two");
        assertEquals(0, timeline.stages().getLast().iterationIndex(),
                "the closing stage sees the counter reset to zero");
    }

    @Test
    void anAa12StyleLadderSelectsTheClipForTheRoundsAlreadyLoaded() {
        // The reference AA12 configuration: > 1 plays the loop clip, == 1 the one-round-left clip, == 0 the empty clip.
        List<WeaponDefinition.ReloadStep> steps = List.of(
                new WeaponDefinition.ReloadStep("repeat_load", "anim.model.reload", 1.0,
                        "reload_inserted_count > 1", true),
                new WeaponDefinition.ReloadStep("repeat_load", "anim.model.reloadoneleft", 1.0,
                        "reload_inserted_count == 1", true),
                new WeaponDefinition.ReloadStep("initial_load", "anim.model.reloadempty", 1.0,
                        "reload_inserted_count == 0", null));

        assertEquals("anim.model.reloadempty", firstLoadAnimation(steps, 0), "nothing loaded selects the empty clip");
        assertEquals("anim.model.reloadoneleft", firstLoadAnimation(steps, 1), "one round left selects its own clip");
        assertEquals("anim.model.reload", firstLoadAnimation(steps, 3), "more than one round selects the loop clip");

        // The second iteration of an empty reload reaches counter 1, so it moves on to the one-round-left clip - the
        // behaviour an inserted-shell counter could never reproduce, because it starts at 0 for a partially filled gun.
        ReloadTimeline empty = ReloadTimeline.plan(steps, 0, 4, 20, 1);
        assertEquals(List.of("anim.model.reloadempty", "anim.model.reloadoneleft", "anim.model.reload",
                        "anim.model.reload"),
                empty.stages().stream().map(ReloadTimeline.Stage::animation).toList());
    }

    /** @return the clip the first load iteration of a reload plays for the given starting magazine count. */
    private static String firstLoadAnimation(List<WeaponDefinition.ReloadStep> steps, int magazineAmmo) {
        return ReloadTimeline.plan(steps, magazineAmmo, 8, 20, 1).stages().stream()
                .filter(stage -> stage.inserts() && stage.kind() != ReloadStage.CHAMBER_LOAD)
                .findFirst()
                .map(ReloadTimeline.Stage::animation)
                .orElse(null);
    }

    @Test
    void aDeclaredTransitionDurationReachesTheClipAndAnAbsentOneKeepsTheDefault() {
        List<WeaponDefinition.ReloadStep> steps = List.of(
                new WeaponDefinition.ReloadStep("prepare", "anim.model.reload_start_empty", 0.38, null, null,
                        null, 0.0),
                new WeaponDefinition.ReloadStep("initial_load", "anim.model.load_first", 1.33, null, null));
        ReloadTimeline timeline = ReloadTimeline.plan(steps, 0, 8, 28, 1);

        List<ReloadClip> clips = timeline.animatedClips();
        assertEquals(0, clips.getFirst().transitionMillis(),
                "an authored transition_duration crosses to the client");
        assertEquals(150, clips.get(1).transitionMillis(),
                "an absent transition_duration keeps the documented 0.15 s default");
        assertEquals(9, clips.size(), "the cross-fade never adds a clip or a stage");
        assertEquals(timeline.totalTicks(), clips.getLast().startTick() + clips.getLast().durationTicks(),
                "adding a transition changes no tick of the schedule");
    }

    private static List<ReloadStage> kinds(ReloadTimeline timeline) {
        return timeline.stages().stream().map(ReloadTimeline.Stage::kind).toList();
    }

    /** The SPAS-12 sequence exactly as the content pack declares it, {@code -1} empty sentinel included. */
    private static List<WeaponDefinition.ReloadStep> shotgunSteps() {
        return List.of(
                new WeaponDefinition.ReloadStep("prepare", "anim.model.reload_start_empty", PREPARE_EMPTY_SECONDS,
                        "ammo_count == -1", null),
                new WeaponDefinition.ReloadStep("prepare", "anim.model.reload_start", PREPARE_SECONDS,
                        "ammo_count >= 0", null),
                new WeaponDefinition.ReloadStep("initial_load", "anim.model.load_first", LOAD_FIRST_SECONDS,
                        "reload_inserted_count == 0", null),
                new WeaponDefinition.ReloadStep("repeat_load", "anim.model.reload_loop", LOAD_LOOP_SECONDS,
                        "reload_inserted_count > 0", true),
                new WeaponDefinition.ReloadStep("finish", "anim.model.reload_finish", FINISH_SECONDS,
                        "reload_inserted_count >= 0", null));
    }
}
