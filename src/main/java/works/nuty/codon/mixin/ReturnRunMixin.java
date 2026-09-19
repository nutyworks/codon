package works.nuty.codon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.context.ContextChain;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.ChainModifiers;
import net.minecraft.commands.execution.EntryAction;
import net.minecraft.commands.execution.ExecutionControl;
import net.minecraft.commands.execution.tasks.BuildContexts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.adapter.CommandTrace;
import works.nuty.codon.adapter.SourceMapper;

import java.util.List;

/** The return-run modifier forwards its actual source list unchanged into a queued continuation. */
@Mixin(targets = "net.minecraft.server.commands.ReturnCommand$ReturnFromCommandCustomModifier")
public abstract class ReturnRunMixin<T extends ExecutionCommandSource<T>> {
    @WrapOperation(method = "apply(Lnet/minecraft/commands/ExecutionCommandSource;Ljava/util/List;Lcom/mojang/brigadier/context/ContextChain;Lnet/minecraft/commands/execution/ChainModifiers;Lnet/minecraft/commands/execution/ExecutionControl;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/commands/execution/ExecutionControl;queueNext(Lnet/minecraft/commands/execution/EntryAction;)V"))
    private void codon$forwardReturnSources(ExecutionControl<T> control, EntryAction<T> action,
                                           Operation<Void> original, @Local(argsOnly = true) List<T> sources) {
        CommandTrace trace = CommandTrace.current();
        if (trace != null && action instanceof BuildContexts.Continuation<?>) {
            trace.forwardContinuation(sources.size(), limit -> sources.subList(0, Math.min(limit, sources.size()))
                .stream().map(source -> SourceMapper.toPauseSource((CommandSourceStack) source)).toList());
        }
        original.call(control, action);
    }

    @Inject(method = "apply(Lnet/minecraft/commands/ExecutionCommandSource;Ljava/util/List;Lcom/mojang/brigadier/context/ContextChain;Lnet/minecraft/commands/execution/ChainModifiers;Lnet/minecraft/commands/execution/ExecutionControl;)V",
        at = @At("RETURN"))
    private void codon$recordEmptyReturn(T originalSource, List<T> sources, ContextChain<T> step,
                                         ChainModifiers modifiers, ExecutionControl<T> control, CallbackInfo ci) {
        CommandTrace trace = CommandTrace.current();
        if (trace != null && sources.isEmpty()) trace.finishStage(List.of());
    }
}
