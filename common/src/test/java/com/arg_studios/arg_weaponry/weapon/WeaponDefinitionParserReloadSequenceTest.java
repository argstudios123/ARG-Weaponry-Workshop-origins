package com.arg_studios.arg_weaponry.weapon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arg_studios.arg_weaponry.core.PackDiagnostic;
import com.arg_studios.arg_weaponry.core.ValidationReport;
import com.arg_studios.arg_weaponry.json.JsoncReader;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The staged reload schema, read through the real parser.
 *
 * <p>{@code readReloadSteps} is the package-private seam the loader uses, so these tests exercise exactly the code a
 * content pack goes through. The full {@code parse} needs the game's classes (the fire mode enum carries a codec) and is
 * therefore covered in game, not here.</p>
 */
class WeaponDefinitionParserReloadSequenceTest {

    private static final ResourceId IDENTITY = new ResourceId("arg_pack", "weapons/shotguns/spas12");

    @Test
    void aStagedSequenceIsReadWithoutASingleDiagnostic() throws Exception {
        ValidationReport report = new ValidationReport();
        WeaponDefinitionParser parser = parser(report);

        List<WeaponDefinition.ReloadStep> steps = parser.readReloadSteps(JsoncReader.parse("""
                {
                  "reload_sequences": [
                    { "stage": "prepare", "animation": "anim.model.reload_start_empty", "duration": 0.38,
                      "when": "ammo_count == 0" },
                    { "stage": "repeat_load", "animation": "anim.model.reload_loop", "duration": 0.75,
                      "repeat": true, "insert_at": 0.5, "when": "reload_inserted_count > 0" }
                  ]
                }
                """).getAsJsonObject());

        assertEquals(2, steps.size());
        assertEquals("prepare", steps.get(0).phase());
        assertEquals("anim.model.reload_start_empty", steps.get(0).animation());
        assertNull(steps.get(0).insertAt(), "an absent insert_at means 'at the end of the clip'");
        assertEquals(0.38, steps.get(0).insertOffsetSeconds(), 1.0E-9);
        assertTrue(steps.get(1).repeated());
        assertEquals(0.5, steps.get(1).insertAt(), 1.0E-9);
        assertEquals(0.5, steps.get(1).insertOffsetSeconds(), 1.0E-9);
        assertTrue(report.diagnostics().isEmpty(),
                "a well-formed staged sequence must not produce any finding: " + formatted(report));
    }

    @Test
    void theLegacyPhaseSpellingStillWorks() throws Exception {
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.ReloadStep> steps = parser(report).readReloadSteps(JsoncReader.parse("""
                {
                  "reload_sequences": [
                    { "phase": "reloading", "animation": "anim.model.reload_empty", "duration": 3.0,
                      "when": "reload_count == 0" }
                  ]
                }
                """).getAsJsonObject());

        assertEquals(1, steps.size());
        assertEquals("reloading", steps.get(0).phase());
        assertTrue(report.diagnostics().stream()
                        .noneMatch(diagnostic -> diagnostic.severity() != PackDiagnostic.Severity.INFO),
                "an unchanged legacy definition must keep loading quietly: " + formatted(report));
    }

    @Test
    void stageWinsWhenAnEntryDeclaresBothSpellings() throws Exception {
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.ReloadStep> steps = parser(report).readReloadSteps(JsoncReader.parse("""
                {
                  "reload_sequences": [
                    { "stage": "finish", "phase": "end", "animation": "anim.model.reload_finish", "duration": 0.67 }
                  ]
                }
                """).getAsJsonObject());

        assertEquals("finish", steps.getFirst().phase());
        assertTrue(report.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("both")),
                "the ambiguity is reported: " + formatted(report));
    }

    @Test
    void anUnrecognizedStageNameIsReportedButKeepsTheClip() throws Exception {
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.ReloadStep> steps = parser(report).readReloadSteps(JsoncReader.parse("""
                {
                  "reload_sequences": [
                    { "stage": "something_new", "animation": "anim.model.reload", "duration": 1.0 }
                  ]
                }
                """).getAsJsonObject());

        assertEquals(1, steps.size(), "an unknown stage never loses the clip");
        assertTrue(report.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("something_new")),
                "the creator is told the name is not part of the vocabulary: " + formatted(report));
    }

    @Test
    void anEntryWithoutAnyStageIsSkippedAndReported() throws Exception {
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.ReloadStep> steps = parser(report).readReloadSteps(JsoncReader.parse("""
                {
                  "reload_sequences": [
                    { "animation": "anim.model.reload", "duration": 1.0 }
                  ]
                }
                """).getAsJsonObject());

        assertTrue(steps.isEmpty());
        assertTrue(report.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.severity() == PackDiagnostic.Severity.WARNING),
                formatted(report));
    }

    @Test
    void anEntryWithoutAnAnimationIsKeptAsATimedBeat() throws Exception {
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.ReloadStep> steps = parser(report).readReloadSteps(JsoncReader.parse("""
                {
                  "reload_sequences": [
                    { "stage": "prepare", "duration": 0.45, "when": "ammo_count == -1" },
                    { "stage": "initial_load", "animation": "anim.model.load_first", "duration": 1.33,
                      "when": "reload_inserted_count == 0" }
                  ]
                }
                """).getAsJsonObject());

        assertEquals(2, steps.size(), "a stage without a clip is still a stage: it holds the reload for its duration");
        assertNull(steps.getFirst().animation());
        assertEquals(0.45, steps.getFirst().duration(), 1.0E-9);
        assertTrue(report.diagnostics().isEmpty(),
                "declaring no animation is legal, not a finding: " + formatted(report));
    }

    @Test
    void anInsertAtOutsideItsOwnClipIsClampedAndReported() throws Exception {
        ValidationReport report = new ValidationReport();

        List<WeaponDefinition.ReloadStep> steps = parser(report).readReloadSteps(JsoncReader.parse("""
                {
                  "reload_sequences": [
                    { "stage": "initial_load", "animation": "anim.model.load_first", "duration": 1.0,
                      "insert_at": 5.0 }
                  ]
                }
                """).getAsJsonObject());

        assertEquals(1.0, steps.getFirst().insertOffsetSeconds(), 1.0E-9);
        assertTrue(report.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.message().contains("clamped")), formatted(report));
    }

    @Test
    void aMissingReloadSequenceYieldsNoSteps() throws Exception {
        assertTrue(parser(new ValidationReport())
                .readReloadSteps(JsoncReader.parse("{}").getAsJsonObject()).isEmpty());
    }

    @Test
    void theOptionalTransitionDurationDefaultsAndCanBeDisabled() throws Exception {
        ValidationReport report = new ValidationReport();
        List<WeaponDefinition.ReloadStep> steps = parser(report).readReloadSteps(JsoncReader.parse("""
                {
                  "reload_sequences": [
                    { "stage": "prepare", "animation": "anim.model.a", "duration": 0.5 },
                    { "stage": "initial_load", "animation": "anim.model.b", "duration": 0.5,
                      "transition_duration": 0.3 },
                    { "stage": "repeat_load", "animation": "anim.model.c", "duration": 0.5,
                      "transition_duration": 0 }
                  ]
                }
                """).getAsJsonObject());

        assertEquals(3, steps.size());
        assertEquals(150, steps.get(0).transitionMillis(),
                "an entry that declares nothing keeps the documented 0.15 s default");
        assertNull(steps.get(0).transitionDuration());
        assertEquals(300, steps.get(1).transitionMillis(), "an authored transition_duration is used as written");
        assertEquals(0, steps.get(2).transitionMillis(), "a transition_duration of 0 disables blending");
        assertTrue(report.diagnostics().isEmpty(), "the property is part of the schema: " + formatted(report));
    }

    @Test
    void theSchemaKnowsEveryPropertyTheShippedDefinitionsUse() {
        assertNotNull(WeaponDefinitionParser.knownProperties());
        assertTrue(WeaponDefinitionParser.knownProperties().contains("shell_amount_reload"));
        assertTrue(WeaponDefinitionParser.knownProperties().contains("inspect_anims"),
                "inspect_anims is parsed, so it must not be reported as unknown");
    }

    private static WeaponDefinitionParser parser(ValidationReport report) {
        return new WeaponDefinitionParser("arg_default_pack", "spas12.json", IDENTITY, report);
    }

    private static String formatted(ValidationReport report) {
        StringBuilder builder = new StringBuilder();
        for (PackDiagnostic diagnostic : report.diagnostics()) {
            builder.append('\n').append(diagnostic.format());
        }
        return builder.toString();
    }
}
