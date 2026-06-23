package works.nuty.bastion.action;

import com.mojang.brigadier.context.ContextChain;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.tasks.BuildContexts;
import net.minecraft.resources.Identifier;

import java.util.Map;

public class MacroLineAction<T extends ExecutionCommandSource<T>> extends BuildContexts.Unbound<T> {
    public final Identifier functionId;
    public final int lineNumber;
    public final Map<String, String> usedVariables;

    public MacroLineAction(String commandInput, ContextChain<T> command, int lineNumber, Identifier id, Map<String, String> substitutions) {
        super(commandInput, command);
        this.functionId = id;
        this.lineNumber = lineNumber;
        this.usedVariables = substitutions;
    }
}
