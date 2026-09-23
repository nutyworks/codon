package works.nuty.codon.core.service;

import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.EntityRef;
import works.nuty.codon.core.model.ExecutionFlowContext;
import works.nuty.codon.core.model.ExecutionFlowEdge;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.Vec3d;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BreakpointConditionEvaluatorTest {
    private static final UUID EXECUTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void distinguishesFinalizedZeroFromAnUnmeasuredResult() {
        ExecutionFlowStage zeroOutputs = stage(List.of(context(1, source(EXECUTOR, 0))), List.of(), List.of(),
            List.of(1L), 1, 0, 1, true, true, false, false);
        assertResult(BreakpointCondition.count(BreakpointCondition.Kind.OUTPUT_COUNT,
            BreakpointCondition.Comparison.EQ, 0), zeroOutputs, BreakpointConditionEvaluator.Result.MATCH);
        assertResult(BreakpointCondition.event(BreakpointCondition.Kind.REMOVED), zeroOutputs,
            BreakpointConditionEvaluator.Result.MATCH);
        assertResult(BreakpointCondition.count(BreakpointCondition.Kind.REMOVED_COUNT,
            BreakpointCondition.Comparison.GT, 0), zeroOutputs, BreakpointConditionEvaluator.Result.MATCH);

        ExecutionFlowStage unfinished = stage(List.of(context(1, source(EXECUTOR, 0))), List.of(), List.of(),
            List.of(), 1, ExecutionFlowStage.UNMEASURED, ExecutionFlowStage.UNMEASURED,
            false, true, false, false);
        assertResult(BreakpointCondition.count(BreakpointCondition.Kind.OUTPUT_COUNT,
            BreakpointCondition.Comparison.EQ, 0), unfinished, BreakpointConditionEvaluator.Result.UNMEASURED);

        ExecutionFlowStage missingLineage = stage(List.of(context(1, source(EXECUTOR, 0))), List.of(), List.of(),
            List.of(), 1, ExecutionFlowStage.UNMEASURED, ExecutionFlowStage.UNMEASURED,
            true, false, false, false);
        assertResult(BreakpointCondition.event(BreakpointCondition.Kind.REMOVED), missingLineage,
            BreakpointConditionEvaluator.Result.UNMEASURED);
    }

    @Test
    void classifiesCreatedAndChangedFromRecordedSourceLineage() {
        PauseSource input = source(EXECUTOR, 0);
        ExecutionFlowContext same = context(2, input);
        ExecutionFlowContext differentExecutor = context(3, source(UUID.randomUUID(), 0));
        ExecutionFlowContext movedSource = context(4, source(EXECUTOR, 4));
        ExecutionFlowStage completed = stage(List.of(context(1, input)), List.of(same, differentExecutor, movedSource),
            List.of(new ExecutionFlowEdge(1, 2), new ExecutionFlowEdge(1, 3), new ExecutionFlowEdge(1, 4)),
            List.of(), 1, 3, 0, true, true, false, false);

        assertResult(BreakpointCondition.count(BreakpointCondition.Kind.CREATED_COUNT,
            BreakpointCondition.Comparison.EQ, 3), completed, BreakpointConditionEvaluator.Result.MATCH);
        assertResult(BreakpointCondition.event(BreakpointCondition.Kind.CREATED), completed,
            BreakpointConditionEvaluator.Result.MATCH);
        assertResult(BreakpointCondition.count(BreakpointCondition.Kind.CHANGED_COUNT,
            BreakpointCondition.Comparison.EQ, 0), completed, BreakpointConditionEvaluator.Result.MATCH);

        ExecutionFlowStage moved = stage(List.of(context(1, input)), List.of(movedSource),
            List.of(new ExecutionFlowEdge(1, 4)), List.of(), 1, 1, 0, true, true, false, false);
        assertResult(BreakpointCondition.count(BreakpointCondition.Kind.CREATED_COUNT,
            BreakpointCondition.Comparison.EQ, 0), moved, BreakpointConditionEvaluator.Result.MATCH);
        assertResult(BreakpointCondition.count(BreakpointCondition.Kind.CHANGED_COUNT,
            BreakpointCondition.Comparison.EQ, 1), moved, BreakpointConditionEvaluator.Result.MATCH);
        ExecutionFlowStage replacedExecutor = stage(List.of(context(1, input)), List.of(differentExecutor),
            List.of(new ExecutionFlowEdge(1, 3)), List.of(), 1, 1, 0, true, true, false, false);
        assertResult(BreakpointCondition.event(BreakpointCondition.Kind.CHANGED), replacedExecutor,
            BreakpointConditionEvaluator.Result.MATCH);

        ExecutionFlowStage truncated = stage(List.of(context(1, input)), List.of(movedSource),
            List.of(new ExecutionFlowEdge(1, 4)), List.of(), 1, 1, 0, true, true, true, false);
        assertResult(BreakpointCondition.event(BreakpointCondition.Kind.CHANGED), truncated,
            BreakpointConditionEvaluator.Result.UNMEASURED);
    }

    @Test
    void rejectsResultConditionsForTerminalStages() {
        ExecutionFlowStage terminal = stage(List.of(), List.of(), List.of(), List.of(), 0, 0, 0,
            true, true, false, true);
        assertResult(BreakpointCondition.count(BreakpointCondition.Kind.INPUT_COUNT,
            BreakpointCondition.Comparison.EQ, 0), terminal, BreakpointConditionEvaluator.Result.UNSUPPORTED);
    }

    private static void assertResult(BreakpointCondition condition, ExecutionFlowStage stage,
                                     BreakpointConditionEvaluator.Result expected) {
        assertEquals(expected, BreakpointConditionEvaluator.evaluate(condition, stage));
    }

    private static ExecutionFlowStage stage(List<ExecutionFlowContext> inputs, List<ExecutionFlowContext> outputs,
                                            List<ExecutionFlowEdge> edges, List<Long> dropped, int inputCount,
                                            int outputCount, int droppedCount, boolean complete,
                                            boolean lineageComplete, boolean truncated, boolean terminal) {
        return new ExecutionFlowStage(0, CommandSnippet.plain("execute as @s run say test"), inputs, outputs, edges,
            dropped, inputCount, outputCount, droppedCount, terminal, 0, 0, complete, lineageComplete, truncated);
    }

    private static ExecutionFlowContext context(long id, PauseSource source) {
        return new ExecutionFlowContext(id, source);
    }

    private static PauseSource source(UUID executor, int x) {
        return new PauseSource(new Vec3d(x, 64, 0), 0, 0, new EntityRef(executor, "executor"),
            "minecraft:overworld");
    }
}
