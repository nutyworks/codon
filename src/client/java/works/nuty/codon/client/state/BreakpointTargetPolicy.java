package works.nuty.codon.client.state;

import java.util.List;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.SourceLocation;

/** New single-stage controls address the line; legacy definitions retain their original identity. */
public final class BreakpointTargetPolicy {
    private BreakpointTargetPolicy() { }

    public static @Nullable BreakpointTarget target(SourceLocation location, int stageIndex,
                                                    String command, int stageCount) {
        if (location instanceof SourceLocation.Player || stageCount <= 0 || stageIndex < 0
            || stageIndex >= stageCount) return null;
        return stageCount == 1 ? BreakpointTarget.whole(location)
            : BreakpointTarget.stage(location, stageIndex, command);
    }

    public static int stageCount(String command, ClientStagePreviewState.@Nullable Preview preview,
                                 @Nullable ExecutionFlowTrace flow) {
        if (preview != null && preview.status() == ClientStagePreviewState.Status.READY
            && command.equals(preview.savedCommand())) return preview.spans().size();
        if (flow == null || flow.stages().isEmpty()) return 0;
        // A partial recording is not the total stage count. Only an exact terminal
        // observation at index zero proves that the command has one stage.
        var only = flow.stages().getFirst();
        if (!flow.truncated() && flow.stages().size() == 1 && only.index() == 0 && only.terminal()
            && command.equals(only.command().text()) && only.command().highlightStart() == 0
            && only.command().highlightEnd() == command.length()) return 1;
        return flow.stages().stream().anyMatch(stage -> stage.index() > 0 || !stage.terminal())
            ? Math.max(2, flow.stages().stream().mapToInt(stage -> stage.index() + 1).max().orElse(0)) : 0;
    }

    /** Treat an old sole-stage definition as part of the line control without rewriting its condition. */
    public static List<BreakpointDefinition> lineDefinitions(SourceLocation location, String command, int stageCount,
                                                            List<BreakpointDefinition> definitions) {
        String fingerprint = BreakpointTarget.fingerprint(command);
        return definitions.stream().filter(definition -> definition.target().location().equals(location))
            .filter(definition -> definition.target().wholeCommand() || stageCount == 1
                && definition.target().stageIndex() == 0 && !definition.staleSource()
                && definition.target().commandFingerprint().equals(fingerprint))
            .sorted(java.util.Comparator.comparing(definition -> !definition.target().wholeCommand())).toList();
    }
}
