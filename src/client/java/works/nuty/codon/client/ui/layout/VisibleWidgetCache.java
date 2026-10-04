package works.nuty.codon.client.ui.layout;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Keeps widget identity for visible controls without retaining historical targets. */
public final class VisibleWidgetCache<K, V> {
    private final Map<K, V> widgets = new HashMap<>();
    private final Set<K> used = new HashSet<>();

    public void begin() { used.clear(); }

    public V get(K key, Supplier<V> create) {
        used.add(key);
        return widgets.computeIfAbsent(key, ignored -> create.get());
    }

    public void end() { widgets.keySet().retainAll(used); }

    public int size() { return widgets.size(); }
}
