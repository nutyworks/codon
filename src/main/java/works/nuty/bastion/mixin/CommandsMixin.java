package works.nuty.bastion.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import works.nuty.bastion.BastionMod;
import works.nuty.bastion.core.service.DebuggerEngine;

@Mixin(Commands.class)
public abstract class CommandsMixin {

    @Shadow
    public abstract CommandDispatcher<CommandSourceStack> getDispatcher();

    /** Debugger commands must never enter the execution they inspect, even just after a step
     * unpauses it. Ordinary commands retain vanilla ContextChain/custom-executor semantics. */
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void bastion$executeControlsImmediately(ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {
        DebuggerEngine engine = BastionMod.engine();
        if (engine == null || parseResults.getContext().getNodes().isEmpty()) return;
        String root = parseResults.getContext().getNodes().getFirst().getNode().getName();
        if (root.equals("bastion") || (engine.isPaused() && root.equals("stop"))) {
            CommandSourceStack source = parseResults.getContext().getSource();
            try {
                this.getDispatcher().execute(parseResults);
            } catch (Exception e) {
                source.sendFailure(Component.literal("Error: " + e.getMessage()));
            }
            ci.cancel();
        }
    }
}
