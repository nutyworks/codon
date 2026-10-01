package works.nuty.codon.adapter;

/** Vanilla may return a partial decoded container; automatic snapshots require a complete read. */
public interface StorageReadStatus {
    boolean codon$hadIncompleteStorageRead(String namespace);
}
