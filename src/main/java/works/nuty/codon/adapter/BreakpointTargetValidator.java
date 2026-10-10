package works.nuty.codon.adapter;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.service.ExecutionFlowRecorder;
import works.nuty.codon.network.BreakpointEditResultPayload.Status;

/** Decides whether a definition can target the command currently saved at its location. */
public final class BreakpointTargetValidator {
    /** Why a definition was accepted or rejected; {@link #status()} is its wire acknowledgement. */
    public enum Verdict {
        VALID(Status.APPLIED),
        STALE_SOURCE(Status.STALE_SOURCE),
        SOURCE_UNAVAILABLE(Status.FAILED),
        NO_TARGET(Status.INVALID_TARGET),
        MACRO_STAGE_OR_CONDITION(Status.INVALID_TARGET),
        UNPARSEABLE(Status.INVALID_TARGET),
        NO_SUCH_STAGE(Status.INVALID_TARGET),
        TERMINAL_STAGE(Status.INVALID_TARGET);

        private final Status status;

        Verdict(Status status) { this.status = status; }

        public Status status() { return status; }
    }

    /** The saved command at a location, or the reason none could be read. */
    public record SavedCommand(Verdict verdict, String command) {
        static SavedCommand failure(Verdict verdict) { return new SavedCommand(verdict, ""); }
    }

    private BreakpointTargetValidator() { }

    /** Reads the saved command without loading chunks or executing anything. */
    public static SavedCommand savedCommand(MinecraftServer server, SourceLocation location) {
        return switch (location) {
            case SourceLocation.Block block -> {
                ServerLevel level = null;
                for (ServerLevel candidate : server.getAllLevels()) {
                    if (candidate.dimension().identifier().toString().equals(block.block().dimension())) {
                        level = candidate;
                        break;
                    }
                }
                if (level == null) yield SavedCommand.failure(Verdict.NO_TARGET);
                BlockPos position = new BlockPos(block.block().x(), block.block().y(), block.block().z());
                if (!level.isLoaded(position) || !(level.getBlockEntity(position) instanceof CommandBlockEntity entity))
                    yield SavedCommand.failure(Verdict.NO_TARGET);
                yield new SavedCommand(Verdict.VALID, entity.getCommandBlock().getCommand());
            }
            case SourceLocation.Function function -> {
                var line = FunctionSourceRepository.commandLine(server, function.location());
                yield switch (line.status()) {
                    case UNAVAILABLE -> SavedCommand.failure(Verdict.SOURCE_UNAVAILABLE);
                    case INVALID -> SavedCommand.failure(Verdict.NO_TARGET);
                    case READY -> new SavedCommand(Verdict.VALID, line.command());
                };
            }
            case SourceLocation.Player ignored -> SavedCommand.failure(Verdict.NO_TARGET);
        };
    }

    public static Verdict validate(MinecraftServer server, BreakpointDefinition definition) {
        BreakpointTarget target = definition.target();
        if (target.stageIndex() >= ExecutionFlowRecorder.MAX_STAGES) return Verdict.NO_SUCH_STAGE;
        SavedCommand saved = savedCommand(server, target.location());
        if (saved.verdict() != Verdict.VALID) return saved.verdict();
        String command = saved.command();
        if (target.location() instanceof SourceLocation.Function && command.startsWith("$")
            && (!target.wholeCommand() || definition.condition().isResultCondition()))
            return Verdict.MACRO_STAGE_OR_CONDITION;
        if (!target.wholeCommand() && !target.commandFingerprint().equals(BreakpointTarget.fingerprint(command)))
            return Verdict.STALE_SOURCE;
        if (!target.wholeCommand() || definition.condition().isResultCondition()) {
            var stages = BreakpointStageParser.parse(server, server.createCommandSourceStack(), command);
            if (stages.isEmpty()) return Verdict.UNPARSEABLE;
            if (target.wholeCommand()) {
                if (stages.get().stream().allMatch(span -> span.terminal())) return Verdict.TERMINAL_STAGE;
            } else if (target.stageIndex() >= stages.get().size()) {
                return Verdict.NO_SUCH_STAGE;
            } else if (definition.condition().isResultCondition() && stages.get().get(target.stageIndex()).terminal()) {
                return Verdict.TERMINAL_STAGE;
            }
        }
        return Verdict.VALID;
    }
}
