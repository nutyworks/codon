package works.nuty.codon.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.ResultConsumer;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.Frame;
import net.minecraft.commands.execution.tasks.ExecuteCommand;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.adapter.CommandTrace;

/** Counts final command attempts and their Brigadier-reported success without re-running them. */
@Mixin(ExecuteCommand.class)
public class ExecuteCommandMixin<T extends ExecutionCommandSource<T>> {
    @Unique private @Nullable CommandTrace codon$trace;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void codon$captureTrace(String commandInput,
                                      net.minecraft.commands.execution.ChainModifiers modifiers,
                                      CommandContext<T> executionContext, CallbackInfo ci) {
        codon$trace = CommandTrace.current();
    }

    @WrapMethod(method = "execute")
    private void codon$countExecution(T sender, ExecutionContext<T> context, Frame frame,
                                        Operation<Void> original) {
        if (codon$trace != null) codon$trace.executionStarted();
        original.call(sender, context, frame);
    }

    @WrapOperation(method = "execute", at = @At(value = "INVOKE",
        target = "Lcom/mojang/brigadier/context/ContextChain;runExecutable(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/Object;Lcom/mojang/brigadier/ResultConsumer;Z)I"))
    private int codon$countResult(CommandContext<T> commandContext, Object sender,
                                    ResultConsumer<T> consumer, boolean forked,
                                    Operation<Integer> original) throws CommandSyntaxException {
        ResultConsumer<T> observed = (context, success, result) -> {
            if (codon$trace != null) codon$trace.executionResult(success);
            consumer.onCommandComplete(context, success, result);
        };
        return original.call(commandContext, sender, observed, forked);
    }
}
