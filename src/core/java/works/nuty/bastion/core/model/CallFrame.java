package works.nuty.bastion.core.model;

/**
 * One entry in the debugger call stack: the command at a given execution depth and where it
 * originated. Replaces the old {@code Bastion.CallStackEntry}.
 */
public record CallFrame(int depth, SourceLocation location, CommandSnippet command) {
}
