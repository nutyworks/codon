package works.nuty.codon.client.state;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.FunctionSourceDocument;

/**
 * Client-side request and response state for the read-only datapack function browser.
 *
 * <p>Responses are accepted only for the current request and strictly contiguous pages. This
 * prevents a late packet from an earlier selection or pre-reload resource view from replacing the
 * current document.
 */
public final class ClientFunctionSourceState {
    public enum Status { IDLE, LOADING, READY, NOT_FOUND, UNAUTHORIZED, ERROR }

    /** Read-only browser presentation retained while its screen is closed or rebuilt for resize. */
    public record BrowseView(int treeOffset, int lineOffset, int selectedLine, int selectedStageIndex,
                             int stageScrollOffset, int horizontalOffset) {
        public BrowseView {
            if (treeOffset < 0 || lineOffset < 0 || selectedLine < -1 || selectedStageIndex < -1
                || stageScrollOffset < 0 || horizontalOffset < 0)
                throw new IllegalArgumentException("invalid source browser view");
        }

        public BrowseView(int treeOffset, int lineOffset, int selectedLine, int selectedStageIndex, int stageScrollOffset) {
            this(treeOffset, lineOffset, selectedLine, selectedStageIndex, stageScrollOffset, 0);
        }
    }

    /** Minecraft-free source browser geometry for direct GUI-scale regression tests. */
    public record ScreenLayout(int panelWidth, int panelHeight, int treeWidth, boolean drawerMode) {
        public static ScreenLayout forScreen(int width, int height) {
            int panelWidth = Math.max(1, Math.min(760, width - 12));
            int panelHeight = Math.max(1, Math.min(440, height - 12));
            // 480×270 still leaves too little horizontal room for a readable source plus tree.
            boolean drawerMode = panelWidth < 560;
            int treeWidth = drawerMode ? panelWidth : Math.max(150, Math.min(270, panelWidth * 35 / 100));
            return new ScreenLayout(panelWidth, panelHeight, treeWidth, drawerMode);
        }
    }

    /** Minecraft-free decoded list response supplied by the client networking adapter. */
    public record ListPage(long requestId, Status status, int offset, boolean last, List<FunctionId> functions) {
        public ListPage { functions = List.copyOf(functions); }
    }

    /** Minecraft-free decoded source response supplied by the client networking adapter. */
    public record SourcePage(long requestId, Status status, FunctionId function, String provider, String revision,
                             boolean truncated, int offset, boolean last, List<String> lines) {
        public SourcePage { lines = List.copyOf(lines); }
    }

    public sealed interface Request permits Request.ListFunctions, Request.ReadFunction {
        long requestId();
        record ListFunctions(long requestId) implements Request { }
        record ReadFunction(long requestId, FunctionId function) implements Request {
            public ReadFunction { function = Objects.requireNonNull(function, "function"); }
        }
    }

    private long nextRequestId;
    private long listRequestId;
    private int expectedListOffset;
    private final List<FunctionId> pendingFunctions = new ArrayList<>();
    private List<FunctionId> functions = List.of();
    private Status listStatus = Status.IDLE;

    private long readRequestId;
    private int expectedReadOffset;
    private final List<String> pendingLines = new ArrayList<>();
    private @Nullable FunctionId selected;
    private @Nullable FunctionSourceDocument document;
    private @Nullable String pendingProvider;
    private @Nullable String pendingRevision;
    private boolean pendingTruncated;
    private Status sourceStatus = Status.IDLE;
    private final List<Request> outgoing = new ArrayList<>();
    private BrowseView browseView = new BrowseView(0, 0, -1, -1, 0);
    private final Map<FunctionId, BrowseView> functionViews = new HashMap<>();
    private final Deque<FunctionId> backStack = new ArrayDeque<>();

    /** Opens the browse flow and requests the current function list if none is in flight. */
    public void open() {
        if (listStatus != Status.LOADING) refreshList();
    }

    /** Requests a fresh effective function list, retaining old rows only until the new list arrives. */
    public void refreshList() {
        listRequestId = nextId();
        expectedListOffset = 0;
        pendingFunctions.clear();
        listStatus = Status.LOADING;
        outgoing.add(new Request.ListFunctions(listRequestId));
    }

    /** Selects a function and requests its current source revision. */
    public void select(FunctionId function) {
        function = Objects.requireNonNull(function, "function");
        if (!function.equals(selected)) {
            rememberCurrentView();
            browseView = functionViews.getOrDefault(function, new BrowseView(0, 0, -1, -1, 0));
        }
        selected = function;
        document = null;
        pendingLines.clear();
        pendingProvider = pendingRevision = null;
        pendingTruncated = false;
        expectedReadOffset = 0;
        readRequestId = nextId();
        sourceStatus = Status.LOADING;
        outgoing.add(new Request.ReadFunction(readRequestId, selected));
    }

    /** Opens a known source line without changing the debugger's live execution selection. */
    public void selectAt(FunctionLocation location) {
        Objects.requireNonNull(location, "location");
        if (location.line() < 1) throw new IllegalArgumentException("Function lines start at 1");
        select(location.function());
        browseView = new BrowseView(browseView.treeOffset(), location.line() - 1,
            location.line(), -1, 0);
        functionViews.put(location.function(), browseView);
    }

    /** Re-reads the selected function after a datapack reload or a recoverable failure. */
    public void refreshSource() {
        if (selected != null) select(selected);
    }

    public List<FunctionId> functions() { return functions; }
    public Status listStatus() { return listStatus; }
    public @Nullable FunctionId selected() { return selected; }
    public @Nullable FunctionSourceDocument document() { return document; }
    public Status sourceStatus() { return sourceStatus; }
    public BrowseView browseView() { return browseView; }

    /** Saves scroll and exact line/stage selection without changing the live source request. */
    public void rememberBrowseView(int treeOffset, int lineOffset, int selectedLine, int selectedStageIndex,
                                   int stageScrollOffset) {
        rememberBrowseView(treeOffset, lineOffset, selectedLine, selectedStageIndex, stageScrollOffset, browseView.horizontalOffset());
    }

    public void rememberBrowseView(int treeOffset, int lineOffset, int selectedLine, int selectedStageIndex,
                                   int stageScrollOffset, int horizontalOffset) {
        browseView = new BrowseView(treeOffset, lineOffset, selectedLine, selectedStageIndex, stageScrollOffset, horizontalOffset);
        if (selected != null) functionViews.put(selected, browseView);
    }

    /** Follows a validated function reference while retaining the caller's precise browse state. */
    public boolean follow(FunctionId function) {
        function = Objects.requireNonNull(function, "function");
        if (!functions.contains(function) || function.equals(selected)) return false;
        if (selected != null) backStack.push(selected);
        select(function);
        return true;
    }

    public boolean canGoBack() { return !backStack.isEmpty(); }

    /** Restores the caller function and its own saved scroll/line/stage state. */
    public boolean goBack() {
        if (backStack.isEmpty()) return false;
        select(backStack.pop());
        return true;
    }

    public List<Request> drainRequests() {
        List<Request> result = List.copyOf(outgoing);
        outgoing.clear();
        return result;
    }

    public void accept(ListPage page) {
        if (page.requestId() != listRequestId || listStatus != Status.LOADING) return;
        if (page.status() != Status.READY) {
            listStatus = page.status();
            pendingFunctions.clear();
            return;
        }
        if (page.offset() != expectedListOffset) return;
        pendingFunctions.addAll(page.functions());
        expectedListOffset += page.functions().size();
        if (!page.last()) return;

        functions = List.copyOf(pendingFunctions);
        pendingFunctions.clear();
        listStatus = Status.READY;
        if (selected != null && !functions.contains(selected)) clearSelection();
    }

    public void accept(SourcePage page) {
        if (page.requestId() != readRequestId || sourceStatus != Status.LOADING || !page.function().equals(selected)) return;
        if (page.status() != Status.READY) {
            sourceStatus = page.status();
            pendingLines.clear();
            return;
        }
        if (page.offset() != expectedReadOffset) return;
        if (pendingRevision == null) {
            pendingProvider = page.provider();
            pendingRevision = page.revision();
            pendingTruncated = page.truncated();
        } else if (!pendingProvider.equals(page.provider()) || !pendingRevision.equals(page.revision())
            || pendingTruncated != page.truncated()) {
            sourceStatus = Status.ERROR;
            pendingLines.clear();
            return;
        }
        pendingLines.addAll(page.lines());
        expectedReadOffset += page.lines().size();
        if (!page.last()) return;

        document = new FunctionSourceDocument(page.function(), pendingProvider, pendingRevision, pendingLines, pendingTruncated);
        pendingLines.clear();
        sourceStatus = Status.READY;
    }

    /** Cancels in-flight requests and drops server-specific browse data on disconnect. */
    public void reset() {
        listRequestId = readRequestId = 0;
        expectedListOffset = expectedReadOffset = 0;
        pendingFunctions.clear();
        pendingLines.clear();
        outgoing.clear();
        functions = List.of();
        listStatus = Status.IDLE;
        browseView = new BrowseView(0, 0, -1, -1, 0);
        functionViews.clear();
        backStack.clear();
        clearSelection();
    }

    private void clearSelection() {
        selected = null;
        document = null;
        pendingProvider = pendingRevision = null;
        pendingTruncated = false;
        sourceStatus = Status.IDLE;
    }

    private void rememberCurrentView() {
        if (selected != null) functionViews.put(selected, browseView);
    }

    private long nextId() {
        nextRequestId = nextRequestId == Long.MAX_VALUE ? 1 : nextRequestId + 1;
        return nextRequestId;
    }

}
