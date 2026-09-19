package works.nuty.codon.core.model;

/**
 * One entry in the debugger call stack: the command at a given execution depth and where it
 * originated. Replaces the old {@code Codon.CallStackEntry}.
 */
public record CallFrame(int depth, SourceLocation location, CommandSnippet command,
                        long invocationId, int flowStageIndex) {
    /** Compatibility constructor for frames without an execution-flow identity. */
    public CallFrame(int depth, SourceLocation location, CommandSnippet command) {
        this(depth, location, command, -1, -1);
    }
}
