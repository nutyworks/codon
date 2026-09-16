package works.nuty.bastion.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.context.StringRange;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.ChainModifiers;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
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
        if (engine != null && engine.isActive()) {
            if (trace == null) {
                SourceLocation location = SourceMapper.toSourceLocation((BuildContexts<?>) (Object) this);
                if (location != null) trace = new CommandTrace(location);
            }
        } else {
            trace = null;
        }
        CommandTrace.setCurrent(trace);
        try {
            original.call(source, sources, context, frame, modifiers);
        } finally {
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
        bastion$observe(frame, currentStage, currentSources);
    }

    @Inject(method = "execute", at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"))
    private void bastion$beforeExecutable(T source, List<T> sources, ExecutionContext<T> context,
                                         Frame frame, ChainModifiers modifiers, CallbackInfo ci,
                                         @Local(name = "currentStage") ContextChain<T> currentStage,
                                         @Local(name = "currentSources") List<T> currentSources) {
        bastion$observe(frame, currentStage, currentSources);
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
}
