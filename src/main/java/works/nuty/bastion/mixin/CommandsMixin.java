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

    /**
     * While the debugger is parked, {@code executeCommandInContext} would enqueue any new command
     * into the suspended execution context, deferring it until resume — a paused server could
     * never receive {@code /bastion resume}. Run commands issued during a pause immediately on the
     * plain dispatcher instead. Players, the console, and RCON all funnel through
     * {@code performCommand}, so each of them can lift a pause.
     */
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void bastion$executeImmediatelyWhenPaused(ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {
        DebuggerEngine engine = BastionMod.engine();
        if (engine != null && engine.isPaused()) {
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
