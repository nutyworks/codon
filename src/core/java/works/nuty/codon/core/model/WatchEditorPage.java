package works.nuty.codon.core.model;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One bounded page of selectable Watch-editor values, with an optional draft preview. */
public record WatchEditorPage(WatchResult.Status status, List<Option> options, int offset, boolean hasMore,
                              @Nullable WatchResult preview) {
    public static final int PAGE_SIZE = 32;
    public static final int MAX_VALUE_LENGTH = 512;
    public static final int MAX_LABEL_LENGTH = 256;
    public static final int MAX_DETAIL_LENGTH = 256;

    public record Option(String value, String label, String detail, boolean expandable) {
        public Option {
            value = bounded(value, MAX_VALUE_LENGTH, "value");
            label = bounded(label, MAX_LABEL_LENGTH, "label");
            detail = bounded(detail, MAX_DETAIL_LENGTH, "detail");
        }
    }

    public WatchEditorPage {
        Objects.requireNonNull(status);
        options = List.copyOf(Objects.requireNonNull(options));
        if (options.size() > PAGE_SIZE || offset < 0) throw new IllegalArgumentException("invalid editor page");
    }

    public static WatchEditorPage absent(WatchResult.Status status) {
        return new WatchEditorPage(status, List.of(), 0, false, null);
    }

    private static String bounded(String value, int max, String field) {
        value = Objects.requireNonNull(value, field);
        if (value.length() > max || value.chars().anyMatch(c -> c < 32 || c == 127 || c == 167))
            throw new IllegalArgumentException("invalid editor option " + field);
        return value;
    }
}
