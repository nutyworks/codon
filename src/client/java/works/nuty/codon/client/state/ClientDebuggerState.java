package works.nuty.codon.client.state;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.ExecutionFlowContext;
import works.nuty.codon.core.model.ExecutionFlowEdge;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;

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
    private List<CallFrame> displayedCallStack = List.of();
    private int selectedCallFrameIndex = -1;
    private int selectedFlowIndex = -1;
    private int selectedFlowStageIndex = -1;
    private boolean controlPending;
    private long controlRequestedAt;
    private final LongSupplier clock;
    private final ClientWatchState watches;
    private final ClientWatchEditorState watchEditor;
    private final ClientNbtState nbt;
    private final ClientBreakpointState breakpoints;
    private final ClientStagePreviewState stagePreviews;
    private @Nullable PauseSource selectionHint;
    private @Nullable FlowSelectionHint flowSelectionHint;
    private @Nullable PauseSnapshot timelineSnapshot;
    private List<ExecutionFlowTimeline.Visit> executionVisits = List.of();
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
        this.watchEditor = new ClientWatchEditorState(clock);
        this.nbt = new ClientNbtState(clock);
        this.breakpoints = new ClientBreakpointState(clock);
        this.stagePreviews = new ClientStagePreviewState();
        this.nbt.setEnabled(preferences.nbtExpanded());
        this.nbt.setEnabledListener(preferences::setNbtExpanded);
    }

    public enum GizmoMode {
        LABELS, GROUPED;

        public GizmoMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public void applyPause(PauseSnapshot snapshot) {
        watchEditor.invalidate();
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
        this.displayedCallStack = snapshot.callStack();
        this.selectedCallFrameIndex = displayedCallStack.isEmpty() ? -1 : 0;
        if (!snapshot.callStack().isEmpty() && snapshot.callStack().getFirst().invocationId() >= 0) {
            selectFrameFlow(snapshot.callStack().getFirst());
        } else {
            // Older fixtures/snapshots have no frame linkage. Never infer it from command text.
            selectLatestFlow(snapshot);
            if (selectedFlowStageIndex >= 0) selectedFrameIndex = -1;
            followSelectedCallStack();
        }
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
    public ClientWatchEditorState watchEditor() { return watchEditor; }
    public ClientNbtState nbt() { return nbt; }

    public void applyResume() {
        watches.resumed();
        nbt.resumed();
        clearPause(false);
    }

    private void clearPause(boolean stepping) {
        if (stepping) watchEditor.invalidate();
        else watchEditor.cancel();
        if (selectedSource() != null) selectionHint = selectedSource();
        FlowSelectionHint hint = currentFlowHint();
        if (hint != null) flowSelectionHint = hint;
        this.paused = false;
        this.stepping = false;
        this.continuing = false;
        this.snapshot = null;
        this.selectedSourceIndex = -1;
        this.selectedFrameIndex = 0;
        this.displayedCallStack = recentFlowSnapshot == null ? List.of() : recentFlowSnapshot.callStack();
        this.selectedCallFrameIndex = displayedCallStack.isEmpty() ? -1 : 0;
        this.selectedFlowIndex = -1;
        this.selectedFlowStageIndex = -1;
        this.controlPending = false;
    }

    /** Server-confirmed advancement: discard the old pause but retain the freecam session. */
    public void applyStep() {
        watches.stepping();
        nbt.stepping();
        clearPause(true);
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
        if (paused) return;

        FlowSelectionHint previousFlow = flowSelectionHint;
        selectLatestFlow(completed);
        selectedFrameIndex = frameForSelectedFlow();
        followSelectedCallStack();
        selectedSourceIndex = displayedSources().isEmpty() ? -1 : 0;
        if (previousFlow != null) restoreFlow(previousFlow, completed);
        controlPending = false;
    }

    public List<BlockLocation> blockBreakpoints() {
        return blockBreakpoints;
    }

    public ClientBreakpointState breakpoints() { return breakpoints; }
    public ClientStagePreviewState stagePreviews() { return stagePreviews; }

    public int selectedSourceIndex() {
        return selectedSourceIndex;
    }

    /** Index in the live pause packet, or -1 for a historical flow-only context. */
    public int selectedPauseSourceIndex() {
        PauseSnapshot current = snapshot;
        PauseSource selected = selectedSource();
        if (current == null || selected == null || selectedFrameIndex > 0 || selectedCallFrameIndex > 0
                || (selectedFrameIndex < 0 && !current.callStack().isEmpty())) return -1;
        CallFrame top = current.callStack().isEmpty() ? null : current.callStack().getFirst();
        if (top != null && top.invocationId() >= 0 && selectedExecutionFlowStage() != null) {
            if (isViewingCurrentCommand() && sourceStage() == null) {
                return selectedSourceIndex >= 0 && selectedSourceIndex < current.pauseSources().size() ? selectedSourceIndex : -1;
            }
            ExecutionFlowTrace flow = selectedExecutionFlow();
            ExecutionFlowContext context = selectedFlowContext();
            int pausedStage = pausedFlowStageIndex();
            if (flow == null || flow.invocationId() != top.invocationId() || context == null || pausedStage < 0) return -1;
            ExecutionFlowStage stage = current.executionFlows().get(pausedFlowIndex()).stages().get(pausedStage);
            // IDs identify actual occurrences; equal entity/source values in old stages are not live.
            if (stage.inputCount() != current.pauseSources().size()) return -1;
            for (int i = 0; i < stage.inputs().size() && i < current.pauseSources().size(); i++) {
                if (stage.inputs().get(i).id() == context.id()) return i;
            }
            return -1;
        }
        return current.pauseSources().indexOf(selected);
    }

    /** Sources currently connected to the inspector and world markers. */
    public List<PauseSource> displayedSources() {
        ExecutionFlowStage stage = sourceStage();
        if (stage != null) {
            List<PauseSource> result = new ArrayList<>();
            for (ExecutionFlowContext context : stage.displayContexts()) result.add(context.source());
            return List.copyOf(result);
        }
        PauseSnapshot current = inspectionSnapshot();
        if (selectedFrameIndex > 0 || (selectedFrameIndex < 0 && current != null && !current.callStack().isEmpty())) return List.of();
        return current == null ? List.of() : current.pauseSources();
    }

    public List<ExecutionFlowContext> displayedFlowContexts() {
        ExecutionFlowStage stage = sourceStage();
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
        ExecutionFlowStage stage = sourceStage();
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
        ExecutionFlowStage stage = sourceStage();
        ExecutionFlowContext selected = selectedFlowContext();
        return stage != null && selected != null && stage.isDroppedContext(selected.id());
    }

    public boolean isDisplayedSourceDropped(int index) {
        ExecutionFlowStage stage = sourceStage();
        List<ExecutionFlowContext> contexts = displayedFlowContexts();
        return stage != null && index >= 0 && index < contexts.size()
            && stage.isDroppedContext(contexts.get(index).id());
    }

    public boolean selectedSourceCreated() {
        return isDisplayedSourceCreated(selectedSourceIndex);
    }

    public boolean isDisplayedSourceCreated(int index) {
        ExecutionFlowStage stage = sourceStage();
        List<ExecutionFlowContext> contexts = displayedFlowContexts();
        return stage != null && index >= 0 && index < contexts.size()
            && stage.isCreatedContext(contexts.get(index).id());
    }

    /**
     * Pauses precede execution. For an unexecuted next stage, show the transition which produced
     * its inputs, including sources just excluded by that transition. Both source views use
     * this transition while command selection remains on the actual current stage.
     */
    public @Nullable ExecutionFlowStage worldSourceStage() {
        if (!paused || snapshot == null) return null;
        ExecutionFlowStage current = selectedExecutionFlowStage();
        ExecutionFlowTrace flow = selectedExecutionFlow();
        if (current == null || flow == null) return null;
        if (isViewingCurrentCommand() && !completePauseInputs(current, snapshot)) return null;
        if ((!current.complete() || current.terminal()) && selectedFlowStageIndex > 0) {
            ExecutionFlowStage previous = flow.stages().get(selectedFlowStageIndex - 1);
            if (!previous.terminal() && previous.complete() && previous.lineageComplete()
                && previous.outputs().size() == previous.outputCount() && current.inputs().size() == current.inputCount()
                && previous.outputCount() == current.inputCount() && previous.outputs().equals(current.inputs())) {
                return previous;
            }
        }
        return current;
    }

    private static boolean completePauseInputs(ExecutionFlowStage stage, PauseSnapshot pause) {
        return stage.inputCount() == pause.pauseSources().size() && stage.inputs().size() == stage.inputCount()
            && stage.inputs().stream().map(ExecutionFlowContext::source).toList().equals(pause.pauseSources());
    }

    private @Nullable ExecutionFlowStage sourceStage() {
        return paused && snapshot != null ? worldSourceStage() : selectedExecutionFlowStage();
    }

    public List<PauseSource> worldSources() {
        if (!paused || snapshot == null) return List.of();
        ExecutionFlowStage stage = worldSourceStage();
        return stage == null ? displayedSources()
            : stage.displayContexts().stream().map(ExecutionFlowContext::source).toList();
    }

    public int selectedWorldSourceIndex() {
        if (!paused || snapshot == null) return -1;
        ExecutionFlowStage stage = worldSourceStage();
        if (stage == null) return selectedSourceIndex;
        ExecutionFlowContext selected = selectedFlowContext();
        if (selected == null) return -1;
        List<ExecutionFlowContext> contexts = stage.displayContexts();
        for (int index = 0; index < contexts.size(); index++) {
            if (contexts.get(index).id() == selected.id()) return index;
        }
        return -1;
    }

    public boolean isWorldSourceCreated(int index) {
        ExecutionFlowStage stage = worldSourceStage();
        if (stage == null) return false;
        List<ExecutionFlowContext> contexts = stage.displayContexts();
        return index >= 0 && index < contexts.size() && stage.isCreatedContext(contexts.get(index).id());
    }

    public boolean isWorldSourceDropped(int index) {
        ExecutionFlowStage stage = worldSourceStage();
        if (stage == null) return false;
        List<ExecutionFlowContext> contexts = stage.displayContexts();
        return index >= 0 && index < contexts.size() && stage.isDroppedContext(contexts.get(index).id());
    }

    public void selectWorldSource(int index) {
        if (index < 0 || index >= worldSources().size()) return;
        ExecutionFlowStage stage = worldSourceStage();
        ExecutionFlowTrace flow = selectedExecutionFlow();
        if (stage != null && flow != null && stage != selectedExecutionFlowStage()) {
            selectExecutionFlowStage(flow.stages().indexOf(stage));
        }
        selectSource(index);
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

    /** Frames observed with the selected recording, kept stable while inspecting its callers. */
    public List<CallFrame> displayedCallStack() {
        return displayedCallStack;
    }

    public int selectedCallFrameIndex() {
        return selectedCallFrameIndex;
    }

    private @Nullable CallFrame selectedCallFrame() {
        return selectedCallFrameIndex >= 0 && selectedCallFrameIndex < displayedCallStack.size()
            ? displayedCallStack.get(selectedCallFrameIndex) : null;
    }

    /** Selecting a recorded caller does not replace that recording's stack with the live stack. */
    public void selectCallFrame(int index) {
        if (index < 0 || index >= displayedCallStack.size()) return;
        selectCallFrame(displayedCallStack, index);
    }

    private void selectCallFrame(List<CallFrame> frames, int index) {
        PauseSource previous = selectedSource() != null ? selectedSource() : selectionHint;
        if (selectedPauseSourceIndex() >= 0) selectionHint = selectedSource();
        displayedCallStack = frames;
        selectedCallFrameIndex = index;
        CallFrame frame = frames.get(index);
        selectedFrameIndex = liveFrameIndex(frame);
        selectFrameFlow(frame);
        List<PauseSource> sources = displayedSources();
        int exact = previous == null ? -1 : sources.indexOf(previous);
        selectedSourceIndex = exact >= 0 ? exact : sources.isEmpty() ? -1 : 0;
        syncLiveSourceSelection();
    }

    private int liveFrameIndex(CallFrame frame) {
        PauseSnapshot current = inspectionSnapshot();
        if (current == null) return -1;
        for (int i = 0; i < current.callStack().size(); i++) {
            CallFrame live = current.callStack().get(i);
            if (frame.invocationId() >= 0 ? live.invocationId() == frame.invocationId() : live.equals(frame)) return i;
        }
        return -1;
    }

    /** Only the authoritative stopped invocation and stage receive a pause marker. */
    public boolean isPausedCallFrame(CallFrame frame) {
        if (!paused || snapshot == null || snapshot.callStack().isEmpty()) return false;
        CallFrame top = snapshot.callStack().getFirst();
        return top.invocationId() >= 0 && top.flowStageIndex() >= 0 && frame.flowStageIndex() >= 0
            ? top.invocationId() == frame.invocationId() && top.flowStageIndex() == frame.flowStageIndex()
            : top == frame;
    }

    private void followSelectedCallStack() {
        ExecutionFlowStage stage = selectedExecutionFlowStage();
        if (stage != null && !stage.callStack().isEmpty()) {
            displayedCallStack = stage.callStack();
            selectedCallFrameIndex = 0;
            return;
        }
        // Legacy live frames can still be inspected. Never attach an unrelated live stack to history.
        PauseSnapshot current = inspectionSnapshot();
        boolean matchingLiveFrame = selectedFrameIndex >= 0 && current != null
            && selectedFrameIndex < current.callStack().size()
            && (stage == null || current.callStack().get(selectedFrameIndex).flowStageIndex() == stage.index());
        displayedCallStack = matchingLiveFrame ? current.callStack() : List.of();
        selectedCallFrameIndex = selectedFrameIndex >= 0 && selectedFrameIndex < displayedCallStack.size()
            ? selectedFrameIndex : -1;
    }

    public void selectFrame(int index) {
        PauseSnapshot current = inspectionSnapshot();
        boolean currentWithoutStack = index == 0 && current != null && current.callStack().isEmpty();
        if (current == null || (!currentWithoutStack
            && (index < 0 || index >= current.callStack().size()))) return;
        if (!currentWithoutStack) {
            selectCallFrame(current.callStack(), index);
            return;
        }

        PauseSource previous = selectedSource() != null ? selectedSource() : selectionHint;
        if (selectedPauseSourceIndex() >= 0) selectionHint = selectedSource();
        selectedFrameIndex = index;
        displayedCallStack = List.of();
        selectedCallFrameIndex = -1;
        selectedFlowIndex = selectedFlowStageIndex = -1;
        List<PauseSource> sources = displayedSources();
        int exact = previous == null ? -1 : sources.indexOf(previous);
        selectedSourceIndex = exact >= 0 ? exact : sources.isEmpty() ? -1 : 0;
        syncLiveSourceSelection();
    }

    /** Selects only explicitly linked stages; an evicted/truncated recording stays unavailable. */
    private void selectFrameFlow(CallFrame frame) {
        selectedFlowIndex = flowIndex(frame.invocationId());
        selectedFlowStageIndex = stageIndex(selectedFlowIndex, frame.flowStageIndex());
    }

    public void selectCurrentCommand() {
        selectFrame(0);
    }

    public int pausedFlowIndex() {
        PauseSnapshot current = snapshot;
        return current == null || current.callStack().isEmpty() ? -1
            : flowIndex(current.callStack().getFirst().invocationId());
    }

    public int pausedFlowStageIndex() {
        PauseSnapshot current = snapshot;
        return current == null || current.callStack().isEmpty() ? -1
            : stageIndex(pausedFlowIndex(), current.callStack().getFirst().flowStageIndex());
    }

    public boolean isViewingCurrentCommand() {
        return paused && snapshot != null && selectedFrameIndex == 0 && selectedCallFrameIndex <= 0
            && selectedFlowStageIndex == pausedFlowStageIndex();
    }

    public @Nullable CommandSnippet selectedCommand() {
        ExecutionFlowStage stage = selectedExecutionFlowStage();
        if (stage != null) return stage.command();
        PauseSnapshot current = inspectionSnapshot();
        if (current == null) return null;
        CallFrame frame = selectedCallFrame();
        return frame != null ? frame.command() : current.command();
    }

    public @Nullable SourceLocation selectedLocation() {
        ExecutionFlowTrace flow = selectedExecutionFlow();
        if (selectedExecutionFlowStage() != null && flow != null) return flow.location();
        PauseSnapshot current = inspectionSnapshot();
        if (current == null) return null;
        CallFrame frame = selectedCallFrame();
        return frame != null ? frame.location() : current.location();
    }

    private int flowIndex(long invocationId) {
        PauseSnapshot current = inspectionSnapshot();
        if (invocationId < 0 || current == null) return -1;
        for (int i = 0; i < current.executionFlows().size(); i++) {
            if (current.executionFlows().get(i).invocationId() == invocationId) return i;
        }
        return -1;
    }

    private int stageIndex(int flowIndex, int recordedIndex) {
        PauseSnapshot current = inspectionSnapshot();
        if (current == null || flowIndex < 0 || recordedIndex < 0) return -1;
        List<ExecutionFlowStage> stages = current.executionFlows().get(flowIndex).stages();
        for (int i = 0; i < stages.size(); i++) if (stages.get(i).index() == recordedIndex) return i;
        return -1;
    }

    private int frameForSelectedFlow() {
        PauseSnapshot current = inspectionSnapshot();
        ExecutionFlowTrace flow = selectedExecutionFlow();
        if (current == null || flow == null) return -1;
        for (int i = 0; i < current.callStack().size(); i++) {
            if (current.callStack().get(i).invocationId() == flow.invocationId()) return i;
        }
        return -1;
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
        selectedFlowStageIndex = latestCompletedStage(flow);
        selectedFrameIndex = frameForSelectedFlow();
        followSelectedCallStack();
        selectedSourceIndex = displayedSources().isEmpty() ? -1 : 0;
        syncLiveSourceSelection();
    }

    public void selectExecutionFlowStage(int stageIndex) {
        ExecutionFlowTrace flow = selectedExecutionFlow();
        if (flow == null || stageIndex < 0 || stageIndex >= flow.stages().size()) return;
        selectedFlowStageIndex = stageIndex;
        selectedFrameIndex = frameForSelectedFlow();
        followSelectedCallStack();
        selectedSourceIndex = displayedSources().isEmpty() ? -1 : 0;
        syncLiveSourceSelection();
    }

    public boolean hasAdjacentExecutionVisit(int direction) {
        return adjacentExecutionVisit(direction) != null;
    }

    /** Read-only history navigation: a caller may appear again after a condition function returns. */
    public void selectAdjacentExecutionVisit(int direction) {
        ExecutionFlowTimeline.Visit visit = adjacentExecutionVisit(direction);
        if (visit == null) return;
        selectedFlowIndex = visit.flowIndex();
        selectedFlowStageIndex = direction > 0 ? visit.stageIndices().getFirst() : visit.stageIndices().getLast();
        selectedFrameIndex = frameForSelectedFlow();
        followSelectedCallStack();
        selectedSourceIndex = displayedSources().isEmpty() ? -1 : 0;
        syncLiveSourceSelection();
    }

    private ExecutionFlowTimeline.@Nullable Visit adjacentExecutionVisit(int direction) {
        if (direction != -1 && direction != 1) return null;
        PauseSnapshot current = inspectionSnapshot();
        if (current != timelineSnapshot) {
            timelineSnapshot = current;
            executionVisits = current == null ? List.of() : ExecutionFlowTimeline.visits(current.executionFlows());
        }
        int selectedVisit = -1;
        for (int i = 0; i < executionVisits.size(); i++) {
            ExecutionFlowTimeline.Visit visit = executionVisits.get(i);
            if (visit.flowIndex() == selectedFlowIndex && visit.stageIndices().contains(selectedFlowStageIndex)) {
                selectedVisit = i;
                break;
            }
        }
        int target = selectedVisit < 0 ? direction > 0 ? 0 : executionVisits.size() - 1 : selectedVisit + direction;
        return target >= 0 && target < executionVisits.size() ? executionVisits.get(target) : null;
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

    /** Delay only the waiting label; controlPending() still disables actions immediately. */
    public boolean displayControlWaiting() {
        return controlPending() && clock.getAsLong() - controlRequestedAt >= PendingDisplay.GRACE_NANOS;
    }

    public void reset() {
        applyResume();
        recentFlowSnapshot = null;
        displayedCallStack = List.of();
        selectedCallFrameIndex = -1;
        timelineSnapshot = null;
        executionVisits = List.of();
        selectionHint = null;
        flowSelectionHint = null;
        blockBreakpoints = List.of();
        breakpoints.reset();
        stagePreviews.reset();
        watches.reset();
        watchEditor.reset();
        nbt.reset();
    }

    private static int latestCompletedStage(ExecutionFlowTrace flow) {
        for (int index = flow.stages().size() - 1; index >= 0; index--) {
            if (flow.stages().get(index).complete()) return index;
        }
        return flow.stages().isEmpty() ? -1 : 0;
    }

    private void selectLatestFlow(PauseSnapshot snapshot) {
        selectedFlowIndex = -1;
        selectedFlowStageIndex = -1;
        long latestOrder = -1;
        for (int flowIndex = 0; flowIndex < snapshot.executionFlows().size(); flowIndex++) {
            List<ExecutionFlowStage> stages = snapshot.executionFlows().get(flowIndex).stages();
            for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
                if (stages.get(stageIndex).observationOrder() > latestOrder) {
                    latestOrder = stages.get(stageIndex).observationOrder();
                    selectedFlowIndex = flowIndex;
                    selectedFlowStageIndex = stageIndex;
                }
            }
        }
        if (latestOrder >= 0) return;
        for (int i = snapshot.executionFlows().size() - 1; i >= 0; i--) {
            ExecutionFlowTrace flow = snapshot.executionFlows().get(i);
            if (!flow.stages().isEmpty()) {
                selectedFlowIndex = i;
                selectedFlowStageIndex = latestCompletedStage(flow);
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
                        selectedFrameIndex = frameForSelectedFlow();
                        followSelectedCallStack();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private @Nullable FlowSelectionHint currentFlowHint() {
        ExecutionFlowTrace flow = selectedExecutionFlow();
        ExecutionFlowStage stage = sourceStage();
        ExecutionFlowContext context = selectedFlowContext();
        return flow == null || stage == null || context == null ? null
            : new FlowSelectionHint(flow.invocationId(), stage.index(), context.id());
    }

    private record FlowSelectionHint(long invocationId, int stageIndex, long contextId) {
    }
}
