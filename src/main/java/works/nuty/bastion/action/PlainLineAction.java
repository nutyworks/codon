package works.nuty.bastion.action;

import com.mojang.brigadier.context.ContextChain;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.commands.execution.tasks.BuildContexts;
import net.minecraft.resources.Identifier;

public class PlainLineAction<T extends ExecutionCommandSource<T>> extends BuildContexts.Unbound<T> {
    public final Identifier functionId;
    public final int lineNumber;

    public PlainLineAction(String commandInput, ContextChain<T> command, int lineNumber, Identifier id) {
        super(commandInput, command);
        this.functionId = id;
        this.lineNumber = lineNumber;
    }
}
