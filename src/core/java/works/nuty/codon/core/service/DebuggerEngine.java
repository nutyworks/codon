package works.nuty.codon.core.service;

import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.port.DebuggerEventSink;
import works.nuty.codon.core.port.ExecutionController;

import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The heart of the debugger: a Minecraft-free state machine that decides when to pause, drives
 * the call stack, and coordinates pause/resume/stepping. It does no blocking or I/O itself — it
 * delegates suspension to {@link ExecutionController} and state publication to
 * {@link DebuggerEventSink}, both supplied by adapters at the composition root.
 *
 * <p>Threading: {@link #onCommandStage} and {@link #pause} run on the command-execution thread;
 * {@link #resume()}/{@code step*()} are invoked (from command or packet handlers) while that
 * thread is parked, and {@link #onTickBoundary()} between drains at the end of a host tick.
 * {@link #paused} is {@code volatile} so the parked thread observes the flip.
 */
public final class DebuggerEngine {
    private static final long NO_CHAIN = -1L;

    private final BreakpointRegistry breakpoints;
    private final StepController step;
    private final CallStack callStack;
    private final ExecutionController executionController;
    private final DebuggerEventSink eventSink;
    private final ExecutionFlowHistory executionFlows;

    private volatile boolean paused;
    /** Continue retains presentation until this execution ends or pauses again. */
    private boolean continuing;
    private volatile @Nullable PauseSnapshot currentSnapshot;
    /** The execute chain that owns the current pause. */
    private volatile long pausedChainId = NO_CHAIN;
    /** Resumed/stepped-out chains whose remaining stages must not re-hit their own breakpoint. */
    private final Set<Long> skippedChainIds = ConcurrentHashMap.newKeySet();
    /** A line breakpoint belongs to the invocation, not each modifier stage within it. */
    private final Set<Long> evaluatedBreakpointChains = ConcurrentHashMap.newKeySet();
    /** The before-stage event supplies the real invocation and source for a finalized result. */
    private final Map<StageKey, CommandStageEvent> observedStages = new ConcurrentHashMap<>();
    /** Server-thread scopes: a command-block chain can contain several execution queues. */
    private int executionNesting;
    private boolean executionFailed;
    private @Nullable CommandStageEvent lastStage;
    // Never reset: even identical stops in a later session must reject old watch requests.
    private long nextPauseId;

    public DebuggerEngine(
        BreakpointRegistry breakpoints,
        StepController step,
        CallStack callStack,
        ExecutionController executionController,
        DebuggerEventSink eventSink
    ) {
        this(breakpoints, step, callStack, executionController, eventSink, new ExecutionFlowHistory());
    }

    public DebuggerEngine(
        BreakpointRegistry breakpoints,
        StepController step,
        CallStack callStack,
        ExecutionController executionController,
        DebuggerEventSink eventSink,
        ExecutionFlowHistory executionFlows
    ) {
        this.breakpoints = breakpoints;
        this.step = step;
        this.callStack = callStack;
        this.executionController = executionController;
        this.eventSink = eventSink;
        this.executionFlows = executionFlows;
    }

    public boolean isPaused() {
        return paused;
    }

    /** The snapshot of the current pause, or {@code null} if not paused. Used to sync late joiners. */
    public @Nullable PauseSnapshot currentSnapshot() {
        return currentSnapshot;
    }

    /**
     * Whether the engine has any reason to inspect command stages — there is at least one
     * breakpoint, or a step is pending. Lets adapters skip building stage events on the hot path.
     */
    public boolean isActive() {
        return !breakpoints.isEmpty() || step.isStepping();
    }

    public CallStack callStack() {
        return callStack;
    }

    // --- Breakpoint management (mutations route through here so the sink is notified) ---

    /** Toggles a block breakpoint without discarding a saved condition. */
    public boolean toggleBlockBreakpoint(BlockLocation block) {
        boolean enabled = breakpoints.toggleEnabled(BreakpointTarget.whole(new SourceLocation.Block(block))).enabled();
        eventSink.breakpointsChanged(breakpoints.blocks());
        return enabled;
    }

    /** Toggles a function-line breakpoint without discarding a saved condition. */
    public boolean toggleFunctionBreakpoint(FunctionLocation location) {
        boolean enabled = breakpoints.toggleEnabled(BreakpointTarget.whole(new SourceLocation.Function(location))).enabled();
        eventSink.breakpointsChanged(breakpoints.blocks());
        return enabled;
    }

    public void clearBreakpoints() {
        breakpoints.clear();
        eventSink.breakpointsChanged(breakpoints.blocks());
    }

    /** One-click UI action: create or switch the saved definition without losing its condition. */
    public BreakpointDefinition toggleBreakpoint(BreakpointTarget target) {
        BreakpointDefinition result = breakpoints.toggleEnabled(target);
        eventSink.breakpointsChanged(breakpoints.blocks());
        return result;
    }

    public void saveBreakpoint(BreakpointDefinition definition) {
        breakpoints.put(definition);
        eventSink.breakpointsChanged(breakpoints.blocks());
    }

    public void deleteBreakpoint(BreakpointTarget target) {
        if (breakpoints.remove(target) != null) eventSink.breakpointsChanged(breakpoints.blocks());
    }

    public List<BreakpointDefinition> breakpointDefinitions() {
        return breakpoints.definitions();
    }

    /** A saved command changed; keep conditions visible but prevent accidental retargeting. */
    public boolean disableStaleStages(SourceLocation location, String previousCommand, String savedCommand) {
        if (location instanceof SourceLocation.Player) return false;
        boolean sourceChanged = !previousCommand.equals(savedCommand);
        String fingerprint = BreakpointTarget.fingerprint(savedCommand);
        boolean changed = false;
        for (BreakpointDefinition definition : breakpoints.definitions()) {
            BreakpointTarget target = definition.target();
            if (!target.location().equals(location)) continue;
            if (target.wholeCommand()) {
                if (!sourceChanged || !definition.condition().isResultCondition()) continue;
            } else {
                boolean stale = !target.commandFingerprint().equals(fingerprint);
                if (stale == definition.staleSource()) continue;
                breakpoints.put(definition.withStaleSource(stale));
                changed = true;
                continue;
            }
            if (definition.staleSource()) continue;
            breakpoints.put(definition.withStaleSource(true));
            changed = true;
        }
        if (changed) eventSink.breakpointsChanged(breakpoints.blocks());
        return changed;
    }

    /** Revalidate saved function-stage positions after a datapack reload in one sync. */
    public int revalidateFunctionStages(Map<FunctionLocation, String> currentCommands) {
        int changed = 0;
        for (BreakpointDefinition definition : breakpoints.definitions()) {
            BreakpointTarget target = definition.target();
            if (target.wholeCommand() || !(target.location() instanceof SourceLocation.Function function)) continue;
            String command = currentCommands.getOrDefault(function.location(), "");
            boolean stale = !target.commandFingerprint().equals(BreakpointTarget.fingerprint(command));
            if (stale == definition.staleSource()) continue;
            breakpoints.put(definition.withStaleSource(stale));
            changed++;
        }
        if (changed > 0) eventSink.breakpointsChanged(breakpoints.blocks());
        return changed;
    }

    public int breakpointCount() {
        return breakpoints.size();
    }

    public boolean hasBreakpoints() {
        return !breakpoints.isEmpty();
    }

    public Set<BlockLocation> blockBreakpoints() {
        return breakpoints.blocks();
    }

    public Set<FunctionLocation> functionBreakpoints() {
        return breakpoints.functions();
    }

    /**
     * Process one stage of command execution. Maintains the call stack and pauses if a breakpoint
     * is hit or an active step request is satisfied. Returns immediately on the hot path when
     * there is nothing to debug.
     */
    public void onCommandStage(CommandStageEvent event) {
        if (paused) {
            // A nested execution while the command thread is parked (e.g. a console command run
            // by the suspension loop). Debugging it would corrupt the paused chain's call stack
            // and re-enter pause() on the parked thread.
            return;
        }
        if (breakpoints.isEmpty() && !step.isStepping()) {
            return;
        }
        callStack.push(new CallFrame(event.depth(), event.location(), event.command(), event.chainId(),
            event.flowStageIndex()));
        executionFlows.recordCallStack(event.chainId(), event.flowStageIndex(), callStack.frames());
        lastStage = event;
        if (event.flowStageIndex() >= 0) observedStages.put(new StageKey(event.chainId(), event.flowStageIndex()), event);

        if (skippedChainIds.contains(event.chainId())) {
            return;
        }

        boolean breakpointHit = breakpoints.matches(event.location())
            && evaluatedBreakpointChains.add(event.chainId());
        if (!breakpointHit && event.flowStageIndex() >= 0 && !(event.location() instanceof SourceLocation.Player)) {
            BreakpointDefinition stage = breakpoints.get(BreakpointTarget.stage(event.location(),
                event.flowStageIndex(), event.command().text()));
            breakpointHit = stage != null && stage.enabled()
                && stage.condition().equals(BreakpointCondition.ALWAYS);
        }
        if (breakpointHit || step.shouldPauseAt(event.depth())) {
            pause(event, breakpointHit ? PauseReason.BREAKPOINT : PauseReason.STEP);
        }
    }

    /** Called only after the recorder has finalized this exact stage's observed results. */
    public void onCommandStageCompleted(long chainId, SourceLocation location, ExecutionFlowStage stage) {
        if (paused || skippedChainIds.contains(chainId) || location instanceof SourceLocation.Player) return;
        CommandStageEvent event = observedStages.remove(new StageKey(chainId, stage.index()));
        if (event == null || !event.location().equals(location)
            || !event.command().text().equals(stage.command().text())) return;
        BreakpointDefinition whole = breakpoints.get(BreakpointTarget.whole(location));
        BreakpointDefinition specific = breakpoints.get(BreakpointTarget.stage(location, stage.index(),
            stage.command().text()));
        boolean wholeHit = matchesResult(whole, stage) && evaluatedBreakpointChains.add(chainId);
        boolean stageHit = matchesResult(specific, stage);
        if (!wholeHit && !stageHit) return;
        List<PauseSource> outputs = stage.displayContexts().stream().map(context -> context.source()).toList();
        CommandStageEvent resultEvent = new CommandStageEvent(chainId, event.depth(), location,
            stage.command(), () -> outputs, stage.index());
        pause(resultEvent, PauseReason.BREAKPOINT, stage.callStack().isEmpty() ? callStack.frames() : stage.callStack());
    }

    private static boolean matchesResult(BreakpointDefinition definition, ExecutionFlowStage stage) {
        return definition != null && definition.enabled() && definition.condition().isResultCondition()
            && BreakpointConditionEvaluator.evaluate(definition.condition(), stage)
                == BreakpointConditionEvaluator.Result.MATCH;
    }

    private void pause(CommandStageEvent event, PauseReason reason) {
        pause(event, reason, callStack.frames());
    }

    private void pause(CommandStageEvent event, PauseReason reason, List<CallFrame> recordedStack) {
        paused = true;
        continuing = false;
        pausedChainId = event.chainId();
        step.onPaused(event.depth());

        try {
            PauseSnapshot snapshot = new PauseSnapshot(
                event.location(),
                event.command(),
                event.depth(),
                recordedStack,
                event.pauseSources().get(),
                executionFlows.snapshot(),
                reason,
                ++nextPauseId
            );
            currentSnapshot = snapshot;
            eventSink.paused(snapshot);

            if (executionController.parkUntil(() -> !paused) == ExecutionController.ParkResult.CANCELLED) {
                resetSession();
            }
        } catch (RuntimeException | Error failure) {
            resetSession();
            throw failure;
        }
    }

    /** Resume normal execution (run to the next breakpoint). */
    public void resume() {
        boolean complete = isExecutionComplete();
        // The whole-command stop is already guarded by evaluatedBreakpointChains. Let later
        // stages in this invocation hit their own breakpoints after Continue.
        if (!complete && executionNesting > 0 && (paused || step.isStepping() || continuing)) {
            boolean wasStepping = step.isStepping();
            step.clear();
            continuing = true;
            if (paused) unpause();
            else if (wasStepping) eventSink.continued();
        } else {
            clearAdvancement();
            unpause();
        }
        if (complete) clearExecutionState();
    }

    /** Resume, pausing at the next command stage (descending into called functions). */
    public void stepInto() {
        if (!paused) return;
        if (isExecutionComplete()) { resume(); return; }
        step.stepInto();
        unpause();
    }

    /** Resume, pausing at the next stage at the same call depth. */
    public void stepOver() {
        if (!paused) return;
        if (isExecutionComplete()) { resume(); return; }
        step.stepOver();
        unpause();
    }

    /** Resume, pausing once execution returns to a shallower call depth. */
    public void stepOut() {
        if (!paused) return;
        if (isExecutionComplete()) { resume(); return; }
        skipRemainingPausedChain();
        step.stepOut();
        unpause();
    }

    /**
     * Marks the end of a host tick. Command chains never span ticks, so skip bookkeeping and
     * call-stack frames left over from finished executions can be dropped. A tick cannot end
     * while paused (the pause parks the tick itself); the guard keeps the invariant anyway.
     */
    public void onTickBoundary() {
        if (executionNesting == 0) completeExecutionState();
    }

    /** Enter a command execution queue or an enclosing batch such as a command-block chain. */
    public void onExecutionStarted() {
        if (executionNesting == 0 && !paused) {
            executionFailed = false;
            lastStage = null;
            executionFlows.clear();
        }
        executionNesting++;
    }

    /** Normal completion of a scope, including synthetic/offline drivers. */
    public void onExecutionFinished() {
        onExecutionFinished(true);
    }

    /** End a scope in finally; exceptional unwinding must never create another blocking stop. */
    public void onExecutionFinished(boolean completedNormally) {
        if (!completedNormally) executionFailed = true;
        if (executionNesting > 0) executionNesting--;
        if (executionNesting != 0 || paused) return;
        executionFlows.finishExecution();
        CommandStageEvent completedStage = lastStage;
        lastStage = null;
        try {
            if (!executionFailed && step.isStepping() && completedStage != null) {
                pause(completedStage, PauseReason.EXECUTION_COMPLETE);
            }
        } finally {
            completeExecutionState();
        }
    }

    private void completeExecutionState() {
        if (paused) return;
        executionFlows.finishExecution();
        var completedFlows = executionFlows.snapshot();
        clearExecutionState();
        // If a terminal step exhausted the queue, clearExecutionState publishes the resume first.
        // The following immutable inspection update then becomes the final client state.
        if (!completedFlows.isEmpty()) eventSink.executionFlowsCompleted(completedFlows);
    }

    private void clearExecutionState() {
        if (paused) {
            return;
        }
        skippedChainIds.clear();
        evaluatedBreakpointChains.clear();
        observedStages.clear();
        callStack.clear();
        lastStage = null;
        executionFailed = false;
        clearAdvancement();
        executionFlows.clear();
    }

    /** End the host session, retaining user breakpoints but no execution state. */
    public void resetSession() {
        executionNesting = 0;
        lastStage = null;
        executionFailed = false;
        clearAdvancement();
        skippedChainIds.clear();
        evaluatedBreakpointChains.clear();
        observedStages.clear();
        callStack.clear();
        executionFlows.clear();
        unpause();
    }

    private void skipRemainingPausedChain() {
        if (pausedChainId != NO_CHAIN) {
            skippedChainIds.add(pausedChainId);
        }
    }

    private boolean isExecutionComplete() {
        PauseSnapshot snapshot = currentSnapshot;
        return paused && snapshot != null && snapshot.reason() == PauseReason.EXECUTION_COMPLETE;
    }

    private void unpause() {
        boolean wasPaused = paused;
        paused = false;
        pausedChainId = NO_CHAIN;
        currentSnapshot = null;
        if (wasPaused) {
            if (step.isStepping()) eventSink.stepping();
            else if (continuing) eventSink.continued();
            else eventSink.resumed();
        }
    }

    private void clearAdvancement() {
        boolean wasAdvancing = step.isStepping() || continuing;
        step.clear();
        continuing = false;
        // Release retained presentation at execution end, cancellation, or a tick-boundary
        // fallback. A later breakpoint within this execution must not reset the camera.
        if (wasAdvancing && !paused) eventSink.resumed();
    }

    private record StageKey(long chainId, int stageIndex) { }
}
