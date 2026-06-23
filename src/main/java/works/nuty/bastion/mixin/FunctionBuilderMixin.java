package works.nuty.bastion.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.functions.*;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import works.nuty.bastion.entry.MacroLineEntry;

import java.util.List;

@Mixin(FunctionBuilder.class)
public class FunctionBuilderMixin<T extends ExecutionCommandSource<T>> {
    @Shadow
    private @Nullable List<MacroFunction.Entry<T>> macroEntries;

    @Shadow
    @Final
    private List<String> macroArguments;

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

    @Inject(
        method = "build",
        at = @At("HEAD"),
        cancellable = true
    )
    private void bastion$build(Identifier id, CallbackInfoReturnable<CommandFunction<T>> cir) {
        if (this.macroEntries != null) {
            final MacroFunction<T> macroFunction = new MacroFunction<>(id, this.macroEntries, this.macroArguments);

            for (final MacroFunction.Entry<T> entry : this.macroEntries) {
                if (entry instanceof MacroLineEntry<T> macroEntry) {
                    macroEntry.setFunction(macroFunction);
                }
            }

            cir.setReturnValue(macroFunction);
            cir.cancel();
        }
    }
}

