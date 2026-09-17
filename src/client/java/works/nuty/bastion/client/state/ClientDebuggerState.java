package works.nuty.bastion.client.state;

import org.jspecify.annotations.Nullable;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.ExecutionFlowContext;
import works.nuty.bastion.core.model.ExecutionFlowEdge;
import works.nuty.bastion.core.model.ExecutionFlowStage;
import works.nuty.bastion.core.model.ExecutionFlowTrace;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.PauseSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Authoritative client-side mirror of server debugger packets. Flow selection uses invocation,
 * stage, and occurrence ids; entity UUIDs are presentation data and never establish lineage.
 */
public final class ClientDebuggerState {
    private volatile boolean paused;
    private volatile boolean stepping;
    private volatile boolean continuing;
    private volatile @Nullable PauseSnapshot snapshot;
    private volatile @Nullable PauseSnapshot recentFlowSnapshot;
    private volatile List<BlockLocation> blockBreakpoints = List.of();
    private int selectedSourceIndex = -1;
    private int selectedFrameIndex;
    private int selectedFlowIndex = -1;
    private int selectedFlowStageIndex = -1;
    private boolean controlPending;
    private long controlRequestedAt;
    private final LongSupplier clock;
    private final ClientWatchState watches;
    private final ClientNbtState nbt;
    private @Nullable PauseSource selectionHint;
    private @Nullable FlowSelectionHint flowSelectionHint;
    private final DebuggerPreferences preferences;

    public ClientDebuggerState() {
        this(System::nanoTime, new DebuggerPreferences());
    }

    /** Injectable monotonic clock keeps acknowledgement timeouts deterministic in unit tests. */
    public ClientDebuggerState(LongSupplier clock) {
        this(clock, new DebuggerPreferences());
    }

    public ClientDebuggerState(DebuggerPreferences preferences) {
        this(System::nanoTime, preferences);
    }

    public ClientDebuggerState(LongSupplier clock, DebuggerPreferences preferences) {
        this.clock = Objects.requireNonNull(clock);
        this.preferences = Objects.requireNonNull(preferences);
        this.watches = new ClientWatchState(clock);
        this.nbt = new ClientNbtState(clock);
        this.nbt.setEnabled(preferences.nbtExpanded());
        this.nbt.setEnabledListener(preferences::setNbtExpanded);
    }

    public enum GizmoMode {
        LABELS, FOCUS, GROUPED;

        public GizmoMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public void applyPause(PauseSnapshot snapshot) {
        FlowSelectionHint previousFlow = currentFlowHint();
        if (previousFlow == null) previousFlow = flowSelectionHint;
        PauseSource previous = selectedSource() != null ? selectedSource() : selectionHint;
        selectionHint = null;
        flowSelectionHint = null;
        this.snapshot = snapshot;
        this.recentFlowSnapshot = snapshot;
        this.paused = true;
        this.stepping = false;
        this.continuing = false;
        this.controlPending = false;
        this.selectedFrameIndex = 0;
        selectLatestFlow(snapshot);
        if (selectedFlowStageIndex >= 0) selectedFrameIndex = -1;
        this.selectedSourceIndex = displayedSources().isEmpty() ? -1 : 0;

        ExecutionFlowTrace latestFlow = selectedExecutionFlow();
        ExecutionFlowStage latestStage = selectedExecutionFlowStage();
        boolean restoredFlow = previousFlow != null && latestFlow != null && latestStage != null
            && previousFlow.invocationId() == latestFlow.invocationId()
            && previousFlow.stageIndex() == latestStage.index()
            && restoreFlow(previousFlow, snapshot);
        // Legacy snapshots have no occurrence ids. Preserve only an exact source value, never an
        // entity/dimension guess which could silently select a different execution context.
        if (!restoredFlow && selectedFlowIndex < 0 && previous != null) {
            int exact = snapshot.pauseSources().indexOf(previous);
            if (exact >= 0) selectedSourceIndex = exact;
        }
        int liveSourceIndex = selectedPauseSourceIndex();
        watches.paused(snapshot.pauseId(), liveSourceIndex);
        watches.rememberExecutors(snapshot.pauseSources());
        nbt.paused(snapshot.pauseId(), snapshot.pauseSources(), liveSourceIndex);
    }

    public ClientWatchState watches() { return watches; }
    public ClientNbtState nbt() { return nbt; }

    public void applyResume() {
        watches.resumed();
        nbt.resumed();
        clearPause();
    }

    private void clearPause() {
        if (selectedSource() != null) selectionHint = selectedSource();
        FlowSelectionHint hint = currentFlowHint();
        if (hint != null) flowSelectionHint = hint;
        this.paused = false;
        this.stepping = false;
        this.continuing = false;
        this.snapshot = null;
        this.selectedSourceIndex = -1;
        this.selectedFrameIndex = 0;
        this.selectedFlowIndex = -1;
        this.selectedFlowStageIndex = -1;
        this.controlPending = false;
    }

    /** Server-confirmed advancement: discard the old pause but retain the freecam session. */
    public void applyStep() {
        watches.stepping();
        nbt.stepping();
        clearPause();
        this.stepping = true;
    }

    public boolean isStepping() {
        return stepping;
    }

    /** Continue clears inspection data like Resume, but the current execution still owns freecam. */
    public void applyContinue() {
        applyResume();
        this.continuing = true;
    }

    public boolean isContinuing() {
        return continuing;
    }

    public void applyBreakpoints(List<BlockLocation> blocks) {
        this.blockBreakpoints = List.copyOf(blocks);
    }

    public boolean isPaused() {
        return paused;
    }

    public @Nullable PauseSnapshot snapshot() {
        return snapshot;
    }

    /** Current pause, or the last completed flow retained for read-only menu inspection. */
    public @Nullable PauseSnapshot inspectionSnapshot() {
        PauseSnapshot current = snapshot;
        return current != null ? current : recentFlowSnapshot;
    }

    /** Updates read-only completion data without changing pause, stepping, freecam, or controls. */
    public void applyCompletedExecutionFlows(List<ExecutionFlowTrace> flows) {
        PauseSnapshot previous = recentFlowSnapshot;
        if (previous == null || flows.isEmpty()) return;
        PauseSnapshot completed = new PauseSnapshot(previous.location(), previous.command(), previous.depth(),
            previous.callStack(), previous.pauseSources(), flows, previous.reason(), previous.pauseId());
        recentFlowSnapshot = completed;

        FlowSelectionHint previousFlow = flowSelectionHint;
        selectLatestFlow(completed);
        selectedFrameIndex = selectedFlowStageIndex >= 0 ? -1 : 0;
        selectedSourceIndex = displayedSources().isEmpty() ? -1 : 0;
        if (previousFlow != null) restoreFlow(previousFlow, completed);
        controlPending = false;
    }

    public List<BlockLocation> blockBreakpoints() {
        return blockBreakpoints;
    }

    public int selectedSourceIndex() {
        return selectedSourceIndex;
    }

    /** Index in the live pause packet, or -1 for a historical flow-only context. */
    public int selectedPauseSourceIndex() {
        PauseSnapshot current = snapshot;
        PauseSource selected = selectedSource();
        return current == null || selected == null ? -1 : current.pauseSources().indexOf(selected);
    }

    /** Sources currently connected to the inspector and world markers. */
    public List<PauseSource> displayedSources() {
        ExecutionFlowStage stage = selectedExecutionFlowStage();
        if (stage != null) {
            List<PauseSource> result = new ArrayList<>();
            for (ExecutionFlowContext context : stage.displayContexts()) result.add(context.source());
            return List.copyOf(result);
        }
        PauseSnapshot current = inspectionSnapshot();
        return current == null ? List.of() : current.pauseSources();
    }

    public List<ExecutionFlowContext> displayedFlowContexts() {
        ExecutionFlowStage stage = selectedExecutionFlowStage();
        return stage == null ? List.of() : stage.displayContexts();
    }

    public @Nullable PauseSource selectedSource() {
        List<PauseSource> sources = displayedSources();
        return selectedSourceIndex >= 0 && selectedSourceIndex < sources.size()
            ? sources.get(selectedSourceIndex) : null;
    }

    public @Nullable ExecutionFlowContext selectedFlowContext() {
        List<ExecutionFlowContext> contexts = displayedFlowContexts();
        return selectedSourceIndex >= 0 && selectedSourceIndex < contexts.size()
            ? contexts.get(selectedSourceIndex) : null;
    }

    public @Nullable ExecutionFlowContext selectedFlowParent() {
        ExecutionFlowStage stage = selectedExecutionFlowStage();
        ExecutionFlowContext selected = selectedFlowContext();
        if (stage == null || selected == null) return null;
        long inputId = 0;
        for (ExecutionFlowEdge edge : stage.edges()) {
            if (edge.outputContextId() == selected.id()) {
                inputId = edge.inputContextId();
                break;
            }
        }
        if (inputId == 0) return null;
        for (ExecutionFlowContext input : stage.inputs()) {
            if (input.id() == inputId) return input;
        }
        return null;
    }

    public boolean selectedSourceDropped() {
        ExecutionFlowStage stage = selectedExecutionFlowStage();
        ExecutionFlowContext selected = selectedFlowContext();
        return stage != null && selected != null && stage.isDroppedContext(selected.id());
    }

    public boolean isDisplayedSourceDropped(int index) {
        ExecutionFlowStage stage = selectedExecutionFlowStage();
        List<ExecutionFlowContext> contexts = displayedFlowContexts();
        return stage != null && index >= 0 && index < contexts.size()
            && stage.isDroppedContext(contexts.get(index).id());
    }

    public void selectSource(int index) {
        List<PauseSource> sources = displayedSources();
        if (inspectionSnapshot() == null || index < 0 || index >= sources.size()) return;
        selectedSourceIndex = index;
        syncLiveSourceSelection();
    }

    /** Prevent Watch/NBT reads from inheriting an unrelated executor after flow navigation. */
    private void syncLiveSourceSelection() {
        if (!paused || snapshot == null) return;
        int liveIndex = selectedPauseSourceIndex();
        watches.selectSource(liveIndex);
        nbt.selectSource(liveIndex);
    }

    public int selectedFrameIndex() {
        return selectedFrameIndex;
    }

    public void selectFrame(int index) {
        PauseSnapshot current = inspectionSnapshot();
        boolean currentWithoutStack = index == 0 && current != null && current.callStack().isEmpty();
        if (current == null || (!currentWithoutStack
            && (index < 0 || index >= current.callStack().size()))) return;

        PauseSource previous = selectedSource();
        selectedFrameIndex = index;
        selectedFlowStageIndex = -1;
        int exact = previous == null ? -1 : current.pauseSources().indexOf(previous);
        selectedSourceIndex = exact >= 0 ? exact : current.pauseSources().isEmpty() ? -1 : 0;
        syncLiveSourceSelection();
    }

    public int selectedFlowIndex() {
        return selectedFlowIndex;
    }

    public int selectedFlowStageIndex() {
        return selectedFlowStageIndex;
    }

    public @Nullable ExecutionFlowTrace selectedExecutionFlow() {
        PauseSnapshot current = inspectionSnapshot();
        return current != null && selectedFlowIndex >= 0 && selectedFlowIndex < current.executionFlows().size()
            ? current.executionFlows().get(selectedFlowIndex) : null;
    }

    public @Nullable ExecutionFlowStage selectedExecutionFlowStage() {
        ExecutionFlowTrace flow = selectedExecutionFlow();
        return flow != null && selectedFlowStageIndex >= 0 && selectedFlowStageIndex < flow.stages().size()
            ? flow.stages().get(selectedFlowStageIndex) : null;
    }

    public void selectExecutionFlow(int flowIndex) {
        PauseSnapshot current = inspectionSnapshot();
        if (current == null || flowIndex < 0 || flowIndex >= current.executionFlows().size()) return;
        ExecutionFlowTrace flow = current.executionFlows().get(flowIndex);
        if (flow.stages().isEmpty()) return;
        selectedFlowIndex = flowIndex;
        selectedFlowStageIndex = flow.stages().size() - 1;
        selectedSourceIndex = displayedSources().isEmpty() ? -1 : 0;
        selectedFrameIndex = -1;
        syncLiveSourceSelection();
    }

    public void selectExecutionFlowStage(int stageIndex) {
        ExecutionFlowTrace flow = selectedExecutionFlow();
        if (flow == null || stageIndex < 0 || stageIndex >= flow.stages().size()) return;
        selectedFlowStageIndex = stageIndex;
        selectedSourceIndex = displayedSources().isEmpty() ? -1 : 0;
        selectedFrameIndex = -1;
        syncLiveSourceSelection();
    }

    public GizmoMode gizmoMode() {
        return preferences.gizmoMode();
    }

    public void setGizmoMode(GizmoMode mode) {
        preferences.setGizmoMode(mode);
    }

    public DebuggerPreferences preferences() {
        return preferences;
    }

    /** Only a fresh server packet completes a control request; UI never fabricates a pause. */
    public boolean beginControlRequest() {
        if (!paused || snapshot == null || controlPending()) return false;
        controlPending = true;
        controlRequestedAt = clock.getAsLong();
        return true;
    }

    public boolean controlPending() {
        if (controlPending && clock.getAsLong() - controlRequestedAt >= 2_000_000_000L) controlPending = false;
        return controlPending;
    }

    public void reset() {
        applyResume();
        recentFlowSnapshot = null;
        selectionHint = null;
        flowSelectionHint = null;
        blockBreakpoints = List.of();
        watches.reset();
        nbt.reset();
    }

    private void selectLatestFlow(PauseSnapshot snapshot) {
        selectedFlowIndex = -1;
        selectedFlowStageIndex = -1;
        for (int i = snapshot.executionFlows().size() - 1; i >= 0; i--) {
            ExecutionFlowTrace flow = snapshot.executionFlows().get(i);
            if (!flow.stages().isEmpty()) {
                selectedFlowIndex = i;
                selectedFlowStageIndex = flow.stages().size() - 1;
                return;
            }
        }
    }

    private boolean restoreFlow(FlowSelectionHint hint, PauseSnapshot snapshot) {
        for (int flowIndex = 0; flowIndex < snapshot.executionFlows().size(); flowIndex++) {
            ExecutionFlowTrace flow = snapshot.executionFlows().get(flowIndex);
            if (flow.invocationId() != hint.invocationId()) continue;
            for (int stageIndex = 0; stageIndex < flow.stages().size(); stageIndex++) {
                ExecutionFlowStage stage = flow.stages().get(stageIndex);
                if (stage.index() != hint.stageIndex()) continue;
                List<ExecutionFlowContext> contexts = stage.displayContexts();
                for (int sourceIndex = 0; sourceIndex < contexts.size(); sourceIndex++) {
                    if (contexts.get(sourceIndex).id() == hint.contextId()) {
                        selectedFlowIndex = flowIndex;
                        selectedFlowStageIndex = stageIndex;
                        selectedSourceIndex = sourceIndex;
                        selectedFrameIndex = -1;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private @Nullable FlowSelectionHint currentFlowHint() {
        ExecutionFlowTrace flow = selectedExecutionFlow();
        ExecutionFlowStage stage = selectedExecutionFlowStage();
        ExecutionFlowContext context = selectedFlowContext();
        return flow == null || stage == null || context == null ? null
            : new FlowSelectionHint(flow.invocationId(), stage.index(), context.id());
    }

    private record FlowSelectionHint(long invocationId, int stageIndex, long contextId) {
    }
}
