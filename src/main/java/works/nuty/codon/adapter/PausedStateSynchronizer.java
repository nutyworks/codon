package works.nuty.codon.adapter;

/** Publishes already-applied state without advancing simulation or processing gameplay tasks. */
public interface PausedStateSynchronizer {
    void codon$syncPausedState();
}
