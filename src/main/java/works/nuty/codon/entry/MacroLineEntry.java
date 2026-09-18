package works.nuty.codon.entry;

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
import works.nuty.codon.action.MacroLineAction;
import works.nuty.codon.action.PlainLineAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MacroLineEntry<T extends ExecutionCommandSource<T>> extends MacroFunction.MacroEntry<T> {
    public final int lineNumber;

    public MacroLineEntry(StringTemplate template, IntList parameters, T compilationContext, int lineNumber) {
        super(template, parameters, compilationContext);
        this.lineNumber = lineNumber;
    }

    @Override
    public @NonNull UnboundEntryAction<T> instantiate(
        @NonNull final List<String> substitutions,
        @NonNull final CommandDispatcher<T> dispatcher,
        @NonNull final Identifier functionId
    ) throws FunctionInstantiationException {
        UnboundEntryAction<T> action = super.instantiate(substitutions, dispatcher, functionId);
        // Another mod may wrap parseCommand with its own action type; pass those through
        // (losing only line-number attribution) instead of failing instantiation.
        if (!(action instanceof BuildContexts.Unbound<T> ret)) {
            return action;
        }

        if (substitutions.isEmpty()) {
            return new PlainLineAction<>(ret.commandInput, ret.command, this.lineNumber, functionId);
        } else {
            // MacroFunction supplies values selected by this entry's parameter indices. They
            // therefore align with template.variables(), rather than the owning function's
            // de-duplicated parameter list. A name can occur multiple times in one template.
            Map<String, String> usedVariables = new LinkedHashMap<>();
            List<String> variables = this.template.variables();
            for (int index = 0; index < substitutions.size(); index++) {
                usedVariables.putIfAbsent(variables.get(index), substitutions.get(index));
            }

            return new MacroLineAction<>(ret.commandInput, ret.command, this.lineNumber, functionId, Map.copyOf(usedVariables));
        }
    }
}
