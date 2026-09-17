package works.nuty.bastion.adapter;

import org.junit.jupiter.api.Test;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.PauseSource;
import works.nuty.bastion.core.model.SourceLocation;
import works.nuty.bastion.core.model.Vec3d;
import works.nuty.bastion.core.service.ExecutionFlowHistory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandTraceTest {
    @Test
    void deferredCustomContinuationStartsWithFreshOccurrencesAcrossAnExplicitGap() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        SourceLocation location = new SourceLocation.Block(
            new BlockLocation(0, 64, 0, "minecraft:overworld"));
        PauseSource original = source(0);
        PauseSource filtered = source(1);
        CommandTrace trace = new CommandTrace(location, history);

        trace.beginStage(CommandSnippet.plain("execute if function test:condition run say ok"),
            List.of(original), false);
        CommandTrace continuation = trace.forkForContinuation();
        trace.abandonStage();
        continuation.beginStage(CommandSnippet.plain("say ok"), List.of(filtered), true);

        var stages = history.snapshot().getFirst().stages();
        assertFalse(stages.getFirst().lineageComplete());
        assertTrue(stages.getFirst().complete());
        assertNotEquals(stages.getFirst().inputs().getFirst().id(),
            stages.getLast().inputs().getFirst().id(),
            "a same-sized continuation must not reuse an unobserved input occurrence");
    }

    @Test
    void largeModifierResultMapsOnlyTheDetailedOccurrenceBudget() {
        ExecutionFlowHistory history = new ExecutionFlowHistory();
        SourceLocation location = new SourceLocation.Block(
            new BlockLocation(0, 64, 0, "minecraft:overworld"));
        CommandTrace trace = new CommandTrace(location, history);
        trace.beginStage(CommandSnippet.plain("execute as @e run say ok"), List.of(source(0)), false);
        trace.modifierReturned();
        AtomicInteger requested = new AtomicInteger(-1);

        trace.acceptModifierOutputs(10_000, limit -> {
            requested.set(limit);
            return IntStream.range(0, limit).mapToObj(CommandTraceTest::source).toList();
        });
        trace.beginStage(CommandSnippet.plain("say ok"), 10_000,
            ignored -> { throw new AssertionError("accepted output occurrences must be reused"); }, true);

        var flow = history.snapshot().getFirst();
        assertEquals(127, requested.get(), "the root occurrence already consumed one detail slot");
        assertEquals(10_000, flow.stages().getFirst().outputCount());
        assertEquals(127, flow.stages().getFirst().outputs().size());
        assertEquals(10_000, flow.finalContextCount());
        assertTrue(flow.truncated());
    }

    private static PauseSource source(double x) {
        return new PauseSource(new Vec3d(x, 64, 0), 0, 0, null, "minecraft:overworld");
    }
}
