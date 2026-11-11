package works.nuty.codon.core.support;

import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.port.DebuggerEventSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Test double that records the snapshots and resume notifications the engine publishes. */
public final class RecordingEventSink implements DebuggerEventSink {
    public final List<PauseSnapshot> pauses = new ArrayList<>();
    public final List<List<ExecutionFlowTrace>> completedFlows = new ArrayList<>();
    public int resumes = 0;
    public int steps = 0;
    public int continues = 0;
    public int breakpointChanges = 0;
    public boolean recipient = true;
    public final List<String> withoutRecipient = new ArrayList<>();

    @Override
    public boolean hasPauseRecipient() {
        return recipient;
    }

    @Override
    public void pauseWithoutRecipient(SourceLocation location, boolean released) {
        withoutRecipient.add((released ? "released " : "skipped ") + location);
    }

    @Override
    public void paused(PauseSnapshot snapshot) {
        pauses.add(snapshot);
    }

    @Override
    public void resumed() {
        resumes++;
    }

    @Override
    public void stepping() {
        steps++;
    }

    @Override
    public void continued() {
        continues++;
    }

    @Override
    public void executionFlowsCompleted(List<ExecutionFlowTrace> flows) {
        completedFlows.add(List.copyOf(flows));
    }

    @Override
    public void breakpointsChanged(Set<BlockLocation> blockBreakpoints) {
        breakpointChanges++;
    }

    public PauseSnapshot lastPause() {
        return pauses.get(pauses.size() - 1);
    }
}
