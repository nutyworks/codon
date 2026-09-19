package works.nuty.codon.core.service;

import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.SourceLocation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Recent invocation recorders, scoped to the outer command-execution lifetime. */
public final class ExecutionFlowHistory {
    public static final int MAX_TRACES = 64;

    private final Deque<ExecutionFlowRecorder> traces = new ArrayDeque<>();
    private AtomicLong nextObservationOrder = new AtomicLong();

    public synchronized ExecutionFlowRecorder start(long invocationId, SourceLocation location) {
        while (traces.size() >= MAX_TRACES) traces.removeFirst();
        AtomicLong scope = nextObservationOrder;
        ExecutionFlowRecorder recorder = new ExecutionFlowRecorder(invocationId, location,
            scope::getAndIncrement, observed -> retain(observed, scope));
        traces.addLast(recorder);
        return recorder;
    }

    public synchronized List<ExecutionFlowTrace> snapshot() {
        List<ExecutionFlowTrace> result = new ArrayList<>(traces.size());
        for (ExecutionFlowRecorder trace : traces) result.add(trace.snapshot());
        return List.copyOf(result);
    }

    /** Resolve still-open stages only when their outer execution scope has actually ended. */
    public synchronized void finishExecution() {
        for (ExecutionFlowRecorder trace : List.copyOf(traces)) trace.finishExecution();
    }

    /** Records a stage stack on the most recently retained recorder for that invocation. */
    public synchronized void recordCallStack(long invocationId, int stageIndex, List<CallFrame> callStack) {
        var iterator = traces.descendingIterator();
        while (iterator.hasNext()) {
            ExecutionFlowRecorder recorder = iterator.next();
            if (recorder.invocationId() != invocationId) continue;
            recorder.recordCallStack(stageIndex, callStack);
            return;
        }
    }

    public synchronized void clear() {
        traces.clear();
        // Existing deferred recorders retain their old counter and cannot consume this lifetime.
        nextObservationOrder = new AtomicLong();
    }

    /** Makes a recorder with an actually observed stage recent, within its original lifetime. */
    private synchronized void retain(ExecutionFlowRecorder recorder, AtomicLong scope) {
        if (nextObservationOrder != scope) return;
        traces.remove(recorder);
        while (traces.size() >= MAX_TRACES) traces.removeFirst();
        traces.addLast(recorder);
    }
}
