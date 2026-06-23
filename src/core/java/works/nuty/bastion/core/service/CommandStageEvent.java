package works.nuty.bastion.core.service;

import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.PauseSource;
import works.nuty.bastion.core.model.SourceLocation;

import java.util.List;
import java.util.function.Supplier;

/**
 * The driving input to {@link DebuggerEngine#onCommandStage}: one stage of command execution,
 * translated from Minecraft internals by the mixin adapter into pure core terms.
 *
 * <p>{@code pauseSources} is a {@link Supplier} because building the in-world visualization data
 * is only needed if this stage actually pauses; the engine invokes it lazily.
 */
public record CommandStageEvent(
    int depth,
    SourceLocation location,
    CommandSnippet command,
    Supplier<List<PauseSource>> pauseSources
) {
}
