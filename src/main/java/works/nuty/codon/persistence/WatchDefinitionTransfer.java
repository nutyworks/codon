package works.nuty.codon.persistence;

import works.nuty.codon.core.model.WatchSpec;
import works.nuty.codon.core.model.WatchIdentity;
import works.nuty.codon.core.model.TransferBudget;

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
    private final TransferBudget budget;
    private long transferId;
    private int nextOffset;
    private List<WatchSpec> staged = List.of();
    private Set<WatchIdentity.Key> unique = Set.of();

    public WatchDefinitionTransfer() { this(System::nanoTime); }

    public WatchDefinitionTransfer(java.util.function.LongSupplier clock) {
        budget = new TransferBudget(TransferBudget.WATCH_DEFINITIONS, clock);
    }

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
            if (!budget.accept(page.size(), WatchDefinitions.toPageJson(page).length(), last)) return reject();
            for (WatchSpec spec : page) if (!unique.add(WatchIdentity.rawKey(spec))) return reject();
            if (!isActive()) return reject();
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

    public boolean isActive() {
        if (budget.expired()) reset();
        return transferId != 0;
    }

    public void reset() {
        budget.reset();
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
