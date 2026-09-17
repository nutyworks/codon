package works.nuty.bastion.core.service;

import org.jspecify.annotations.Nullable;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.ExecutionFlowContext;
import works.nuty.bastion.core.model.ExecutionFlowEdge;
import works.nuty.bastion.core.model.ExecutionFlowStage;
import works.nuty.bastion.core.model.ExecutionFlowTrace;
import works.nuty.bastion.core.model.PauseSource;
import works.nuty.bastion.core.model.SourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable, bounded recorder for one invocation. Its adapter caller supplies only relationships
 * observed while Minecraft iterates the actual modifier results.
 */
public final class ExecutionFlowRecorder {
    public static final int MAX_STAGES = 24;
    public static final int MAX_CONTEXTS = 128;
    public static final int MAX_EDGES = 256;

    private final long invocationId;
    private final SourceLocation location;
    private final List<MutableStage> stages = new ArrayList<>();
    private final Map<Long, ExecutionFlowContext> contexts = new LinkedHashMap<>();
    private long nextContextId = 1;
    private int edgeCount;
    private boolean truncated;
    private @Nullable MutableStage activeStage;

    ExecutionFlowRecorder(long invocationId, SourceLocation location) {
        this.invocationId = invocationId;
        this.location = location;
    }

    /** Creates a context occurrence. Zero is returned when only aggregate counts can be retained. */
    public synchronized long createContext(PauseSource source) {
        if (contexts.size() >= MAX_CONTEXTS) {
            truncated = true;
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
        truncated = true;
        if (activeStage != null) activeStage.truncated = true;
    }

    /** Starts a stage with occurrence ids that already correspond to its actual input list. */
    public synchronized boolean beginStage(CommandSnippet command, List<Long> inputIds,
                                           int inputCount, boolean terminal) {
        if (stages.size() >= MAX_STAGES) {
            truncated = true;
            activeStage = null;
            return false;
        }
        MutableStage stage = new MutableStage(stages.size(), command, retained(inputIds), inputCount, terminal);
        if (terminal) {
            stage.outputIds.addAll(stage.inputIds);
            stage.outputCount = inputCount;
        }
        stages.add(stage);
        activeStage = stage;
        return true;
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
                truncated = stage.truncated = true;
            }
        } else if (inputId != 0 || outputId != 0) {
            truncated = stage.truncated = true;
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
        activeStage = null;
    }

    /** Marks a custom/error boundary whose continuation relationship was not directly observed. */
    public synchronized void abandonStage() {
        MutableStage stage = activeStage;
        if (stage != null && !stage.terminal) {
            stage.complete = true;
            stage.lineageComplete = false;
            stage.truncated = true;
            truncated = true;
        }
        activeStage = null;
    }

    public synchronized void executionStarted() {
        MutableStage terminal = terminalStage();
        if (terminal != null) terminal.executionCount++;
    }

    public synchronized void executionResult(boolean success) {
        MutableStage terminal = terminalStage();
        if (terminal != null && success && terminal.successCount < terminal.executionCount) {
            terminal.successCount++;
        }
    }

    public synchronized ExecutionFlowTrace snapshot() {
        List<ExecutionFlowStage> result = new ArrayList<>(stages.size());
        for (MutableStage stage : stages) result.add(stage.snapshot(contexts));
        return new ExecutionFlowTrace(invocationId, location, result, truncated);
    }

    private @Nullable MutableStage terminalStage() {
        if (stages.isEmpty()) return null;
        MutableStage stage = stages.getLast();
        return stage.terminal ? stage : null;
    }

    private List<Long> retained(List<Long> ids) {
        List<Long> result = new ArrayList<>(Math.min(ids.size(), MAX_CONTEXTS));
        for (long id : ids) if (id != 0 && contexts.containsKey(id)) result.add(id);
        if (result.size() < ids.size()) truncated = true;
        return result;
    }

    private static final class MutableStage {
        private final int index;
        private final CommandSnippet command;
        private final List<Long> inputIds;
        private final List<Long> outputIds = new ArrayList<>();
        private final List<ExecutionFlowEdge> edges = new ArrayList<>();
        private final List<Long> droppedInputIds = new ArrayList<>();
        private final int inputCount;
        private final boolean terminal;
        private int outputCount;
        private int droppedCount;
        private int executionCount;
        private int successCount;
        private boolean complete;
        private boolean lineageComplete = true;
        private boolean truncated;

        private MutableStage(int index, CommandSnippet command, List<Long> inputIds,
                             int inputCount, boolean terminal) {
            this.index = index;
            this.command = command;
            this.inputIds = inputIds;
            this.inputCount = inputCount;
            this.terminal = terminal;
            this.complete = terminal;
        }

        private ExecutionFlowStage snapshot(Map<Long, ExecutionFlowContext> contexts) {
            return new ExecutionFlowStage(index, command, values(inputIds, contexts), values(outputIds, contexts),
                edges, droppedInputIds, inputCount, outputCount, droppedCount, terminal, executionCount, successCount, complete,
                lineageComplete, truncated);
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
