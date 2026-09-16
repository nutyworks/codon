package works.nuty.bastion.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.functions.FunctionBuilder;
import net.minecraft.commands.functions.MacroFunction;
import net.minecraft.commands.functions.StringTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.bastion.entry.MacroLineEntry;

@Mixin(FunctionBuilder.class)
public class FunctionBuilderMixin<T extends ExecutionCommandSource<T>> {
    @WrapOperation(
        method = "addMacro",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/commands/functions/StringTemplate;Lit/unimi/dsi/fastutil/ints/IntList;Lnet/minecraft/commands/ExecutionCommandSource;)Lnet/minecraft/commands/functions/MacroFunction$MacroEntry;"
        )
    )
    private MacroFunction.MacroEntry<T> bastion$addMacro$newPlainTextEntry(
        StringTemplate template, IntList parameters, T compilationContext, Operation<MacroFunction.MacroEntry<T>> original,
        @Local(name = "line") int lineNumber
    ) {
        MacroFunction.MacroEntry<T> ret = original.call(template, parameters, compilationContext);
        return new MacroLineEntry<>(ret.template, ret.parameters, ret.compilationContext, lineNumber);
    }
}
