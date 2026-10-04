package works.nuty.codon.client.state;

import java.util.List;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;
import static org.junit.jupiter.api.Assertions.*;

class BreakpointTargetPolicyTest {
    private static final SourceLocation LOCATION = new SourceLocation.Function(new FunctionLocation(new FunctionId("test", "one"), 1));
    private static final String COMMAND = "say one";

    @Test void editorPinsOnlyItsLogicalMarkerAndMatchingSavedFingerprint() {
        var line = BreakpointTarget.whole(LOCATION);
        var legacy = BreakpointDefinition.plain(BreakpointTarget.stage(LOCATION, 0, COMMAND)).withEnabled(false);
        assertTrue(BreakpointTargetPolicy.editedMarker(line, line, legacy, COMMAND));
        assertFalse(BreakpointTargetPolicy.editedMarker(legacy.target(), line, legacy, COMMAND));
        assertFalse(BreakpointTargetPolicy.editedMarker(line, line, legacy, "say changed"));
        assertFalse(BreakpointTargetPolicy.editedMarker(line, line, legacy.withStaleSource(true), COMMAND));
        var otherLine = BreakpointTarget.whole(new SourceLocation.Function(new FunctionLocation(new FunctionId("test", "one"), 2)));
        assertFalse(BreakpointTargetPolicy.editedMarker(otherLine, line, legacy, COMMAND));
        var stage = BreakpointDefinition.plain(BreakpointTarget.stage(LOCATION, 1, COMMAND)).withEnabled(false);
        assertTrue(BreakpointTargetPolicy.editedMarker(stage.target(), stage.target(), stage, COMMAND));
        assertFalse(BreakpointTargetPolicy.editedMarker(line, stage.target(), stage, COMMAND));
        assertFalse(BreakpointTargetPolicy.editedMarker(line, line, stage, COMMAND));
        assertTrue(BreakpointTargetPolicy.editedMarker(line, line, BreakpointDefinition.plain(line), COMMAND));
    }

    @Test void singleStageUsesLineAndMultipleStagesRetainExactIndex() {
        assertEquals(BreakpointTarget.whole(LOCATION), BreakpointTargetPolicy.target(LOCATION, 0, COMMAND, 1));
        assertEquals(BreakpointTarget.stage(LOCATION, 1, COMMAND), BreakpointTargetPolicy.target(LOCATION, 1, COMMAND, 2));
        assertNull(BreakpointTargetPolicy.target(LOCATION, 0, COMMAND, 0));
        assertNull(BreakpointTargetPolicy.target(LOCATION, 2, COMMAND, 2));
    }

    @Test void parsedRunAndFunctionTargetsRemainSeparateFromTheLineEvenBeforeRecording() {
        String command = "execute as @s run function test:leaf";
        var spans = List.of(new ClientStagePreviewState.StageSpan(0, 8, 13, false),
            new ClientStagePreviewState.StageSpan(1, 14, 17, false),
            new ClientStagePreviewState.StageSpan(2, 18, command.length(), true));
        var preview = new ClientStagePreviewState.Preview(ClientStagePreviewState.Status.READY, command, spans);
        for (SourceLocation location : List.of(LOCATION,
            new SourceLocation.Block(new BlockLocation(1, 64, 2, "minecraft:overworld")))) {
            int count = BreakpointTargetPolicy.stageCount(command, preview, null);
            assertEquals(3, count);
            for (var span : spans) {
                var target = BreakpointTargetPolicy.target(location, span.index(), command, count);
                assertEquals(BreakpointTarget.stage(location, span.index(), command), target);
                assertNotEquals(BreakpointTarget.whole(location), target);
                assertNotEquals(BreakpointTarget.stage(location, span.index(), command + " changed"), target);
            }
        }
    }

    @Test void matchingParseDecidesMultiplicityForSingleFunctionAndExecuteCommands() {
        for (String command : List.of("function test:leaf", "execute run say one")) {
            var preview = new ClientStagePreviewState.Preview(ClientStagePreviewState.Status.READY, command,
                List.of(new ClientStagePreviewState.StageSpan(0, 0, command.length(), true)));
            assertEquals(BreakpointTarget.whole(LOCATION), BreakpointTargetPolicy.target(LOCATION, 0, command,
                BreakpointTargetPolicy.stageCount(command, preview, null)), "Text prefix does not decide line versus stage");
        }
    }

    @Test void legacyConditionsCoexistWithoutMigrationAndObsoleteTargetsStaySeparate() {
        var legacy = new BreakpointDefinition(BreakpointTarget.stage(LOCATION, 0, COMMAND), false,
            BreakpointCondition.count(BreakpointCondition.Kind.INPUT_COUNT, BreakpointCondition.Comparison.EQ, 1));
        var line = BreakpointDefinition.plain(BreakpointTarget.whole(LOCATION));
        var obsolete = BreakpointDefinition.plain(BreakpointTarget.stage(LOCATION, 0, "say old"));
        assertEquals(List.of(line, legacy), BreakpointTargetPolicy.lineDefinitions(LOCATION, COMMAND, 1, List.of(legacy, obsolete, line)));
        assertEquals(List.of(line), BreakpointTargetPolicy.lineDefinitions(LOCATION, COMMAND, 2, List.of(legacy, line)));
        assertFalse(legacy.enabled());
        assertEquals(1, legacy.condition().threshold());
    }

    @Test void oneRecordedModifierDoesNotProveASingleStage() {
        var modifier = stage(false, new CommandSnippet("execute as @s run say one", 0, 13));
        assertEquals(2, BreakpointTargetPolicy.stageCount(modifier.command().text(), null,
            new ExecutionFlowTrace(1, LOCATION, List.of(modifier), false)));
        var terminal = stage(true, new CommandSnippet(COMMAND, 0, COMMAND.length()));
        assertEquals(1, BreakpointTargetPolicy.stageCount(COMMAND, null, new ExecutionFlowTrace(1, LOCATION, List.of(terminal), false)));
        assertEquals(0, BreakpointTargetPolicy.stageCount(COMMAND, null, new ExecutionFlowTrace(1, LOCATION, List.of(terminal), true)));
    }

    @Test void unknownPreviewDefersOnlyAmbiguousCurrentLegacyStageZero() {
        var legacy = new BreakpointDefinition(BreakpointTarget.stage(LOCATION, 0, COMMAND), false,
            BreakpointCondition.count(BreakpointCondition.Kind.INPUT_COUNT, BreakpointCondition.Comparison.EQ, 1));
        var line = BreakpointDefinition.plain(BreakpointTarget.whole(LOCATION));
        assertTrue(BreakpointTargetPolicy.lineActionDeferred(LOCATION, COMMAND, 0, List.of(legacy)));
        assertTrue(BreakpointTargetPolicy.lineActionDeferred(LOCATION, COMMAND, 0, List.of(line, legacy.withEnabled(true))));
        assertFalse(BreakpointTargetPolicy.lineActionDeferred(LOCATION, COMMAND, 0, List.of(line)));
        assertFalse(BreakpointTargetPolicy.lineActionDeferred(LOCATION, COMMAND, 0,
            List.of(BreakpointDefinition.plain(BreakpointTarget.stage(LOCATION, 1, COMMAND)))));
        assertFalse(BreakpointTargetPolicy.lineActionDeferred(LOCATION, "say changed", 0, List.of(legacy)));
        assertFalse(BreakpointTargetPolicy.lineActionDeferred(LOCATION, COMMAND, 0, List.of(legacy.withStaleSource(true))));
    }

    @Test void missingLoadingAndStalePreviewBecomeUnambiguousOnlyAfterMatchingReady() {
        var legacy = BreakpointDefinition.plain(BreakpointTarget.stage(LOCATION, 0, COMMAND));
        var previews = new ClientStagePreviewState();
        assertTrue(BreakpointTargetPolicy.lineActionDeferred(LOCATION, COMMAND,
            BreakpointTargetPolicy.stageCount(COMMAND, previews.get(LOCATION), null), List.of(legacy)));
        long request = previews.begin(LOCATION);
        assertTrue(BreakpointTargetPolicy.lineActionDeferred(LOCATION, COMMAND,
            BreakpointTargetPolicy.stageCount(COMMAND, previews.get(LOCATION), null), List.of(legacy)));
        previews.accept(request, LOCATION, ClientStagePreviewState.Status.READY, "say changed",
            List.of(new ClientStagePreviewState.StageSpan(0, 0, 11, true)));
        assertTrue(BreakpointTargetPolicy.lineActionDeferred(LOCATION, COMMAND,
            BreakpointTargetPolicy.stageCount(COMMAND, previews.get(LOCATION), null), List.of(legacy)));
        request = previews.begin(LOCATION);
        previews.accept(request, LOCATION, ClientStagePreviewState.Status.READY, COMMAND,
            List.of(new ClientStagePreviewState.StageSpan(0, 0, COMMAND.length(), true)));
        assertFalse(BreakpointTargetPolicy.lineActionDeferred(LOCATION, COMMAND,
            BreakpointTargetPolicy.stageCount(COMMAND, previews.get(LOCATION), null), List.of(legacy)));
        assertEquals(List.of(legacy), BreakpointTargetPolicy.lineDefinitions(LOCATION, COMMAND, 1, List.of(legacy)));
        assertFalse(BreakpointTargetPolicy.lineActionDeferred(LOCATION, COMMAND, 2, List.of(legacy)));
        assertTrue(BreakpointTargetPolicy.lineDefinitions(LOCATION, COMMAND, 2, List.of(legacy)).isEmpty());
    }

    private static ExecutionFlowStage stage(boolean terminal, CommandSnippet command) {
        return new ExecutionFlowStage(0, command, List.of(), List.of(), List.of(), List.of(),
            0, 0, 0, terminal, 0, 0, true, true, false);
    }
}
