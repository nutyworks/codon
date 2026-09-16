package works.nuty.bastion.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.bastion.adapter.DebuggerTaskQueue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Routes debugger commands from dedicated console and RCON through the debugger mailbox. */
@Mixin(DedicatedServer.class)
abstract class DedicatedServerMixin {
    @WrapMethod(method = "runCommand")
    private String bastion$routeRconControl(String command, Operation<String> original) {
        DedicatedServer server = (DedicatedServer) (Object) this;
        if (server.isSameThread() || !DebuggerTaskQueue.isControlCommand(command)) {
            return original.call(command);
        }
        CompletableFuture<String> result = new CompletableFuture<>();
        DebuggerTaskQueue.execute(server, () -> {
            try {
                result.complete(original.call(command));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Debugger command interrupted";
        } catch (Exception e) {
            return "Debugger command unavailable: " + e.getMessage();
        }
    }

    @Inject(method = "handleConsoleInput", at = @At("HEAD"), cancellable = true)
    private void bastion$enqueueDebuggerConsoleCommand(String message, CommandSourceStack source, CallbackInfo ci) {
        String command = message.trim();
        if (!DebuggerTaskQueue.isControlCommand(command)) {
            return;
        }
        DedicatedServer server = (DedicatedServer) (Object) this;
        DebuggerTaskQueue.execute(server, () -> server.getCommands().performPrefixedCommand(source, command));
        ci.cancel();
    }
}
