package works.nuty.codon.persistence;

import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reassembles one ordered definition transfer. Empty means either an incomplete or rejected
 * transfer; callers can use {@link #isActive()} to distinguish them. A rejected transfer is
 * discarded, so it can never become a partial replacement later.
 */
public final class WatchDefinitionTransfer {
    private long transferId;
    private int nextOffset;
    private List<WatchSpec> staged = List.of();
    private Set<WatchSpec> unique = Set.of();

    public Optional<List<WatchSpec>> accept(long transferId, int offset, boolean last, List<WatchSpec> definitions) {
        if (transferId <= 0 || offset < 0 || definitions == null || (!last && definitions.isEmpty())) return reject();
        if (offset == 0) {
            reset();
            this.transferId = transferId;
            this.unique = new HashSet<>();
        } else if (!isActive() || this.transferId != transferId || this.nextOffset != offset) {
            return reject();
        }
        try {
            List<WatchSpec> page = WatchDefinitions.validate(definitions);
            for (WatchSpec spec : page) if (!unique.add(spec)) return reject();
            if (!isActive()) throw new IllegalStateException("transfer was unexpectedly reset");
            if (!(staged instanceof ArrayList)) staged = new ArrayList<>();
            staged.addAll(page);
            nextOffset += page.size();
            if (!last) return Optional.empty();
            List<WatchSpec> complete = List.copyOf(staged);
            reset();
            return Optional.of(complete);
        } catch (IllegalArgumentException invalid) {
            return reject();
        }
    }

    public boolean isActive() { return transferId != 0; }

    public void reset() {
        transferId = 0;
        nextOffset = 0;
        staged = List.of();
        unique = Set.of();
    }

    private Optional<List<WatchSpec>> reject() {
        reset();
        return Optional.empty();
    }
}
