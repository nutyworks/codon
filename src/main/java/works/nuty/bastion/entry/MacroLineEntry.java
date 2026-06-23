package works.nuty.bastion.entry;

import com.mojang.brigadier.CommandDispatcher;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.FunctionInstantiationException;
import net.minecraft.commands.execution.UnboundEntryAction;
import net.minecraft.commands.execution.tasks.BuildContexts;
import net.minecraft.commands.functions.MacroFunction;
import net.minecraft.commands.functions.StringTemplate;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.bastion.action.MacroLineAction;
import works.nuty.bastion.action.PlainLineAction;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class MacroLineEntry<T extends ExecutionCommandSource<T>> extends MacroFunction.MacroEntry<T> {
    public MacroFunction<T> function;
    public final int lineNumber;

    public MacroLineEntry(StringTemplate template, IntList parameters, T compilationContext, int lineNumber) {
        super(template, parameters, compilationContext);
        this.lineNumber = lineNumber;
    }

    public void setFunction(MacroFunction<T> function) {
        this.function = function;
    }

    @Override
    public @NonNull UnboundEntryAction<T> instantiate(
        @NonNull final List<String> substitutions,
        @NonNull final CommandDispatcher<T> dispatcher,
        @NonNull final Identifier functionId
    ) throws FunctionInstantiationException {
        BuildContexts.Unbound<T> ret = (BuildContexts.Unbound<T>) super.instantiate(substitutions, dispatcher, functionId);

        if (substitutions.isEmpty()) {
            return new PlainLineAction<>(ret.commandInput, ret.command, this.lineNumber, functionId);
        } else {
            List<String> usedKeys = this.template.variables();
            Map<String, String> usedVariables = IntStream.range(0, substitutions.size()).boxed()
                .collect(Collectors.toMap(this.function.parameters::get, substitutions::get));

            usedVariables.keySet().removeIf(key -> !usedKeys.contains(key));

            return new MacroLineAction<>(ret.commandInput, ret.command, this.lineNumber, functionId, usedVariables);
        }
    }
}
