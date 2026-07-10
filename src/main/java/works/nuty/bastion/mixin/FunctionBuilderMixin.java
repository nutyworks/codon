package works.nuty.bastion.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.functions.CommandFunction;
import net.minecraft.commands.functions.FunctionBuilder;
import net.minecraft.commands.functions.MacroFunction;
import net.minecraft.commands.functions.StringTemplate;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import works.nuty.bastion.entry.MacroLineEntry;

import java.util.List;

@Mixin(FunctionBuilder.class)
public class FunctionBuilderMixin<T extends ExecutionCommandSource<T>> {
    @Shadow
    private @Nullable List<MacroFunction.Entry<T>> macroEntries;

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

    /**
     * Macro entries resolve their line's variable substitutions against the owning function's
     * parameter list at instantiation time; hand each Bastion entry the function once vanilla has
     * built it.
     */
    @ModifyReturnValue(method = "build", at = @At("RETURN"))
    private CommandFunction<T> bastion$linkMacroEntriesToFunction(CommandFunction<T> function) {
        if (function instanceof MacroFunction<T> macroFunction && this.macroEntries != null) {
            for (MacroFunction.Entry<T> entry : this.macroEntries) {
                if (entry instanceof MacroLineEntry<T> macroEntry) {
                    macroEntry.setFunction(macroFunction);
                }
            }
        }
        return function;
    }
}
