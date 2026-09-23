package works.nuty.codon.adapter;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowWarning.Reason;
import works.nuty.codon.core.service.ExecutionFlowRecorder;
import works.nuty.codon.core.service.ExecutionFlowHistory;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/** One invocation, shared by its deferred continuations, never by cached function actions. */
public final class CommandTrace {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final ThreadLocal<CommandTrace> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<QueueScope> QUEUE_SCOPE = new ThreadLocal<>();
    public final long id;
    public final SourceLocation location;
    private final ExecutionFlowRecorder flow;
    private final StageResultObserver stageResults;
    private List<Long> contextIds = List.of();
    private int contextCount;
    private List<Long> nextContextIds = List.of();
    private boolean initialized;
    private boolean stageActive;
    private int inputCursor;
    private int outputCount;
    private int droppedCount;
    private long pendingInputId;
    private boolean pendingResult;
    private int flowStageIndex = -1;
    private boolean deferred;
    private boolean continuationReady;
    private boolean continuationScheduled;
    private int deferredRegistered;
    private int deferredCompleted;
    private @Nullable CommandTrace continuationParent;
    private @Nullable QueueScope pendingScope;
    private Reason interruptionReason = Reason.UNFINISHED_STAGE;
    private String interruptionDetail = "";
    private int interruptionLimit = -1;

    public CommandTrace(SourceLocation location, ExecutionFlowHistory history) {
        this(location, history, (id, source, stage) -> { });
    }

    public CommandTrace(SourceLocation location, ExecutionFlowHistory history, StageResultObserver stageResults) {
        this.id = NEXT_ID.getAndIncrement();
        this.location = location;
        this.flow = history.start(id, location);
        this.stageResults = stageResults;
    }

    private CommandTrace(long id, SourceLocation location, ExecutionFlowRecorder flow,
                         StageResultObserver stageResults) {
        this.id = id;
        this.location = location;
        this.flow = flow;
        this.stageResults = stageResults;
    }

    @FunctionalInterface
    public interface StageResultObserver {
        void completed(long chainId, SourceLocation location, ExecutionFlowStage stage);
    }

    /** A deferred continuation shares the invocation recorder but owns its occurrence cursor. */
    public CommandTrace forkForContinuation() {
        synchronized (this) {
            CommandTrace continuation = new CommandTrace(id, location, flow, stageResults);
            if (deferred) {
                continuationScheduled = true;
                continuation.continuationParent = this;
            } else if (continuationReady) {
                continuation.continuationParent = this;
            }
            return continuation;
        }
    }

    public static @Nullable CommandTrace current() { return CURRENT.get(); }

    public static void setCurrent(@Nullable CommandTrace trace) {
        if (trace == null) CURRENT.remove(); else CURRENT.set(trace);
    }

    public synchronized void beginStage(CommandSnippet command, List<PauseSource> sources, boolean terminal) {
        beginStage(command, sources.size(), limit -> sources.subList(0, Math.min(limit, sources.size())), terminal);
    }

    /**
     * Starts a stage while mapping at most the remaining detailed-occurrence budget. Aggregate
     * counts remain exact even when a large selector exceeds that budget.
     */
    public synchronized void beginStage(CommandSnippet command, int sourceCount,
                                        IntFunction<List<PauseSource>> sourceMapper, boolean terminal) {
        if (continuationParent != null) {
            CommandTrace parent = continuationParent;
            continuationParent = null;
            if (parent.continuationBegan(sourceCount)) inheritOccurrences(parent);
        }
        // Vanilla exposes the prior stage's accepted outputs as the next stage's input list.
        // Closing here avoids a fragile local capture around ContextChain.nextStage().
        if (stageActive) finishStage(sourceCount, sourceMapper);
        ensureContextIds(sourceCount, sourceMapper);
        flowStageIndex = -1;
        if (!flow.beginStage(command, contextIds, sourceCount, terminal)) return;
        flowStageIndex = flow.activeStageIndex();
        if (terminal) return;
        stageActive = true;
        nextContextIds = new ArrayList<>();
        inputCursor = 0;
        outputCount = 0;
        droppedCount = 0;
        pendingInputId = 0;
        pendingResult = false;
        interruptionReason = Reason.UNFINISHED_STAGE;
        interruptionDetail = "";
        interruptionLimit = -1;
    }

    /** Called immediately around the actual Brigadier modifier invocation. */
    public synchronized void modifierReturned() {
        if (!stageActive) return;
        if (pendingResult) {
            abandonStage(Reason.MODIFIER_RESULT_MISMATCH, "A modifier returned before its preceding result was accepted.");
            return;
        }
        pendingInputId = inputCursor < contextIds.size() ? contextIds.get(inputCursor) : 0;
        inputCursor++;
        pendingResult = true;
    }

    public synchronized void modifierFailed() {
        if (!stageActive) return;
        long failedInput = inputCursor < contextIds.size() ? contextIds.get(inputCursor) : 0;
        inputCursor++;
        flow.inputDropped(failedInput);
        droppedCount++;
        pendingInputId = 0;
        pendingResult = false;
    }

    /** Called only after vanilla has accepted the returned collection into its next-source list. */
    public synchronized void acceptModifierOutputs(List<PauseSource> outputs) {
        acceptModifierOutputs(outputs.size(), limit -> outputs.subList(0, Math.min(limit, outputs.size())));
    }

    public synchronized void acceptModifierOutputs(int acceptedCount,
                                                   IntFunction<List<PauseSource>> outputMapper) {
        if (!stageActive || !pendingResult) return;
        outputCount += acceptedCount;
        if (acceptedCount == 0) {
            droppedCount++;
            flow.inputDropped(pendingInputId);
        }
        int limit = flow.remainingContextCapacity();
        List<PauseSource> outputs = outputMapper.apply(limit);
        int retainedCount = Math.min(Math.min(outputs.size(), limit), acceptedCount);
        for (int i = 0; i < retainedCount; i++) {
            PauseSource output = outputs.get(i);
            nextContextIds.add(flow.addOutput(pendingInputId, output));
        }
        if (retainedCount < acceptedCount) markMissingDetails(limit, acceptedCount, retainedCount);
        pendingInputId = 0;
        pendingResult = false;
    }

    public synchronized void finishStage(List<PauseSource> outputs) {
        finishStage(outputs.size(), limit -> outputs.subList(0, Math.min(limit, outputs.size())));
    }

    private void finishStage(int actualOutputCount, IntFunction<List<PauseSource>> outputMapper) {
        if (!stageActive) return;
        // A context-chain node without a redirect modifier is an observed one-to-one pass-through.
        if (inputCursor == 0) {
            if (actualOutputCount != contextCount) {
                abandonStage(Reason.MODIFIER_RESULT_MISMATCH,
                    "A pass-through stage changed its input count from " + contextCount + " to " + actualOutputCount + ".");
                return;
            }
            int limit = flow.remainingContextCapacity();
            List<PauseSource> outputs = outputMapper.apply(limit);
            int retainedCount = Math.min(Math.min(outputs.size(), limit), actualOutputCount);
            for (int i = 0; i < retainedCount; i++) {
                long inputId = i < contextIds.size() ? contextIds.get(i) : 0;
                nextContextIds.add(flow.addOutput(inputId, outputs.get(i)));
            }
            outputCount = actualOutputCount;
            if (retainedCount < actualOutputCount) markMissingDetails(limit, actualOutputCount, retainedCount);
        } else if (pendingResult || inputCursor != contextCount || outputCount != actualOutputCount) {
            abandonStage(Reason.MODIFIER_RESULT_MISMATCH,
                "Observed " + inputCursor + " of " + contextCount + " inputs and " + outputCount
                    + " of " + actualOutputCount + " outputs; unaccepted result: " + pendingResult + ".");
            return;
        }
        flow.finishStage(outputCount, droppedCount);
        notifyStageCompleted();
        contextIds = List.copyOf(nextContextIds);
        contextCount = outputCount;
        stageActive = false;
    }

    /** Leaves a visible gap instead of inventing a relationship for a custom/error continuation. */
    public synchronized void abandonStage() {
        abandonStage(Reason.UNFINISHED_STAGE, "The modifier stage ended before its outputs were observed.");
    }

    public synchronized void abandonStage(Reason reason, String detail) {
        if (stageActive) {
            flow.abandonStage(reason, detail);
            initialized = false;
            contextIds = List.of();
            contextCount = 0;
        }
        stageActive = false;
        pendingResult = false;
        deferred = false;
        continuationReady = false;
        clearPendingContinuation();
    }

    /** The exact list forwarded by return run, observed at its real continuation enqueue. */
    public synchronized void forwardContinuation(int sourceCount, IntFunction<List<PauseSource>> sourceMapper) {
        if (!stageActive) return;
        finishStage(sourceCount, sourceMapper);
        continuationReady = initialized && !stageActive;
        if (continuationReady) awaitContinuation();
    }

    /** Leaves this stage open while vanilla's isolated condition calls execute on the queue. */
    public synchronized void deferStage() {
        if (!stageActive) return;
        deferred = true;
        continuationScheduled = false;
        deferredRegistered = deferredCompleted = 0;
        flow.deferStage();
        awaitContinuation();
    }

    /** Captured once per actual source iteration, even if several occurrences use the same source. */
    public synchronized @Nullable DeferredInput registerDeferredInput() {
        if (!stageActive || !deferred) return null;
        int index = deferredRegistered++;
        long inputId = index < contextIds.size() ? contextIds.get(index) : 0;
        return new DeferredInput(this, inputId);
    }

    /** No queued continuation means vanilla ended this condition without forwarding any sources. */
    public synchronized void finishDeferredScheduling() {
        if (!stageActive || !deferred || continuationScheduled) return;
        if (deferredRegistered != 0) {
            abandonStage(Reason.CONTINUATION_NOT_RESUMED, "Condition calls were queued without a continuation.");
            return;
        }
        for (long inputId : contextIds) flow.inputDropped(inputId);
        outputCount = 0;
        droppedCount = contextCount;
        completeObservedStage();
    }

    private synchronized boolean finishDeferredStage(int actualOutputCount) {
        if (!stageActive || !deferred) return false;
        if (deferredRegistered != contextCount || deferredCompleted != deferredRegistered
            || actualOutputCount != outputCount) {
            abandonStage(Reason.MODIFIER_RESULT_MISMATCH,
                "Condition callbacks completed " + deferredCompleted + "/" + deferredRegistered
                    + " for " + contextCount + " inputs; observed outputs " + outputCount + "/" + actualOutputCount + ".");
            return false;
        }
        completeObservedStage();
        return true;
    }

    private synchronized boolean continuationBegan(int actualOutputCount) {
        if (deferred) return finishDeferredStage(actualOutputCount);
        if (!continuationReady) return false;
        continuationReady = false;
        clearPendingContinuation();
        if (contextCount == actualOutputCount) return true;
        flow.continuationNotObserved(flowStageIndex, Reason.MODIFIER_RESULT_MISMATCH, -1,
            "Queued continuation received " + actualOutputCount + " inputs; expected " + contextCount + ".");
        return false;
    }

    private void completeObservedStage() {
        flow.finishStage(outputCount, droppedCount);
        notifyStageCompleted();
        contextIds = List.copyOf(nextContextIds);
        contextCount = outputCount;
        stageActive = deferred = false;
        clearPendingContinuation();
    }

    private void awaitContinuation() {
        pendingScope = QUEUE_SCOPE.get();
        if (pendingScope != null) pendingScope.pending.add(this);
    }

    private void clearPendingContinuation() {
        if (pendingScope != null) pendingScope.pending.remove(this);
        pendingScope = null;
    }

    private synchronized void queueEnded(Reason reason, int limit, String detail) {
        if (deferred || continuationReady) {
            flow.continuationNotObserved(flowStageIndex, reason, limit, detail);
            stageActive = deferred = continuationReady = false;
        }
        clearPendingContinuation();
    }

    /** Keeps only unresolved continuations alive, independently of the bounded display history. */
    public static QueueScope openQueueScope() {
        QueueScope scope = new QueueScope(QUEUE_SCOPE.get());
        QUEUE_SCOPE.set(scope);
        return scope;
    }

    public static final class QueueScope {
        private final @Nullable QueueScope parent;
        private final Set<CommandTrace> pending = new LinkedHashSet<>();

        private QueueScope(@Nullable QueueScope parent) { this.parent = parent; }

        public void finish(Reason reason, int limit, String detail) {
            try {
                for (CommandTrace trace : List.copyOf(pending)) trace.queueEnded(reason, limit, detail);
            } finally {
                pending.clear();
                if (parent == null) QUEUE_SCOPE.remove(); else QUEUE_SCOPE.set(parent);
            }
        }
    }

    private void inheritOccurrences(CommandTrace parent) {
        contextIds = parent.contextIds;
        contextCount = parent.contextCount;
        initialized = parent.initialized;
    }

    /** A fallback reason only becomes a warning if the stage actually ends unfinished. */
    public synchronized void interrupted(Reason reason, int limit, String detail) {
        interruptionReason = reason;
        interruptionLimit = limit;
        interruptionDetail = detail;
    }

    public synchronized void invocationEnded(boolean failed) {
        if (!stageActive || (deferred && !failed)) return;
        if (interruptionLimit >= 0) flow.markTruncated(interruptionReason, interruptionLimit, interruptionDetail);
        abandonStage(interruptionReason, interruptionDetail);
    }

    private void markMissingDetails(int available, int actual, int retained) {
        boolean capacityExceeded = available < actual;
        flow.markTruncated(capacityExceeded ? Reason.CONTEXT_LIMIT : Reason.MAPPING_SHORTFALL,
            capacityExceeded ? ExecutionFlowRecorder.MAX_CONTEXTS : -1,
            "Retained " + retained + " of " + actual + " context details.");
    }

    /** One actual condition callback, associated with its input occurrence at scheduling time. */
    public static final class DeferredInput {
        private final CommandTrace trace;
        private final long inputId;
        private boolean completed;

        private DeferredInput(CommandTrace trace, long inputId) {
            this.trace = trace;
            this.inputId = inputId;
        }

        public void acceptOutputs(int acceptedCount, IntFunction<List<PauseSource>> outputMapper) {
            synchronized (trace) {
                if (!trace.stageActive || !trace.deferred) return;
                if (completed || acceptedCount < 0) {
                    trace.abandonStage(Reason.MODIFIER_RESULT_MISMATCH,
                        "A condition callback repeated or removed previously accepted outputs.");
                    return;
                }
                completed = true;
                trace.deferredCompleted++;
                trace.outputCount += acceptedCount;
                if (acceptedCount == 0) {
                    trace.droppedCount++;
                    trace.flow.inputDropped(inputId);
                }
                int limit = trace.flow.remainingContextCapacity();
                List<PauseSource> outputs = outputMapper.apply(limit);
                int retained = Math.min(Math.min(outputs.size(), limit), acceptedCount);
                for (int i = 0; i < retained; i++) {
                    trace.nextContextIds.add(trace.flow.addOutput(inputId, outputs.get(i)));
                }
                if (retained < acceptedCount) trace.markMissingDetails(limit, acceptedCount, retained);
            }
        }

        public void failed(Throwable failure) {
            trace.abandonStage(Reason.EXECUTION_ERROR, failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    public void executionStarted() {
        flow.executionStarted();
    }

    public void executionResult(boolean success) {
        flow.executionResult(success);
    }

    /** The retained Flow stage for the most recently begun command stage, if any. */
    public synchronized int flowStageIndex() {
        return flowStageIndex;
    }

    private void ensureContextIds(int sourceCount, IntFunction<List<PauseSource>> sourceMapper) {
        if (initialized && contextCount == sourceCount) return;
        int limit = flow.remainingContextCapacity();
        List<PauseSource> sources = sourceMapper.apply(limit);
        int retainedCount = Math.min(Math.min(sources.size(), limit), sourceCount);
        List<Long> ids = new ArrayList<>(retainedCount);
        for (int i = 0; i < retainedCount; i++) ids.add(flow.createContext(sources.get(i)));
        // beginStage reports an input-detail shortfall against its exact stage and command.
        contextIds = List.copyOf(ids);
        contextCount = sourceCount;
        initialized = true;
    }

    private void notifyStageCompleted() {
        if (flowStageIndex < 0) return;
        var stages = flow.snapshot().stages();
        if (flowStageIndex < stages.size()) stageResults.completed(id, location, stages.get(flowStageIndex));
    }
}
