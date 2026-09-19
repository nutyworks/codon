package works.nuty.codon.adapter;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.service.ExecutionFlowRecorder;
import works.nuty.codon.core.service.ExecutionFlowHistory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/** One invocation, shared by its deferred continuations, never by cached function actions. */
public final class CommandTrace {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final ThreadLocal<CommandTrace> CURRENT = new ThreadLocal<>();
    public final long id;
    public final SourceLocation location;
    private final ExecutionFlowRecorder flow;
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

    public CommandTrace(SourceLocation location, ExecutionFlowHistory history) {
        this.id = NEXT_ID.getAndIncrement();
        this.location = location;
        this.flow = history.start(id, location);
    }

    private CommandTrace(long id, SourceLocation location, ExecutionFlowRecorder flow) {
        this.id = id;
        this.location = location;
        this.flow = flow;
    }

    /** A deferred continuation shares the invocation recorder but owns its occurrence cursor. */
    public CommandTrace forkForContinuation() {
        return new CommandTrace(id, location, flow);
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
    }

    /** Called immediately around the actual Brigadier modifier invocation. */
    public synchronized void modifierReturned() {
        pendingInputId = inputCursor < contextIds.size() ? contextIds.get(inputCursor) : 0;
        inputCursor++;
        pendingResult = true;
    }

    public synchronized void modifierFailed() {
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
        if (retainedCount < acceptedCount) flow.markTruncated();
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
            int limit = flow.remainingContextCapacity();
            List<PauseSource> outputs = outputMapper.apply(limit);
            int retainedCount = Math.min(Math.min(outputs.size(), limit), actualOutputCount);
            for (int i = 0; i < retainedCount; i++) {
                long inputId = i < contextIds.size() ? contextIds.get(i) : 0;
                nextContextIds.add(flow.addOutput(inputId, outputs.get(i)));
            }
            outputCount = actualOutputCount;
            if (retainedCount < actualOutputCount) flow.markTruncated();
        } else if (pendingResult || inputCursor != contextCount) {
            abandonStage();
            return;
        }
        flow.finishStage(outputCount, droppedCount);
        contextIds = List.copyOf(nextContextIds);
        contextCount = outputCount;
        stageActive = false;
    }

    /** Leaves a visible gap instead of inventing a relationship for a custom/error continuation. */
    public synchronized void abandonStage() {
        if (stageActive) {
            flow.abandonStage();
            initialized = false;
            contextIds = List.of();
            contextCount = 0;
        }
        stageActive = false;
        pendingResult = false;
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
        if (retainedCount < sourceCount) flow.markTruncated();
        contextIds = List.copyOf(ids);
        contextCount = sourceCount;
        initialized = true;
    }
}
