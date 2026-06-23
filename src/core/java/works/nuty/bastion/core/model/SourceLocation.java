package works.nuty.bastion.core.model;

import java.util.UUID;

/**
 * Where a command stage is executing from — the Minecraft-free replacement for the old
 * {@code Bastion.InitialSource}. Carries plain data only; rendering it (to a chat component,
 * a DAP frame, etc.) is the presentation layer's job.
 */
public sealed interface SourceLocation {
    /** Execution triggered from a command block (or other block) at a position. */
    record Block(BlockLocation block) implements SourceLocation {}

    /** Execution of a line inside a datapack function. */
    record Function(FunctionLocation location) implements SourceLocation {}

    /** Execution triggered directly by a player. */
    record Player(UUID uuid, String name) implements SourceLocation {}
}
