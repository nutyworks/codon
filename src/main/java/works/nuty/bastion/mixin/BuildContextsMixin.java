package works.nuty.bastion.mixin;

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
import works.nuty.bastion.BastionMod;
import works.nuty.bastion.adapter.CommandTrace;
import works.nuty.bastion.adapter.TracedCommand;
import works.nuty.bastion.adapter.SourceMapper;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.SourceLocation;
import works.nuty.bastion.core.service.CommandStageEvent;
import works.nuty.bastion.core.service.DebuggerEngine;
import works.nuty.bastion.core.service.ExecutionFlowHistory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Mixin(BuildContexts.class)
public class BuildContextsMixin<T extends ExecutionCommandSource<T>> implements TracedCommand {
    @Shadow @Final public String commandInput;
    @Unique private @Nullable CommandTrace bastion$inheritedTrace;

    @Override
    public void bastion$inheritTrace(CommandTrace trace) { bastion$inheritedTrace = trace; }

    @WrapMethod(method = "execute")
    private void bastion$traceInvocation(T source, List<T> sources, ExecutionContext<T> context,
                                       Frame frame, ChainModifiers modifiers, Operation<Void> original) {
        DebuggerEngine engine = BastionMod.engine();
        CommandTrace previous = CommandTrace.current();
        CommandTrace trace = bastion$inheritedTrace;
        if (engine != null && engine.isActive() && !engine.isPaused()) {
            if (trace == null) {
                SourceLocation location = SourceMapper.toSourceLocation((BuildContexts<?>) (Object) this);
                ExecutionFlowHistory history = BastionMod.executionFlows();
                if (location != null && history != null) trace = new CommandTrace(location, history);
            }
        } else {
            trace = null;
        }
        CommandTrace.setCurrent(trace);
        try {
            original.call(source, sources, context, frame, modifiers);
        } finally {
            if (trace != null) trace.abandonStage();
            CommandTrace.setCurrent(previous);
            bastion$inheritedTrace = null;
        }
    }

    @Inject(method = "execute", at = @At(value = "INVOKE",
        target = "Lcom/mojang/brigadier/context/ContextChain;getTopContext()Lcom/mojang/brigadier/context/CommandContext;"),
        slice = @Slice(to = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z")))
    private void bastion$beforeModifier(T source, List<T> sources, ExecutionContext<T> context,
                                       Frame frame, ChainModifiers modifiers, CallbackInfo ci,
                                       @Local(name = "currentSources") List<T> currentSources,
                                       @Local(name = "currentStage") ContextChain<T> currentStage) {
        bastion$beginFlowStage(currentStage, currentSources, false);
        bastion$observe(frame, currentStage, currentSources);
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
        target = "Lcom/mojang/brigadier/context/ContextChain;runModifier(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/Object;Lcom/mojang/brigadier/ResultConsumer;Z)Ljava/util/Collection;"))
    private Collection<T> bastion$runModifier(CommandContext<T> commandContext, Object source,
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
    private boolean bastion$acceptModifierOutputs(List<T> target, Collection<T> outputs,
                                                   Operation<Boolean> original) {
        boolean added = original.call(target, outputs);
        CommandTrace trace = CommandTrace.current();
        if (trace != null) trace.acceptModifierOutputs(outputs.size(),
            limit -> bastion$pauseSources(outputs, limit));
        return added;
    }

    @Inject(method = "execute", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"))
    private void bastion$beforeExecutable(T source, List<T> sources, ExecutionContext<T> context,
                                         Frame frame, ChainModifiers modifiers, CallbackInfo ci,
                                         @Local(name = "currentStage") ContextChain<T> currentStage,
                                         @Local(name = "currentSources") List<T> currentSources) {
        bastion$beginFlowStage(currentStage, currentSources, true);
        bastion$observe(frame, currentStage, currentSources);
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/commands/execution/CustomCommandExecutor;run(Ljava/lang/Object;Lcom/mojang/brigadier/context/ContextChain;Lnet/minecraft/commands/execution/ChainModifiers;Lnet/minecraft/commands/execution/ExecutionControl;)V"))
    @SuppressWarnings("unchecked")
    private void bastion$runCustomCommand(CustomCommandExecutor<T> executor, Object sourceObject,
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
    private void bastion$observe(Frame frame, ContextChain<T> stage, List<T> sources) {
        DebuggerEngine engine = BastionMod.engine();
        CommandTrace trace = CommandTrace.current();
        if (engine == null || trace == null || !engine.isActive()) return;
        StringRange range = stage.getTopContext().getRange();
        CommandSnippet command = new CommandSnippet(commandInput, range.getStart(), range.getEnd());
        List<CommandSourceStack> current = (List<CommandSourceStack>) sources;
        engine.onCommandStage(new CommandStageEvent(trace.id, frame.depth(), trace.location, command,
            () -> SourceMapper.toPauseSources(current)));
    }

    @Unique
    private void bastion$beginFlowStage(ContextChain<T> stage, Collection<T> sources, boolean terminal) {
        CommandTrace trace = CommandTrace.current();
        if (trace == null) return;
        StringRange range = stage.getTopContext().getRange();
        trace.beginStage(new CommandSnippet(commandInput, range.getStart(), range.getEnd()), sources.size(),
            limit -> bastion$pauseSources(sources, limit), terminal);
    }

    @Unique
    private List<works.nuty.bastion.core.model.PauseSource> bastion$pauseSources(Collection<T> sources) {
        return bastion$pauseSources(sources, Integer.MAX_VALUE);
    }

    @Unique
    private List<works.nuty.bastion.core.model.PauseSource> bastion$pauseSources(Collection<T> sources,
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
