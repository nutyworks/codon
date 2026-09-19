package works.nuty.codon.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.execution.CommandQueueEntry;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.tasks.BuildContexts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.CommandTrace;
import works.nuty.codon.adapter.TracedCommand;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.core.model.ExecutionFlowWarning.Reason;

@Mixin(ExecutionContext.class)
abstract class ExecutionContextMixin<T> {
    @Shadow @Final private int commandLimit;
    @Shadow @Final private static int MAX_QUEUE_DEPTH;
    @Shadow private int commandQuota;
    @Shadow private boolean queueOverflow;
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
        CommandTrace.QueueScope scope = CommandTrace.openQueueScope();
        boolean completedNormally = false;
        String failureDetail = "Execution queue ended before its continuation was observed";
        try {
            original.call();
            completedNormally = true;
        } catch (RuntimeException | Error failure) {
            failureDetail = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            throw failure;
        } finally {
            Reason reason = !completedNormally ? Reason.EXECUTION_ERROR : queueOverflow ? Reason.QUEUE_LIMIT
                : commandQuota <= 0 ? Reason.COMMAND_LIMIT : Reason.CONTINUATION_NOT_RESUMED;
            int limit = reason == Reason.COMMAND_LIMIT ? commandLimit : reason == Reason.QUEUE_LIMIT ? MAX_QUEUE_DEPTH : -1;
            String detail = reason == Reason.COMMAND_LIMIT ? "Command execution limit exhausted before the continuation began"
                : reason == Reason.QUEUE_LIMIT ? "Execution queue overflow discarded the continuation" : failureDetail;
            scope.finish(reason, limit, detail);
            if (engine != null) engine.onExecutionFinished(completedNormally);
        }
    }
}
