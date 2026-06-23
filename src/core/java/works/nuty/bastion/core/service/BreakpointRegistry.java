package works.nuty.bastion.core.service;

import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.FunctionLocation;
import works.nuty.bastion.core.model.SourceLocation;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of active breakpoints. Replaces the two static {@code HashSet}s in the old god class
 * with a single thread-safe service (breakpoints are mutated from the command thread and read
 * from the command-execution thread).
 */
public final class BreakpointRegistry {
    private final Set<BlockLocation> blocks = ConcurrentHashMap.newKeySet();
    private final Set<FunctionLocation> functions = ConcurrentHashMap.newKeySet();

    /** Toggles a block breakpoint; returns {@code true} if it was added, {@code false} if removed. */
    public boolean toggleBlock(BlockLocation block) {
        return toggle(blocks, block);
    }

    /** Toggles a function breakpoint; returns {@code true} if it was added, {@code false} if removed. */
    public boolean toggleFunction(FunctionLocation location) {
        return toggle(functions, location);
    }

    private static <T> boolean toggle(Set<T> set, T value) {
        if (set.remove(value)) {
            return false;
        }
        set.add(value);
        return true;
    }

    /** Whether the given execution location currently sits on a breakpoint. */
    public boolean matches(SourceLocation location) {
        return switch (location) {
            case SourceLocation.Block b -> blocks.contains(b.block());
            case SourceLocation.Function f -> functions.contains(f.location());
            case SourceLocation.Player p -> false;
        };
    }

    public boolean isEmpty() {
        return blocks.isEmpty() && functions.isEmpty();
    }

    public int size() {
        return blocks.size() + functions.size();
    }

    public void clear() {
        blocks.clear();
        functions.clear();
    }

    /** An immutable snapshot of the block breakpoints (e.g. for syncing/listing). */
    public Set<BlockLocation> blocks() {
        return Set.copyOf(blocks);
    }

    /** An immutable snapshot of the function breakpoints (e.g. for syncing/listing). */
    public Set<FunctionLocation> functions() {
        return Set.copyOf(functions);
    }
}
