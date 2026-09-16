package works.nuty.bastion.adapter;

/** Metadata handoff at ExecutionContext.queueNext, before a continuation is deferred. */
public interface TracedCommand {
    void bastion$inheritTrace(CommandTrace trace);
}
