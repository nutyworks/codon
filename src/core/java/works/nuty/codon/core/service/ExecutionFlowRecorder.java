package works.nuty.codon.core.service;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.ExecutionFlowContext;
import works.nuty.codon.core.model.ExecutionFlowEdge;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.ExecutionFlowWarning;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Mutable, bounded recorder for one invocation. Its adapter caller supplies only relationships
 * observed while Minecraft iterates the actual modifier results.
 */
public final class ExecutionFlowRecorder {
    public static final int MAX_STAGES = 24;
    public static final int MAX_CONTEXTS = 128;
    public static final int MAX_EDGES = 256;
    public static final int MAX_STACK_FRAMES = 32;

    private final long invocationId;
    private final SourceLocation location;
    private final LongSupplier observationOrders;
    private final Consumer<ExecutionFlowRecorder> onStageObserved;
    private final List<MutableStage> stages = new ArrayList<>();
    private final Map<Long, ExecutionFlowContext> contexts = new LinkedHashMap<>();
    private final List<ExecutionFlowWarning> warnings = new ArrayList<>();
    private final List<PendingWarning> pendingWarnings = new ArrayList<>();
    private long nextContextId = 1;
    private int edgeCount;
    private boolean truncated;
    private @Nullable MutableStage activeStage;

    ExecutionFlowRecorder(long invocationId, SourceLocation location) {
        this(invocationId, location, () -> -1, ignored -> { });
    }

    ExecutionFlowRecorder(long invocationId, SourceLocation location, LongSupplier observationOrders) {
        this(invocationId, location, observationOrders, ignored -> { });
    }

    ExecutionFlowRecorder(long invocationId, SourceLocation location, LongSupplier observationOrders,
                            Consumer<ExecutionFlowRecorder> onStageObserved) {
        this.invocationId = invocationId;
        this.location = location;
        this.observationOrders = observationOrders;
        this.onStageObserved = onStageObserved;
    }

    /** Creates a context occurrence. Zero is returned when only aggregate counts can be retained. */
    public synchronized long createContext(PauseSource source) {
        if (contexts.size() >= MAX_CONTEXTS) {
            markWarning(ExecutionFlowWarning.Reason.CONTEXT_LIMIT, MAX_CONTEXTS,
                "Context detail limit reached");
            return 0;
        }
        long id = nextContextId++;
        contexts.put(id, new ExecutionFlowContext(id, source));
        return id;
    }

    public synchronized boolean canRecordContext() {
        return contexts.size() < MAX_CONTEXTS;
    }

    public synchronized int remainingContextCapacity() {
        return Math.max(0, MAX_CONTEXTS - contexts.size());
    }

    /** Records that aggregate counts exceed the detailed occurrence graph retained in memory. */
    public synchronized void markTruncated() {
        markTruncated(ExecutionFlowWarning.Reason.UNSPECIFIED, -1, "Recording was marked incomplete");
    }

    public synchronized void markTruncated(ExecutionFlowWarning.Reason reason, int limit, String detail) {
        markWarning(reason, limit, detail);
    }

    /** Starts a stage with occurrence ids that already correspond to its actual input list. */
    public boolean beginStage(CommandSnippet command, List<Long> inputIds, int inputCount, boolean terminal) {
        synchronized (this) {
            if (stages.size() >= MAX_STAGES) {
                addWarning(ExecutionFlowWarning.Reason.STAGE_LIMIT, stages.size(), command, MAX_STAGES,
                    "Stage detail limit reached; this clause was not retained");
                truncated = true;
                activeStage = null;
                return false;
            }
            MutableStage stage = new MutableStage(stages.size(), observationOrders.getAsLong(), command,
                retained(inputIds), inputCount, terminal);
            if (terminal) {
                stage.outputIds.addAll(stage.inputIds);
                stage.outputCount = inputCount;
            }
            stages.add(stage);
            activeStage = stage;
            for (PendingWarning warning : pendingWarnings)
                addWarning(warning.reason, stage.index, command, warning.limit, warning.detail);
            pendingWarnings.clear();
            boolean missingId = inputIds.size() != stage.inputIds.size();
            if (missingId) {
                addWarning(ExecutionFlowWarning.Reason.MISSING_CONTEXT, stage.index, command, -1,
                    "Some stage input IDs were not retained");
            }
            if (stage.inputIds.size() < inputCount) {
                ExecutionFlowWarning.Reason reason = contexts.size() >= MAX_CONTEXTS ? ExecutionFlowWarning.Reason.CONTEXT_LIMIT
                    : ExecutionFlowWarning.Reason.MAPPING_SHORTFALL;
                addWarning(reason, stage.index, command, reason == ExecutionFlowWarning.Reason.CONTEXT_LIMIT ? MAX_CONTEXTS : -1,
                    reason == ExecutionFlowWarning.Reason.CONTEXT_LIMIT ? "Input context detail limit reached" :
                        "Some stage inputs have no recorded context");
            }
        }
        onStageObserved.accept(this);
        return true;
    }

    /** Index of the stage currently being recorded, or -1 when no stage was retained. */
    public synchronized int activeStageIndex() {
        return activeStage == null ? -1 : activeStage.index;
    }

    /** Stores the exact top-first stack observed for a retained stage, within the stage cap. */
    public synchronized void recordCallStack(int stageIndex, List<CallFrame> callStack) {
        if (stageIndex < 0 || stageIndex >= stages.size() || callStack.isEmpty()) return;
        MutableStage stage = stages.get(stageIndex);
        CallFrame top = callStack.getFirst();
        if (top.invocationId() != invocationId || top.flowStageIndex() != stageIndex) return;
        if (callStack.size() > MAX_STACK_FRAMES) {
            addWarning(ExecutionFlowWarning.Reason.CALL_STACK_LIMIT, stage.index, stage.command, MAX_STACK_FRAMES,
                "Call stack detail limit reached");
        }
        stage.callStack = List.copyOf(callStack.subList(0, Math.min(callStack.size(), MAX_STACK_FRAMES)));
    }

    /** Adds one accepted modifier output and returns its occurrence id, or zero when truncated. */
    public synchronized long addOutput(long inputId, PauseSource output) {
        MutableStage stage = activeStage;
        if (stage == null || stage.terminal) return 0;
        long outputId = createContext(output);
        if (outputId != 0) stage.outputIds.add(outputId);
        if (inputId != 0 && outputId != 0) {
            if (edgeCount < MAX_EDGES) {
                stage.edges.add(new ExecutionFlowEdge(inputId, outputId));
                edgeCount++;
            } else {
                markWarning(ExecutionFlowWarning.Reason.EDGE_LIMIT, MAX_EDGES, "Edge detail limit reached");
            }
        } else if ((inputId == 0 || outputId == 0) && !(outputId == 0 && contexts.size() >= MAX_CONTEXTS)) {
            markWarning(ExecutionFlowWarning.Reason.MISSING_CONTEXT, -1, "An input or output context was not retained");
        }
        return outputId;
    }

    public synchronized void inputDropped(long inputId) {
        MutableStage stage = activeStage;
        if (stage != null && !stage.terminal && inputId != 0) stage.droppedInputIds.add(inputId);
    }

    public synchronized void finishStage(int outputCount, int droppedCount) {
        MutableStage stage = activeStage;
        if (stage == null) return;
        stage.outputCount = outputCount;
        stage.droppedCount = droppedCount;
        stage.complete = true;
        stage.deferred = false;
        activeStage = null;
    }

    /** Marks a custom/error boundary whose continuation relationship was not directly observed. */
    public synchronized void abandonStage() {
        abandonStage(ExecutionFlowWarning.Reason.UNFINISHED_STAGE, "Stage did not finish recording");
    }

    public void abandonStage(ExecutionFlowWarning.Reason reason, String detail) {
        boolean changed = false;
        synchronized (this) {
            MutableStage stage = activeStage;
            if (stage != null && !stage.terminal) {
                stage.complete = true;
                stage.lineageComplete = false;
                stage.deferred = false;
                addWarning(reason, stage.index, stage.command, -1, detail);
                changed = true;
            }
            activeStage = null;
        }
        if (changed) onStageObserved.accept(this);
    }

    /** Marks the active nonterminal stage as awaiting a continuation callback. */
    public synchronized void deferStage() {
        if (activeStage != null && !activeStage.terminal) activeStage.deferred = true;
    }

    /** Finalizes any pending stage when its expected continuation never arrived. */
    public synchronized void finishExecution() {
        MutableStage stage = activeStage;
        if (stage != null && !stage.terminal && !stage.complete) {
            abandonStage(stage.deferred ? ExecutionFlowWarning.Reason.CONTINUATION_NOT_RESUMED
                : ExecutionFlowWarning.Reason.UNFINISHED_STAGE,
                stage.deferred ? "Queued continuation was not observed before execution ended" : "Stage did not finish recording");
        }
    }

    /** Reports an expected child which never began, including an already recorded return-run stage. */
    public void continuationNotObserved(int stageIndex, ExecutionFlowWarning.Reason reason, int limit, String detail) {
        synchronized (this) {
            if (stageIndex < 0 || stageIndex >= stages.size()) return;
            MutableStage stage = stages.get(stageIndex);
            if (!stage.complete) {
                stage.complete = true;
                stage.lineageComplete = false;
            }
            stage.deferred = false;
            if (activeStage == stage) activeStage = null;
            addWarning(reason, stage.index, stage.command, limit, detail);
        }
        // A long condition function can evict its waiting parent from the display history.
        onStageObserved.accept(this);
    }

    public synchronized void executionStarted() {
        MutableStage terminal = terminalStage();
        if (terminal != null) terminal.executionCount = Math.max(0, terminal.executionCount) + 1;
    }

    public synchronized void executionResult(boolean success) {
        MutableStage terminal = terminalStage();
        if (terminal != null && terminal.executionCount > 0) {
            terminal.successCount = Math.max(0, terminal.successCount);
            if (success && terminal.successCount < terminal.executionCount) terminal.successCount++;
        }
    }

    public synchronized ExecutionFlowTrace snapshot() {
        List<ExecutionFlowStage> result = new ArrayList<>(stages.size());
        for (MutableStage stage : stages) result.add(stage.snapshot(contexts));
        return new ExecutionFlowTrace(invocationId, location, result, truncated, warnings);
    }

    synchronized long invocationId() {
        return invocationId;
    }

    private @Nullable MutableStage terminalStage() {
        if (stages.isEmpty()) return null;
        MutableStage stage = stages.getLast();
        return stage.terminal ? stage : null;
    }

    private List<Long> retained(List<Long> ids) {
        List<Long> result = new ArrayList<>(Math.min(ids.size(), MAX_CONTEXTS));
        for (long id : ids) if (id != 0 && contexts.containsKey(id)) result.add(id);
        return result;
    }

    private void markWarning(ExecutionFlowWarning.Reason reason, int limit, String detail) {
        truncated = true;
        if (activeStage == null) {
            if (pendingWarnings.stream().noneMatch(warning -> warning.reason == reason))
                pendingWarnings.add(new PendingWarning(reason, limit, detail));
        } else {
            activeStage.truncated = true;
            addWarning(reason, activeStage.index, activeStage.command, limit, detail);
        }
    }

    private void addWarning(ExecutionFlowWarning.Reason reason, int stageIndex, CommandSnippet command, int limit,
                            String detail) {
        truncated = true;
        if (stageIndex >= 0 && stageIndex < stages.size()) stages.get(stageIndex).truncated = true;
        if (warnings.stream().noneMatch(warning -> warning.stageIndex() == stageIndex && warning.reason() == reason))
            warnings.add(new ExecutionFlowWarning(reason, stageIndex, command, limit, detail));
    }

    private record PendingWarning(ExecutionFlowWarning.Reason reason, int limit, String detail) { }

    private static final class MutableStage {
        private final int index;
        private final long observationOrder;
        private final CommandSnippet command;
        private final List<Long> inputIds;
        private final List<Long> outputIds = new ArrayList<>();
        private final List<ExecutionFlowEdge> edges = new ArrayList<>();
        private final List<Long> droppedInputIds = new ArrayList<>();
        private final int inputCount;
        private final boolean terminal;
        private int outputCount = ExecutionFlowStage.UNMEASURED;
        private int droppedCount;
        private int executionCount = ExecutionFlowStage.UNMEASURED;
        private int successCount = ExecutionFlowStage.UNMEASURED;
        private boolean complete;
        private boolean lineageComplete = true;
        private boolean truncated;
        private boolean deferred;
        private List<CallFrame> callStack = List.of();

        private MutableStage(int index, long observationOrder, CommandSnippet command, List<Long> inputIds,
                             int inputCount, boolean terminal) {
            this.index = index;
            this.observationOrder = observationOrder;
            this.command = command;
            this.inputIds = inputIds;
            this.inputCount = inputCount;
            this.terminal = terminal;
            this.complete = terminal;
            if (terminal && inputCount == 0) {
                executionCount = successCount = 0;
            }
        }

        private ExecutionFlowStage snapshot(Map<Long, ExecutionFlowContext> contexts) {
            return new ExecutionFlowStage(index, command, values(inputIds, contexts), values(outputIds, contexts),
                edges, droppedInputIds, inputCount, outputCount, droppedCount, terminal, executionCount, successCount, complete,
                lineageComplete, truncated, observationOrder, callStack);
        }

        private static List<ExecutionFlowContext> values(List<Long> ids,
                                                         Map<Long, ExecutionFlowContext> contexts) {
            List<ExecutionFlowContext> result = new ArrayList<>(ids.size());
            for (long id : ids) {
                ExecutionFlowContext context = contexts.get(id);
                if (context != null) result.add(context);
            }
            return result;
        }
    }
}
