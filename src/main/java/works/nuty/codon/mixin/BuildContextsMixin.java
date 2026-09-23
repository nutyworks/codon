package works.nuty.codon.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.ResultConsumer;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.CommandResultCallback;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.ChainModifiers;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
import net.minecraft.commands.execution.CustomCommandExecutor;
import net.minecraft.commands.execution.CustomModifierExecutor;
import net.minecraft.commands.execution.TraceCallbacks;
import net.minecraft.commands.execution.ExecutionControl;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.commands.execution.tasks.BuildContexts;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.CommandTrace;
import works.nuty.codon.adapter.TracedCommand;
import works.nuty.codon.adapter.SourceMapper;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.ExecutionFlowWarning.Reason;
import works.nuty.codon.core.service.CommandStageEvent;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.core.service.ExecutionFlowHistory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Mixin(BuildContexts.class)
public class BuildContextsMixin<T extends ExecutionCommandSource<T>> implements TracedCommand {
    @Shadow @Final public String commandInput;
    @Unique private @Nullable CommandTrace codon$inheritedTrace;

    @Override
    public void codon$inheritTrace(CommandTrace trace) { codon$inheritedTrace = trace; }

    @WrapMethod(method = "execute")
    private void codon$traceInvocation(T source, List<T> sources, ExecutionContext<T> context,
                                       Frame frame, ChainModifiers modifiers, Operation<Void> original) {
        DebuggerEngine engine = CodonMod.engine();
        CommandTrace previous = CommandTrace.current();
        CommandTrace trace = codon$inheritedTrace;
        if (engine != null && engine.isActive() && !engine.isPaused()) {
            if (trace == null) {
                SourceLocation location = SourceMapper.toSourceLocation((BuildContexts<?>) (Object) this);
                ExecutionFlowHistory history = CodonMod.executionFlows();
                if (location != null && history != null)
                    trace = new CommandTrace(location, history, engine::onCommandStageCompleted);
            }
        } else {
            trace = null;
        }
        CommandTrace.setCurrent(trace);
        boolean failed = true;
        try {
            original.call(source, sources, context, frame, modifiers);
            failed = false;
        } catch (RuntimeException | Error failure) {
            if (trace != null) trace.interrupted(Reason.EXECUTION_ERROR, -1,
                failure.getClass().getSimpleName() + ": " + failure.getMessage());
            throw failure;
        } finally {
            if (trace != null) trace.invocationEnded(failed);
            CommandTrace.setCurrent(previous);
            codon$inheritedTrace = null;
        }
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/commands/execution/CustomModifierExecutor;apply(Ljava/lang/Object;Ljava/util/List;Lcom/mojang/brigadier/context/ContextChain;Lnet/minecraft/commands/execution/ChainModifiers;Lnet/minecraft/commands/execution/ExecutionControl;)V"))
    private void codon$identifyCustomModifier(CustomModifierExecutor<T> modifier, Object source, List<T> sources,
                                              ContextChain<T> stage, ChainModifiers modifiers,
                                              ExecutionControl<T> control, Operation<Void> original) {
        CommandTrace trace = CommandTrace.current();
        if (trace != null) trace.interrupted(Reason.UNSUPPORTED_MODIFIER, -1, modifier.getClass().getName());
        original.call(modifier, source, sources, stage, modifiers, control);
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/commands/ExecutionCommandSource;handleError(Lcom/mojang/brigadier/exceptions/CommandSyntaxException;ZLnet/minecraft/commands/execution/TraceCallbacks;)V"))
    private void codon$identifyEarlyExit(ExecutionCommandSource<T> source, CommandSyntaxException error,
                                        boolean forked, TraceCallbacks callbacks, Operation<Void> original,
                                        @Local(argsOnly = true) ExecutionContext<T> context) {
        CommandTrace trace = CommandTrace.current();
        if (trace != null) {
            boolean forkLimit = error.getType() == BuildContexts.ERROR_FORK_LIMIT_REACHED;
            trace.interrupted(forkLimit ? Reason.FORK_LIMIT : Reason.EXECUTION_ERROR,
                forkLimit ? context.forkLimit() : -1, error.getRawMessage().getString());
        }
        original.call(source, error, forked, callbacks);
    }

    @Inject(method = "execute", at = @At(value = "INVOKE",
        target = "Lcom/mojang/brigadier/context/ContextChain;getTopContext()Lcom/mojang/brigadier/context/CommandContext;"),
        slice = @Slice(to = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z")))
    private void codon$beforeModifier(T source, List<T> sources, ExecutionContext<T> context,
                                       Frame frame, ChainModifiers modifiers, CallbackInfo ci,
                                       @Local(name = "currentSources") List<T> currentSources,
                                       @Local(name = "currentStage") ContextChain<T> currentStage) {
        codon$beginFlowStage(currentStage, currentSources, false);
        codon$observe(frame, currentStage, currentSources);
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
        target = "Lcom/mojang/brigadier/context/ContextChain;runModifier(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/Object;Lcom/mojang/brigadier/ResultConsumer;Z)Ljava/util/Collection;"))
    private Collection<T> codon$runModifier(CommandContext<T> commandContext, Object source,
                                               ResultConsumer<T> consumer, boolean forked,
                                               Operation<Collection<T>> original) throws CommandSyntaxException {
        CommandTrace trace = CommandTrace.current();
        try {
            Collection<T> result = original.call(commandContext, source, consumer, forked);
            if (trace != null) trace.modifierReturned();
            return result;
        } catch (Throwable failure) {
            if (trace != null) trace.modifierFailed();
            if (failure instanceof CommandSyntaxException syntax) throw syntax;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new RuntimeException(failure);
        }
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
        target = "Ljava/util/List;addAll(Ljava/util/Collection;)Z"))
    private boolean codon$acceptModifierOutputs(List<T> target, Collection<T> outputs,
                                                   Operation<Boolean> original) {
        boolean added = original.call(target, outputs);
        CommandTrace trace = CommandTrace.current();
        if (trace != null) trace.acceptModifierOutputs(outputs.size(),
            limit -> codon$pauseSources(outputs, limit));
        return added;
    }

    @Inject(method = "execute", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"))
    private void codon$beforeExecutable(T source, List<T> sources, ExecutionContext<T> context,
                                         Frame frame, ChainModifiers modifiers, CallbackInfo ci,
                                         @Local(name = "currentStage") ContextChain<T> currentStage,
                                         @Local(name = "currentSources") List<T> currentSources) {
        codon$beginFlowStage(currentStage, currentSources, true);
        codon$observe(frame, currentStage, currentSources);
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/commands/execution/CustomCommandExecutor;run(Ljava/lang/Object;Lcom/mojang/brigadier/context/ContextChain;Lnet/minecraft/commands/execution/ChainModifiers;Lnet/minecraft/commands/execution/ExecutionControl;)V"))
    @SuppressWarnings("unchecked")
    private void codon$runCustomCommand(CustomCommandExecutor<T> executor, Object sourceObject,
                                          ContextChain<T> stage, ChainModifiers modifiers,
                                          ExecutionControl<T> control, Operation<Void> original) {
        CommandTrace trace = CommandTrace.current();
        T source = (T) sourceObject;
        T observed = source;
        if (trace != null) {
            trace.executionStarted();
            CommandResultCallback existing = source.callback();
            observed = (T) source.withCallback((success, result) -> {
                try {
                    existing.onResult(success, result);
                } finally {
                    trace.executionResult(success);
                }
            });
        }
        original.call(executor, observed, stage, modifiers, control);
    }

    @Unique
    @SuppressWarnings("unchecked")
    private void codon$observe(Frame frame, ContextChain<T> stage, List<T> sources) {
        DebuggerEngine engine = CodonMod.engine();
        CommandTrace trace = CommandTrace.current();
        if (engine == null || trace == null || !engine.isActive()) return;
        StringRange range = stage.getTopContext().getRange();
        CommandSnippet command = new CommandSnippet(commandInput, range.getStart(), range.getEnd());
        List<CommandSourceStack> current = (List<CommandSourceStack>) sources;
        engine.onCommandStage(new CommandStageEvent(trace.id, frame.depth(), trace.location, command,
            () -> SourceMapper.toPauseSources(current), trace.flowStageIndex()));
    }

    @Unique
    private void codon$beginFlowStage(ContextChain<T> stage, Collection<T> sources, boolean terminal) {
        CommandTrace trace = CommandTrace.current();
        if (trace == null) return;
        StringRange range = stage.getTopContext().getRange();
        trace.beginStage(new CommandSnippet(commandInput, range.getStart(), range.getEnd()), sources.size(),
            limit -> codon$pauseSources(sources, limit), terminal);
    }

    @Unique
    private List<works.nuty.codon.core.model.PauseSource> codon$pauseSources(Collection<T> sources) {
        return codon$pauseSources(sources, Integer.MAX_VALUE);
    }

    @Unique
    private List<works.nuty.codon.core.model.PauseSource> codon$pauseSources(Collection<T> sources,
                                                                                 int limit) {
        List<CommandSourceStack> commandSources = new ArrayList<>(Math.min(sources.size(), Math.max(0, limit)));
        int retained = 0;
        for (T source : sources) {
            if (retained++ >= limit) break;
            commandSources.add((CommandSourceStack) source);
        }
        return SourceMapper.toPauseSources(commandSources);
    }
}
