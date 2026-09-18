package works.nuty.codon.core.service;

import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;

import java.util.List;
import java.util.function.Supplier;

/**
 * The driving input to {@link DebuggerEngine#onCommandStage}: one stage of command execution,
 * translated from Minecraft internals by the mixin adapter into pure core terms.
 *
 * <p>{@code pauseSources} is a {@link Supplier} because building the in-world visualization data
 * is only needed if this stage actually pauses; the engine invokes it lazily.
 *
 * <p>{@code chainId} identifies the execute chain a stage belongs to: all stages produced by one
 * command's fork/modifier expansion share it, and a fresh invocation of the same command gets a
 * new id. It lets resume skip the remainder of the chain it paused in.
 */
public record CommandStageEvent(
    long chainId,
    int depth,
    SourceLocation location,
    CommandSnippet command,
    Supplier<List<PauseSource>> pauseSources
) {
}
