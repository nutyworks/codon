package works.nuty.codon.adapter;

import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.context.StringRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import works.nuty.codon.core.service.ExecutionFlowRecorder;
import works.nuty.codon.network.BreakpointStagePreviewSyncPayload.StageSpan;

/** Extracts the same ContextChain stages observed by {@code BuildContextsMixin}, without executing them. */
public final class BreakpointStageParser {
    private BreakpointStageParser() { }

    /** Returns empty when Brigadier did not completely parse one executable command chain. */
    public static Optional<List<StageSpan>> parse(MinecraftServer server, CommandSourceStack source, String command) {
        if (command == null || command.isEmpty()) return Optional.empty();
        var parsed = server.getCommands().getDispatcher().parse(command, source);
        if (parsed.getReader().canRead()) return Optional.empty();
        Optional<ContextChain<CommandSourceStack>> flattened = ContextChain.tryFlatten(parsed.getContext().build(command));
        if (flattened.isEmpty()) return Optional.empty();

        List<StageSpan> stages = new ArrayList<>();
        ContextChain<CommandSourceStack> stage = flattened.get();
        while (stage != null && stages.size() < ExecutionFlowRecorder.MAX_STAGES) {
            StringRange range = stage.getTopContext().getRange();
            int start = range.getStart();
            int end = range.getEnd();
            if (start < 0 || end < start || end > command.length()) return Optional.empty();
            boolean terminal = stage.getStage() == ContextChain.Stage.EXECUTE;
            stages.add(new StageSpan(stages.size(), start, end, terminal));
            if (terminal) return Optional.of(List.copyOf(stages));
            stage = stage.nextStage();
        }
        return Optional.empty();
    }
}
