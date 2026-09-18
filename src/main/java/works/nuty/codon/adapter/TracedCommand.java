package works.nuty.codon.adapter;

/** Metadata handoff at ExecutionContext.queueNext, before a continuation is deferred. */
public interface TracedCommand {
    void codon$inheritTrace(CommandTrace trace);
}
