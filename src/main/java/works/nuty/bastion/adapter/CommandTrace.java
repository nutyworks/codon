package works.nuty.bastion.adapter;

import org.jspecify.annotations.Nullable;
import works.nuty.bastion.core.model.SourceLocation;

import java.util.concurrent.atomic.AtomicLong;

/** One invocation, shared by its deferred continuations, never by cached function actions. */
public final class CommandTrace {
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final ThreadLocal<CommandTrace> CURRENT = new ThreadLocal<>();
    public final long id = NEXT_ID.getAndIncrement();
    public final SourceLocation location;

    public CommandTrace(SourceLocation location) { this.location = location; }

    public static @Nullable CommandTrace current() { return CURRENT.get(); }

    public static void setCurrent(@Nullable CommandTrace trace) {
        if (trace == null) CURRENT.remove(); else CURRENT.set(trace);
    }
}
