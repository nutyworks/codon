package works.nuty.codon.client.state;

import java.util.List;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.*;
import static org.junit.jupiter.api.Assertions.*;

class BreakpointTargetPolicyTest {
    private static final SourceLocation LOCATION = new SourceLocation.Function(new FunctionLocation(new FunctionId("test", "one"), 1));
    private static final String COMMAND = "say one";

    @Test void singleStageUsesLineAndMultipleStagesRetainExactIndex() {
        assertEquals(BreakpointTarget.whole(LOCATION), BreakpointTargetPolicy.target(LOCATION, 0, COMMAND, 1));
        assertEquals(BreakpointTarget.stage(LOCATION, 1, COMMAND), BreakpointTargetPolicy.target(LOCATION, 1, COMMAND, 2));
        assertNull(BreakpointTargetPolicy.target(LOCATION, 0, COMMAND, 0));
        assertNull(BreakpointTargetPolicy.target(LOCATION, 2, COMMAND, 2));
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

    private static ExecutionFlowStage stage(boolean terminal, CommandSnippet command) {
        return new ExecutionFlowStage(0, command, List.of(), List.of(), List.of(), List.of(),
            0, 0, 0, terminal, 0, 0, true, true, false);
    }
}
