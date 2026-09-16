package works.nuty.bastion.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.execution.CommandQueueEntry;
import net.minecraft.commands.execution.ExecutionContext;
import net.minecraft.commands.execution.tasks.BuildContexts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.bastion.BastionMod;
import works.nuty.bastion.adapter.CommandTrace;
import works.nuty.bastion.adapter.TracedCommand;

@Mixin(ExecutionContext.class)
abstract class ExecutionContextMixin<T> {
    @Inject(method = "queueNext", at = @At("HEAD"))
    private void bastion$inheritContinuation(CommandQueueEntry<T> entry, CallbackInfo ci) {
        CommandTrace trace = CommandTrace.current();
        if (trace != null && entry.action() instanceof BuildContexts.Continuation<?>
            && entry.action() instanceof TracedCommand traced) {
            traced.bastion$inheritTrace(trace);
        }
    }

    @WrapMethod(method = "runCommandQueue")
    private void bastion$finishExecution(Operation<Void> original) {
        try {
            original.call();
        } finally {
            if (BastionMod.engine() != null) {
                BastionMod.engine().onExecutionFinished();
            }
        }
    }
}
