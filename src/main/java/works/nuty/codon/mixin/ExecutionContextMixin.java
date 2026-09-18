package works.nuty.codon.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.execution.CommandQueueEntry;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.tasks.BuildContexts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.CommandTrace;
import works.nuty.codon.adapter.TracedCommand;
import works.nuty.codon.core.service.DebuggerEngine;

@Mixin(ExecutionContext.class)
abstract class ExecutionContextMixin<T> {
    @Inject(method = "queueNext", at = @At("HEAD"))
    private void codon$inheritContinuation(CommandQueueEntry<T> entry, CallbackInfo ci) {
        CommandTrace trace = CommandTrace.current();
        if (trace != null && entry.action() instanceof BuildContexts.Continuation<?>
            && entry.action() instanceof TracedCommand traced) {
            traced.codon$inheritTrace(trace.forkForContinuation());
        }
    }

    @WrapMethod(method = "runCommandQueue")
    private void codon$finishExecution(Operation<Void> original) {
        DebuggerEngine engine = CodonMod.engine();
        if (engine != null) engine.onExecutionStarted();
        boolean completedNormally = false;
        try {
            original.call();
            completedNormally = true;
        } finally {
            if (engine != null) engine.onExecutionFinished(completedNormally);
        }
    }
}
