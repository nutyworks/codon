package works.nuty.bastion.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.UnboundEntryAction;
import net.minecraft.commands.execution.tasks.BuildContexts;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.bastion.action.PlainLineAction;

@Mixin(CommandFunction.class)
interface CommandFunctionMixin {
    @WrapOperation(
        method = "fromLines",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/commands/functions/CommandFunction;parseCommand(Lcom/mojang/brigadier/CommandDispatcher;Lnet/minecraft/commands/ExecutionCommandSource;Lcom/mojang/brigadier/StringReader;)Lnet/minecraft/commands/execution/UnboundEntryAction;"
        )
    )
    private static <T extends ExecutionCommandSource<T>> UnboundEntryAction<T> bastion$fromLines$parseCommand(
        CommandDispatcher<T> dispatcher,
        T compilationContext,
        StringReader input,
        Operation<UnboundEntryAction<T>> original,
        @Local(name = "lineNumber") int lineNumber,
        @Local(argsOnly = true) Identifier functionId
    ) {
        BuildContexts.Unbound<T> ret = (BuildContexts.Unbound<T>) original.call(dispatcher, compilationContext, input);
        return new PlainLineAction<>(ret.commandInput, ret.command, lineNumber, functionId);
    }
}
