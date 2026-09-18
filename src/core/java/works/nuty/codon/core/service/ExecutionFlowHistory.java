package works.nuty.codon.core.service;

import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.SourceLocation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Recent invocation recorders, scoped to the outer command-execution lifetime. */
public final class ExecutionFlowHistory {
    public static final int MAX_TRACES = 6;

    private final Deque<ExecutionFlowRecorder> traces = new ArrayDeque<>();

    public synchronized ExecutionFlowRecorder start(long invocationId, SourceLocation location) {
        while (traces.size() >= MAX_TRACES) traces.removeFirst();
        ExecutionFlowRecorder recorder = new ExecutionFlowRecorder(invocationId, location);
        traces.addLast(recorder);
        return recorder;
    }

    public synchronized List<ExecutionFlowTrace> snapshot() {
        List<ExecutionFlowTrace> result = new ArrayList<>(traces.size());
        for (ExecutionFlowRecorder trace : traces) result.add(trace.snapshot());
        return List.copyOf(result);
    }

    public synchronized void clear() {
        traces.clear();
    }
}
