package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientFunctionSourceState;
import works.nuty.codon.client.CodonClientMod;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientStagePreviewState;
import works.nuty.codon.client.state.BreakpointTargetPolicy;
import works.nuty.codon.client.ui.layout.CommandFlowLayout;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.client.ui.layout.SourceSyntax;
import works.nuty.codon.client.ui.layout.SourceLineLayout;
import works.nuty.codon.client.ui.layout.SourceInteraction;
import works.nuty.codon.client.ui.layout.SourceReferences;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.FunctionSourceDocument;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.SourceLocation;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Read-only searchable tree and source view for all functions loaded by the current server. */
public final class FunctionSourceScreen extends ScaledCodonScreen {
    private static final int ROW_HEIGHT = 18;
    private final Screen parent;
    private final ClientFunctionSourceState sources;
    private final Set<String> collapsed = new HashSet<>();
    private final List<Entry> entries = new ArrayList<>();
    private int left, top, panelWidth, panelHeight, treeWidth;
    private boolean compactSourceControls;
    private boolean drawerMode;
    private boolean drawerOpen;
    private boolean docked;
    private boolean forwardingParentDrag;
    private boolean parentOwnsContextKeys;
    private int listOffset, lineOffset, horizontalOffset;
    private final ScrollbarInput scrollbars = new ScrollbarInput();
    private boolean resizingTree;
    private double treeGrab, horizontalRemainder;
    private int hoveredLine = -1, hoveredStage = -1, expandedWidth;
    private FunctionSourceDocument cachedDocument;
    private final List<SourceCodeLine> codeLines = new ArrayList<>();
    private List<SourceSyntax.Match> matches = List.of();
    private boolean matchesLimited;
    private final Map<Integer, List<SourceSyntax.Match>> matchesByLine = new HashMap<>();
    private int matchIndex = -1;
    private int widestLine;
    private int selectedLine = -1;
    private int selectedStageIndex = -1;
    private @org.jspecify.annotations.Nullable BreakpointTarget revealTarget;
    private @org.jspecify.annotations.Nullable BreakpointTarget focusedBreakpoint;
    private final List<StageHit> stageHits = new ArrayList<>();
    private final List<LineHit> lineHits = new ArrayList<>();
    private final List<FunctionHit> functionHits = new ArrayList<>();
    private EditBox search, sourceSearch;
    private DebuggerButton previousMatch, nextMatch;
    private DebuggerButton refresh, close, reread, drawerButton, backButton;


    private sealed interface Entry permits Entry.Group, Entry.Function {
        record Group(String key, String label, int depth) implements Entry { }
        record Function(FunctionId id, String label, int depth) implements Entry { }
    }
    private record StageHit(int x, int y, int width, int height, BreakpointTarget target, boolean control) {
        boolean contains(double px, double py) { return px >= x && px < x + width && py >= y && py < y + height; }
    }
    private record LineHit(int y, int height, int line) {
        boolean contains(double py) { return py >= y && py < y + height; }
    }
    private record InlineStage(int index, int start, int end, BreakpointTarget target) { }
    private record InlineRow(ClientStagePreviewState.Preview preview, List<InlineStage> stages) { }
    private final Map<Integer, InlineRow> inlineRows = new HashMap<>();
    private final Map<Integer, String> sourceFingerprints = new HashMap<>();
    private SourceLineLayout inlineLayout;
    private record FunctionHit(SourceInteraction.HitBox bounds, FunctionId function) {
        boolean contains(double px, double py) { return bounds.contains(px, py); }
    }
    private record StageCounts(int enabled, int obsolete) { }

    public FunctionSourceScreen(Screen parent, ClientFunctionSourceState sources) {
        super(Component.translatable("codon.source.title"), preferencesFor(parent));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    @Override protected void init() {
        parentOwnsContextKeys = false;
        if (parent instanceof CodonScreen codon) codon.cancelPanelResize();
        String searchValue = search == null ? "" : search.getValue();
        String sourceSearchValue = sourceSearch == null ? "" : sourceSearch.getValue();
        cachedDocument = null;
        clearSourceHits();
        scrollbars.release();
        resizingTree = forwardingParentDrag = false;
        horizontalRemainder = 0;
        clearWidgets();
        ClientFunctionSourceState.ScreenLayout layout = ClientFunctionSourceState.ScreenLayout.forScreen(width, height);
        panelWidth = layout.panelWidth();
        panelHeight = layout.panelHeight();
        docked = parent instanceof CodonScreen && width >= 640 && height >= 360;
        if (docked) panelHeight = Math.min(panelHeight, height - 106);
        left = (width - panelWidth) / 2;
        top = docked ? height - panelHeight - 6 : (height - panelHeight) / 2;
        drawerMode = layout.drawerMode();
        if (!drawerMode) drawerOpen = false;
        if (drawerMode && sources.selected() == null) drawerOpen = true;
        treeWidth = drawerMode ? (drawerOpen ? panelWidth : 0)
            : SourceInteraction.treeWidth(panelWidth, sources.treeWidth() == 0 ? layout.treeWidth() : sources.treeWidth());
        int sourceLeft = left + treeWidth + 8;
        int sourceWidth = panelWidth - treeWidth - 16;
        compactSourceControls = sourceWidth < 450 || drawerMode;
        search = addRenderableWidget(new DebuggerEditBox(font, left + 8, top + 29, Math.max(1, treeWidth - 16), 20,
            Component.translatable("codon.source.search")));
        search.setHint(Component.translatable("codon.source.search"));
        search.setMaxLength(128);
        search.setValue(searchValue);
        search.setResponder(ignored -> { listOffset = 0; rebuildEntries(); rememberView(); });
        refresh = addRenderableWidget(WatchUi.button(drawerMode ? left + 74 : left + treeWidth - 86, top + 5, 78, 18,
            Component.translatable("codon.source.refresh"), () -> { sources.refreshList(); listOffset = 0; }));
        refresh.setTooltip(Tooltip.create(Component.translatable("codon.source.refresh_hint")));
        reread = addRenderableWidget(WatchUi.button(drawerMode ? left + 74 : sourceLeft, top + 5, 84, 18,
            Component.translatable("codon.source.reload"), () -> {
                sources.refreshSource();
                lineOffset = 0;
                ClientDebuggerState debugger = CodonClientMod.state();
                if (debugger != null && sources.selected() != null && selectedLine > 0)
                    ClientNetworking.requestStagePreview(debugger, new SourceLocation.Function(
                        new FunctionLocation(sources.selected(), selectedLine)));
            }));
        reread.setTooltip(Tooltip.create(Component.translatable("codon.source.reload_hint")));
        close = addRenderableWidget(WatchUi.button(left + panelWidth - 58, top + 5, 50, 18,
            Component.translatable("codon.breakpoint.close"), this::onClose));
        drawerButton = addRenderableWidget(WatchUi.button(left + 8, top + 5, 64, 18,
            Component.translatable("codon.source.functions"), () -> setDrawerOpen(!drawerOpen)));
        backButton = addRenderableWidget(WatchUi.button(left + panelWidth - 116, top + 5, 54, 18,
            Component.translatable("codon.source.back"), this::goBack));
        reread.visible = reread.active = sources.selected() != null;
        drawerButton.visible = drawerButton.active = drawerMode;
        backButton.visible = backButton.active = !drawerOpen && sources.canGoBack();
        search.visible = search.active = !drawerMode || drawerOpen;
        int findY = top + ClientFunctionSourceState.ScreenLayout.findInset(compactSourceControls);
        sourceSearch = addRenderableWidget(new DebuggerEditBox(font, sourceLeft + 5, findY,
            Math.max(1, sourceWidth - 117), 20, Component.translatable("codon.source.find")));
        sourceSearch.setHint(Component.translatable("codon.source.find"));
        sourceSearch.setMaxLength(128);
        sourceSearch.setValue(sourceSearchValue);
        sourceSearch.setResponder(ignored -> { matchIndex = -1; rebuildMatches(); });
        previousMatch = addRenderableWidget(WatchUi.button(sourceLeft + sourceWidth - 41, findY, 18, 20,
            Component.literal("<"), () -> nextMatch(-1)));
        nextMatch = addRenderableWidget(WatchUi.button(sourceLeft + sourceWidth - 21, findY, 18, 20,
            Component.literal(">"), () -> nextMatch(1)));
        previousMatch.setTooltip(Tooltip.create(Component.translatable("codon.source.previous_match")));
        nextMatch.setTooltip(Tooltip.create(Component.translatable("codon.source.next_match")));
        setFocused(revealTarget != null ? null : search.visible ? search : null);
        ClientFunctionSourceState.BrowseView view = sources.browseView();
        listOffset = view.treeOffset();
        lineOffset = view.lineOffset();
        selectedLine = view.selectedLine();
        selectedStageIndex = view.selectedStageIndex();
        horizontalOffset = view.horizontalOffset();
        sources.open();
        if (sources.selected() != null && selectedLine > 0) {
            ClientDebuggerState debugger = CodonClientMod.state();
            if (debugger != null) {
                var location = new SourceLocation.Function(new FunctionLocation(sources.selected(), selectedLine));
                var preview = debugger.stagePreviews().get(location);
                if (preview == null || sources.document() != null && selectedLine <= sources.document().lines().size()
                    && debugger.stagePreviews().refreshNeeded(location, sources.document().lines().get(selectedLine - 1).trim()))
                    ClientNetworking.requestAutomaticStagePreview(debugger, location);
            }
        }
        rebuildEntries();
    }

    Screen parentScreen() { return parent; }

    FunctionSourceScreen revealBreakpoint(BreakpointTarget target) {
        revealTarget = target;
        return this;
    }

    private void revealBreakpoint() {
        if (revealTarget == null || sources.document() == null) return;
        BreakpointTarget target = revealTarget;
        if (!(target.location() instanceof SourceLocation.Function location)
            || !Objects.equals(sources.selected(), location.location().function())) { revealTarget = null; return; }
        int line = location.location().line();
        if (line < 1 || line > codeLines.size()) { revealTarget = null; return; }
        selectedLine = line;
        selectedStageIndex = -1;
        lineOffset = Math.clamp(line - 1, 0, maximumLineOffset());
        if (!target.wholeCommand()) {
            SourceCodeLine code = codeLines.get(line - 1);
            String command = code.source().trim();
            if (!target.commandFingerprint().equals(BreakpointTarget.fingerprint(command))) { revealTarget = null; return; }
            List<InlineStage> stages = stagesForLine(line, true);
            var debugger = CodonClientMod.state();
            if (debugger == null) { revealTarget = null; return; }
            var preview = debugger.stagePreviews().get(target.location());
            if (preview == null || preview.status() == ClientStagePreviewState.Status.LOADING) return;
            for (InlineStage stage : stages) if (stage.target().equals(target)) {
                selectedStageIndex = stage.index();
                inlineLayout = visibleLayout(code, stages, -1);
                horizontalOffset = Math.clamp(inlineLayout.x(stage.start()) - 24, 0, maxHorizontalOffset());
                break;
            }
        }
        focusedBreakpoint = target.wholeCommand() || selectedStageIndex == target.stageIndex() ? target : null;
        revealTarget = null;
        rememberView();
    }

    private void clearSourceHits() {
        stageHits.clear(); lineHits.clear(); functionHits.clear();
        hoveredLine = hoveredStage = -1;
    }

    boolean containsPanel(double x, double y) {
        return x >= left && x < left + panelWidth && y >= top && y < top + panelHeight;
    }

    private int sourceLeft() { return left + treeWidth + 8; }
    private int sourceWidth() { return panelWidth - treeWidth - 16; }
    private int codeRight() { return sourceLeft() + sourceWidth() - 12; }
    private int codeWidth() { return Math.max(1, sourceWidth() - gutterWidth() - 12); }
    private int lineMarkerX() { return sourceLeft() + 11; }
    /** The 18×18 gutter target; on a stopped line the amber `>` cue stays outside it, so clicking the cue only selects. */
    private boolean lineMarkerContains(double x, boolean stopped) {
        int left = stopped ? Math.max(lineMarkerX() - 8, sourceLeft() + 3 + font.width(">")) : lineMarkerX() - 8;
        return x >= left && x < lineMarkerX() + 10;
    }
    private int maximumLineOffset() { return sources.document() == null ? 0 : Math.max(0, codeLines.size() - sourceRows()); }

    private boolean splitterContains(double x, double y) {
        return !drawerMode && x >= left + treeWidth - 3 && x < left + treeWidth + 4
            && y >= top + 25 && y < top + panelHeight - 8;
    }

    private void resizeTree(double x) {
        treeWidth = SourceInteraction.treeWidth(panelWidth, (int) Math.round(x - left - treeGrab));
        sources.rememberTreeWidth(treeWidth);
        int sourceLeft = sourceLeft(), sourceWidth = sourceWidth();
        compactSourceControls = sourceWidth < 450;
        search.setWidth(treeWidth - 16);
        refresh.setX(left + treeWidth - 86); reread.setX(sourceLeft);
        int findY = top + ClientFunctionSourceState.ScreenLayout.findInset(compactSourceControls);
        sourceSearch.setX(sourceLeft + 5); sourceSearch.setY(findY); sourceSearch.setWidth(Math.max(1, sourceWidth - 117));
        previousMatch.setX(sourceLeft + sourceWidth - 41); previousMatch.setY(findY);
        nextMatch.setX(sourceLeft + sourceWidth - 21); nextMatch.setY(findY);
        horizontalOffset = Math.clamp(horizontalOffset, 0, maxHorizontalOffset());
        lineOffset = Math.clamp(lineOffset, 0, maximumLineOffset());
        clearSourceHits();
        rememberView();
    }

    private void rebuildEntries() {
        entries.clear();
        String needle = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<FunctionId> matches = sources.functions().stream()
            .filter(id -> needle.isEmpty() || id.toString().toLowerCase(Locale.ROOT).contains(needle))
            .sorted(Comparator.comparing(FunctionId::namespace).thenComparing(FunctionId::path)).toList();
        String namespace = null;
        Set<String> emittedFolders = new HashSet<>();
        for (FunctionId id : matches) {
            if (!id.namespace().equals(namespace)) {
                namespace = id.namespace();
                emittedFolders.clear();
                entries.add(new Entry.Group(namespace, namespace, 0));
            }
            if (!expanded(namespace, needle)) continue;
            String[] parts = id.path().split("/");
            String prefix = namespace;
            boolean hidden = false;
            for (int index = 0; index < parts.length - 1; index++) {
                prefix += "/" + parts[index];
                if (emittedFolders.add(prefix)) entries.add(new Entry.Group(prefix, parts[index], index + 1));
                if (!expanded(prefix, needle)) { hidden = true; break; }
            }
            if (!hidden) entries.add(new Entry.Function(id, parts[parts.length - 1], parts.length));
        }
        int clamped = Math.clamp(listOffset, 0, maximumListOffset());
        if (listOffset != clamped) { listOffset = clamped; rememberView(); }
    }

    private boolean expanded(String key, String needle) { return !needle.isEmpty() || !collapsed.contains(key); }
    private int visibleRows() { return Math.max(1, (panelHeight - 76) / ROW_HEIGHT); }
    private int maximumListOffset() { return Math.max(0, entries.size() - visibleRows()); }
    private int treeRowRight() { return left + treeWidth - (maximumListOffset() > 0 ? 12 : 5); }
    private int sourceLineTop() { return top + ClientFunctionSourceState.ScreenLayout.sourceInset(compactSourceControls); }
    private int sourceRows() { return ClientFunctionSourceState.ScreenLayout.sourceRows(panelHeight, compactSourceControls); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        stageHits.clear();
        lineHits.clear();
        functionHits.clear();
        scrollbars.beginFrame();
        rebuildEntries();
        refresh.visible = refresh.active = !drawerMode || drawerOpen;
        reread.visible = reread.active = !drawerOpen && sources.selected() != null && sources.sourceStatus() != ClientFunctionSourceState.Status.LOADING;
        drawerButton.visible = drawerButton.active = drawerMode;
        backButton.visible = backButton.active = !drawerOpen && sources.canGoBack();
        search.visible = search.active = !drawerMode || drawerOpen;
        updateCodeCache();
        revealBreakpoint();
        updateInlineLayout();
        sourceSearch.visible = sourceSearch.active = !drawerOpen && sources.document() != null;
        previousMatch.visible = nextMatch.visible = sourceSearch.visible;
        previousMatch.active = nextMatch.active = !matches.isEmpty();
        if (docked) {
            boolean covered = containsPanel(mouseX, mouseY) || ScreenLayers.get(this) != null;
            parent.extractRenderState(graphics, covered ? -1 : mouseX, covered ? -1 : mouseY, partialTick);
        }
        else graphics.fill(0, 0, width, height, DebuggerTheme.color(0x70000000));
        graphics.fill(left, top, left + panelWidth, top + panelHeight, WORKSPACE);
        graphics.outline(left, top, panelWidth, panelHeight, DIVIDER);
        if (!drawerMode) WatchUi.line(graphics, font, tr("codon.source.title"),
            left + 8, top + 9, Math.max(1, treeWidth - 102), TEXT);
        if (!drawerMode || drawerOpen) {
            if (!drawerMode) {
                boolean hovered = splitterContains(mouseX, mouseY);
                graphics.fill(left + treeWidth, top + 25, left + treeWidth + 1, top + panelHeight - 8,
                    hovered || resizingTree ? TEAL : DIVIDER);
                if (hovered) {
                    graphics.requestCursor(CursorTypes.RESIZE_EW);
                    graphics.setTooltipForNextFrame(font, Component.translatable("codon.source.resize_tree"), mouseX, mouseY);
                }
            }
            renderTree(graphics, mouseX, mouseY);
            if (!drawerOpen) renderSource(graphics, mouseX, mouseY);
        } else renderSource(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        scrollbars.endFrame();
    }

    private void renderTree(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int listTop = top + 54;
        int rows = visibleRows();
        int rowRight = treeRowRight();
        int reserved = maximumListOffset() > 0 ? 7 : 0;
        graphics.enableScissor(left + 4, listTop, rowRight, listTop + rows * ROW_HEIGHT);
        for (int row = 0; row < rows && listOffset + row < entries.size(); row++) {
            Entry entry = entries.get(listOffset + row);
            int y = listTop + row * ROW_HEIGHT;
            boolean selected = entry instanceof Entry.Function function && function.id().equals(sources.selected());
            boolean hovered = mouseX >= left + 5 && mouseX < rowRight && mouseY >= y && mouseY < y + ROW_HEIGHT;
            if (selected || hovered) graphics.fill(left + 5, y, rowRight, y + ROW_HEIGHT - 1,
                selected ? TEAL_SURFACE : ROW_HOVER);
            if (selected) graphics.fill(left + 5, y, left + 7, y + ROW_HEIGHT - 1, TEAL);
            switch (entry) {
                case Entry.Group group -> {
                    boolean open = expanded(group.key(), search.getValue());
                    WatchUi.line(graphics, font, (open ? "− " : "+ ") + group.label(), left + 9 + group.depth() * 10,
                        y + 5, treeWidth - 16 - reserved - group.depth() * 10, open ? TEXT : MUTED);
                }
                case Entry.Function function -> WatchUi.line(graphics, font, function.label(),
                    left + 18 + function.depth() * 10, y + 5, treeWidth - 26 - reserved - function.depth() * 10, TEXT);
            }
        }
        graphics.disableScissor();
        if (maximumListOffset() > 0) renderVerticalScrollbar(graphics, "functions", left + treeWidth - 10,
            listTop, rows * ROW_HEIGHT, listOffset, maximumListOffset(), rows,
            value -> { listOffset = value; rememberView(); });
        String footer = switch (sources.listStatus()) {
            case IDLE, LOADING -> tr("codon.source.loading_functions");
            case READY -> tr("codon.source.function_count", sources.functions().size());
            case UNAUTHORIZED -> tr("codon.source.owner_required");
            case ERROR -> tr("codon.source.load_failed");
            default -> "";
        };
        WatchUi.line(graphics, font, footer, left + 8, top + panelHeight - 20, treeWidth - 16,
            sources.listStatus() == ClientFunctionSourceState.Status.ERROR ? AMBER : MUTED);
    }

    private void renderSource(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int sourceLeft = left + treeWidth + 8;
        int sourceWidth = panelWidth - treeWidth - 16;
        FunctionId selected = sources.selected();
        if (selected == null) {
            WatchUi.line(graphics, font, tr("codon.source.select_function"),
                sourceLeft + 6, top + 48, sourceWidth - 12, MUTED);
            return;
        }
        String path = "data/" + selected.namespace() + "/function/" + selected.path() + ".mcfunction";
        FunctionSourceDocument document = sources.document();
        int pathY = top + ClientFunctionSourceState.ScreenLayout.pathInset(compactSourceControls);
        int statusY = top + ClientFunctionSourceState.ScreenLayout.statusInset(compactSourceControls);
        WatchUi.line(graphics, font, path, sourceLeft + 6, pathY, sourceWidth - 12, TEXT);
        if (mouseX >= sourceLeft + 6 && mouseX < sourceLeft + sourceWidth - 6 && mouseY >= pathY && mouseY < pathY + 10) {
            // Show the full path only when it is clipped; provider and revision are never drawn elsewhere.
            List<String> details = new ArrayList<>();
            if (WatchUi.clipped(font, path, sourceWidth - 12)) details.add(path);
            if (document != null) {
                String revision = document.revision().substring(0, Math.min(12, document.revision().length()));
                String origin = (document.provider().isEmpty() ? "" : document.provider() + " · ") + revision;
                if (!origin.isEmpty()) details.add(origin);
            }
            if (!details.isEmpty())
                graphics.setTooltipForNextFrame(font, font.split(Component.literal(String.join("\n", details)), Math.min(360, width - 24)), mouseX, mouseY);
        }
        if (document == null) {
            WatchUi.line(graphics, font, sourceStatus(), sourceLeft + 6, statusY, sourceWidth - 12,
                sources.sourceStatus() == ClientFunctionSourceState.Status.ERROR ? AMBER : MUTED);
            return;
        }
        // Keep incomplete-source warnings visible even though revision details are secondary.
        String status = (document.truncated() ? tr("codon.source.truncated") + " · " : "") + executionStatus(selected);
        WatchUi.line(graphics, font, status, sourceLeft + 6, statusY, sourceWidth - 12,
            document.truncated() ? AMBER : MUTED);
        int countX = sourceLeft + sourceWidth - 110;
        int countY = sourceSearch.getY() + 6;
        WatchUi.line(graphics, font, matches.isEmpty() ? "0/0"
            : (matchIndex + 1) + "/" + matches.size() + (matchesLimited ? "+" : ""), countX, countY, 66, MUTED);
        if (matchesLimited && mouseX >= countX && mouseX < countX + 66 && mouseY >= countY && mouseY < countY + 10)
            graphics.setTooltipForNextFrame(font, font.split(Component.translatable("codon.source.find_limit", SourceSyntax.MAX_MATCHES),
                Math.min(200, width - 24)), mouseX, mouseY);
        int gutter = gutterWidth();
        int codeLeft = sourceLeft + gutter, codeWidth = codeWidth();
        horizontalOffset = Math.clamp(horizontalOffset, 0, maxHorizontalOffset());
        int lineTop = sourceLineTop(), rows = sourceRows();
        if (selectedLine > document.lines().size()) {
            selectedLine = selectedStageIndex = -1;
            rememberView();
        }
        lineOffset = Math.clamp(lineOffset, 0, maximumLineOffset());
        int lineBottom = lineTop + rows * ROW_HEIGHT;
        ClientDebuggerState debugger = CodonClientMod.state();
        Map<Integer, StageCounts> stageCounts = stageBreakpointCounts(selected, debugger);
        int nextHoveredLine = -1, nextHoveredStage = -1;
        expandedWidth = widestLine;
        graphics.fill(sourceLeft + 3, lineTop, codeRight(), lineBottom, EDITOR);
        graphics.enableScissor(sourceLeft + 3, lineTop, codeRight(), lineBottom);
        for (int index = lineOffset, y = lineTop; index < document.lines().size() && y < lineBottom; index++, y += ROW_HEIGHT) {
            int line = index + 1;
            String number = Integer.toString(line);
            SourceLocation.Function location = new SourceLocation.Function(new FunctionLocation(selected, line));
            BreakpointDefinition definition = breakpoint(location);
            String command = codeLines.get(index).source().trim();
            boolean editingLine = BreakpointUi.editingMarker(this, BreakpointTarget.whole(location), command);
            if (editingLine) definition = BreakpointUi.editingDefinition(this, BreakpointTarget.whole(location), command);
            boolean hovered = ScreenLayers.get(this) == null && mouseX >= sourceLeft + 3 && mouseX < codeRight()
                && mouseY >= y && mouseY < y + ROW_HEIGHT;
            boolean stopped = isActualStop(selected, line), inspected = selectedLine == line;
            SourceCodeLine code = codeLines.get(index);
            StageCounts counts = stageCounts.getOrDefault(line, new StageCounts(0, 0));
            List<InlineStage> stages = stagesForLine(line, stopped || hovered || counts.enabled() > 0);
            if (stopped) {
                graphics.fill(codeLeft, y, codeRight(), y + ROW_HEIGHT - 1, DebuggerTheme.color(AMBER_SURFACE));
                graphics.text(font, ">", sourceLeft + 3, y + 5, DebuggerTheme.foreground(AMBER), false);
            }
            graphics.text(font, SourceCodeLine.plain(number), codeLeft - 6 - font.width(SourceCodeLine.plain(number)),
                y + 5, DebuggerTheme.foreground(stopped ? AMBER : inspected ? TEXT : MUTED), false);
            SourceLineLayout layout = visibleLayout(code, stages, hovered && hoveredLine == line ? hoveredStage : -1);
            int rowHoveredStage = -1;
            if (hovered && mouseX >= codeLeft) {
                for (InlineStage stage : stages) {
                    int start = codeLeft + layout.before(stage.start()) - horizontalOffset;
                    int end = codeLeft + layout.before(stage.end()) - horizontalOffset;
                    if (mouseX >= Math.max(codeLeft, start) && mouseX < Math.min(codeRight(), end)) {
                        rowHoveredStage = stage.index();
                        break;
                    }
                }
            }
            if (hovered) { nextHoveredLine = line; nextHoveredStage = rowHoveredStage; }
            layout = visibleLayout(code, stages, rowHoveredStage);
            expandedWidth = Math.max(expandedWidth, layout.width());
            if (inspected) inlineLayout = layout;
            graphics.enableScissor(codeLeft, y, codeRight(), y + ROW_HEIGHT);
            renderStageBackgrounds(graphics, stages, layout, line, codeLeft, y, codeWidth);
            renderSourceText(graphics, code, layout, line, codeLeft, y, codeWidth);
            renderInlineMarkers(graphics, stages, layout, line, rowHoveredStage, codeLeft, y, codeWidth, mouseX, mouseY);
            graphics.disableScissor();
            boolean enabled = definition != null && definition.enabled();
            if (SourceInteraction.markerVisible(enabled, hovered && wholeEligible(document, line),
                editingLine || BreakpointTarget.whole(location).equals(focusedBreakpoint))) {
                BreakpointUi.icon(definition).drawSmall(graphics, lineMarkerX(), y + 5,
                    DebuggerTheme.foreground(enabled ? RED : MUTED));
            } else if (counts.enabled() > 0 && stages.isEmpty()) {
                // Acknowledged stages stay visible in the gutter while their server preview loads.
                DebuggerIcon.BREAKPOINT.drawSmall(graphics, lineMarkerX(), y + 5, DebuggerTheme.foreground(RED));
            }
            if (counts.obsolete() > 0) {
                // A changed fingerprint has no valid marker in the new command. Keep a
                // separate review warning; never disguise it as a current stage control.
                graphics.text(font, "!", sourceLeft + 21, y + 5, DebuggerTheme.foreground(AMBER), false);
                if (hovered && mouseX >= sourceLeft + 21 && mouseX < sourceLeft + 26)
                    graphics.setTooltipForNextFrame(font, Component.translatable("codon.breakpoint.error.stale_source"), mouseX, mouseY);
            }
            if (hovered && lineMarkerContains(mouseX, stopped) && wholeEligible(document, line)) {
                var hint = Component.translatable("codon.source.line_breakpoint_hint",
                    definition == null ? tr("codon.source.no_breakpoint") : BreakpointUi.condition(definition.condition()));
                if (counts.enabled() > 0)
                    hint.append("\n").append(Component.translatable("codon.source.stage_breakpoints", counts.enabled()));
                graphics.setTooltipForNextFrame(font, hint, mouseX, mouseY);
            }
            lineHits.add(new LineHit(y, ROW_HEIGHT, line));
        }
        hoveredLine = nextHoveredLine; hoveredStage = nextHoveredStage;
        graphics.disableScissor();
        renderHorizontalScrollbar(graphics, sourceLeft, sourceWidth);
        renderVerticalScrollbar(graphics, "source", sourceLeft + sourceWidth - 7, lineTop, rows * ROW_HEIGHT,
            lineOffset, maximumLineOffset(), rows, value -> { lineOffset = value; clearSourceHits(); rememberView(); });
    }

    private Map<Integer, StageCounts> stageBreakpointCounts(FunctionId function, ClientDebuggerState debugger) {
        Map<Integer, StageCounts> counts = new HashMap<>();
        if (debugger == null) return counts;
        for (BreakpointDefinition definition : debugger.breakpoints().definitions()) {
            BreakpointTarget target = definition.target();
            if (target.stageIndex() >= 0 && target.location() instanceof SourceLocation.Function location
                && location.location().function().equals(function)) {
                int line = location.location().line();
                if (line < 1 || line > codeLines.size()) continue;
                String fingerprint = sourceFingerprints.computeIfAbsent(line,
                    key -> BreakpointTarget.fingerprint(codeLines.get(key - 1).source().trim()));
                boolean obsolete = SourceInteraction.stageNeedsReview(definition, fingerprint);
                if (!obsolete && !definition.enabled()) continue;
                counts.merge(line, new StageCounts(obsolete ? 0 : 1, obsolete ? 1 : 0),
                    (a, b) -> new StageCounts(a.enabled() + b.enabled(), a.obsolete() + b.obsolete()));
            }
        }
        return counts;
    }

    private void updateInlineLayout() {
        SourceCodeLine code = selectedLine >= 1 && selectedLine <= codeLines.size() ? codeLines.get(selectedLine - 1) : null;
        inlineLayout = code == null ? null : visibleLayout(code, stagesForLine(selectedLine, false),
            hoveredLine == selectedLine ? hoveredStage : -1);
    }

    /** Only server-confirmed offsets may create an inline control, including on unselected rows. */
    private List<InlineStage> stagesForLine(int line, boolean requestIfMissing) {
        FunctionSourceDocument document = sources.document();
        ClientDebuggerState state = CodonClientMod.state();
        if (state == null || document == null || sources.selected() == null || !stageEligible(document, line)) return List.of();
        SourceCodeLine code = codeLines.get(line - 1);
        var location = new SourceLocation.Function(new FunctionLocation(sources.selected(), line));
        var preview = state.stagePreviews().get(location);
        InlineRow cached = inlineRows.get(line);
        // Refreshing the condition preview must not remove its already-confirmed
        // marker slot. Reuse offsets only for the same document command and exact editor target.
        if (preview != null && preview.status() == ClientStagePreviewState.Status.LOADING && cached != null
            && previewMatchesLine(document, line, cached.preview())
            && cached.stages().stream().anyMatch(stage -> BreakpointUi.editingMarker(this, stage.target(), code.source().trim())))
            return cached.stages();
        if (requestIfMissing && state.stagePreviews().refreshNeeded(location, document.lines().get(line - 1).trim())) {
            ClientNetworking.requestAutomaticStagePreview(state, location);
            preview = state.stagePreviews().get(location);
        }
        if (cached != null && cached.preview() == preview) return cached.stages();
        List<InlineStage> stages = new ArrayList<>();
        if (previewMatchesLine(document, line, preview) && preview.spans().size() > 1) {
            int leading = code.source().indexOf(preview.savedCommand());
            int prefixEnd = CommandFlowLayout.executePrefixEnd(preview.savedCommand()), previousEnd = 0;
            for (var span : preview.spans()) {
                if (span.start() < previousEnd || span.end() > preview.savedCommand().length() || span.start() >= span.end()) {
                    stages.clear(); break;
                }
                int start = Math.max(prefixEnd, span.start());
                while (start < span.end() && Character.isWhitespace(preview.savedCommand().charAt(start))) start++;
                previousEnd = span.end();
                if (start < span.end()) stages.add(new InlineStage(span.index(), leading + start, leading + span.end(),
                    BreakpointTarget.stage(location, span.index(), preview.savedCommand())));
            }
        }
        List<InlineStage> result = List.copyOf(stages);
        inlineRows.put(line, new InlineRow(preview, result));
        return result;
    }

    private boolean stageEnabled(InlineStage stage) {
        var definition = CodonClientMod.state().breakpoints().get(stage.target());
        return definition != null && definition.enabled();
    }

    private SourceLineLayout visibleLayout(SourceCodeLine code, List<InlineStage> stages, int hover) {
        return new SourceLineLayout(code.source().length(), stages.stream()
            .filter(stage -> SourceInteraction.markerVisible(stageEnabled(stage), stage.index() == hover,
                stage.target().equals(focusedBreakpoint) || BreakpointUi.editingMarker(this, stage.target(), code.source().trim())))
            .map(InlineStage::start).toList(), code::x);
    }

    private void renderStageBackgrounds(GuiGraphicsExtractor graphics, List<InlineStage> stages, SourceLineLayout layout,
                                        int line, int x, int y, int width) {
        for (InlineStage stage : stages) {
            boolean stopped = isActualStageStop(stage, line);
            if (!stopped) continue;
            int start = x + layout.x(stage.start()) - horizontalOffset;
            int end = x + layout.before(stage.end()) - horizontalOffset;
            int tint = (AMBER & 0xffffff) | 0x70000000;
            if (end > x && start < x + width)
                // Only the authoritative pause adds a stage background.
                graphics.fill(Math.max(x, start), y + 3, Math.min(x + width, end), y + 15,
                    DebuggerTheme.color(tint));
        }
    }

    private boolean isActualStageStop(InlineStage stage, int line) {
        ClientDebuggerState state = CodonClientMod.state();
        if (!isActualStop(sources.selected(), line) || state.snapshot().reason() == PauseReason.EXECUTION_COMPLETE
            || state.snapshot().callStack().isEmpty()) return false;
        var frame = state.snapshot().callStack().getFirst();
        return frame.location().equals(stage.target().location()) && frame.flowStageIndex() == stage.index()
            && frame.command().text().equals(codeLines.get(line - 1).source().trim());
    }

    private void renderInlineMarkers(GuiGraphicsExtractor graphics, List<InlineStage> stages, SourceLineLayout layout,
                                     int line, int hover, int x, int y, int width, int mouseX, int mouseY) {
        ClientDebuggerState state = CodonClientMod.state();
        if (state == null) return;
        for (InlineStage stage : stages) {
            int markerX = x + layout.before(stage.start()) - horizontalOffset;
            int start = x + layout.x(stage.start()) - horizontalOffset;
            int end = x + layout.before(stage.end()) - horizontalOffset;
            BreakpointDefinition definition = state.breakpoints().get(stage.target());
            boolean enabled = definition != null && definition.enabled(), hovered = hover == stage.index();
            if (SourceInteraction.markerVisible(enabled, hovered,
                stage.target().equals(focusedBreakpoint)
                    || BreakpointUi.editingMarker(this, stage.target(), codeLines.get(line - 1).source().trim()))) {
                DebuggerIcon icon = BreakpointUi.icon(definition);
                icon.drawSmall(graphics, markerX + (SourceLineLayout.MARKER_WIDTH - icon.smallSize()) / 2,
                    y + 5, DebuggerTheme.foreground(enabled ? RED : MUTED));
                addStageHit(markerX, y, start, x, width, stage.target(), true);
            }
            addStageHit(start, y, end, x, width, stage.target(), false);
            if (hovered && mouseX < start)
                graphics.setTooltipForNextFrame(font, Component.translatable("codon.breakpoint.stage_target", stage.index() + 1)
                    .append(" · " + (definition == null ? tr("codon.source.no_breakpoint") : BreakpointUi.condition(definition.condition()))), mouseX, mouseY);
        }
    }

    private void addStageHit(int start, int y, int end, int viewportX, int width, BreakpointTarget target, boolean control) {
        int from = Math.max(start, viewportX), to = Math.min(end, viewportX + width);
        if (to > from) stageHits.add(new StageHit(from, y, to - from, ROW_HEIGHT, target, control));
    }

    private static boolean previewMatchesLine(FunctionSourceDocument document, int line,
                                               ClientStagePreviewState.@org.jspecify.annotations.Nullable Preview preview) {
        return preview != null && preview.status() == ClientStagePreviewState.Status.READY
            && line >= 1 && line <= document.lines().size()
            && preview.savedCommand().equals(document.lines().get(line - 1).trim());
    }

    private int gutterWidth() {
        return 32 + font.width(Integer.toString(sources.document() == null ? 1 : sources.document().lines().size()));
    }

    private int displayedWidth() { return Math.max(Math.max(widestLine, expandedWidth), inlineLayout == null ? 0 : inlineLayout.width()); }
    private int maxHorizontalOffset() { return Math.max(0, displayedWidth() - codeWidth()); }
    private int horizontalTrackY() { return top + ClientFunctionSourceState.ScreenLayout.scrollbarInset(panelHeight); }

    private void updateCodeCache() {
        FunctionSourceDocument document = sources.document();
        if (cachedDocument == document) return;
        cachedDocument = document;
        codeLines.clear();
        inlineRows.clear();
        sourceFingerprints.clear();
        expandedWidth = widestLine = 0;
        Map<Integer, Float> glyphWidths = new HashMap<>();
        if (document != null) for (String line : document.lines()) {
            SourceCodeLine code = new SourceCodeLine(line, font, glyphWidths);
            codeLines.add(code);
            widestLine = Math.max(widestLine, code.width());
        }
        rebuildMatches();
    }

    private void rebuildMatches() {
        SourceSyntax.Match previous = matchIndex < 0 || matchIndex >= matches.size() ? null : matches.get(matchIndex);
        SourceSyntax.SearchResults results = sources.document() == null || sourceSearch == null
            ? new SourceSyntax.SearchResults(List.of(), false)
            : SourceSyntax.find(sources.document().lines(), sourceSearch.getValue());
        matches = results.matches();
        matchesLimited = results.hasMore();
        // The key list lives in Help; only the cap on listed matches is not visible in the field.
        if (sourceSearch != null) sourceSearch.setTooltip(matchesLimited
            ? Tooltip.create(Component.translatable("codon.source.find_limit", SourceSyntax.MAX_MATCHES)) : null);
        matchesByLine.clear();
        for (SourceSyntax.Match match : matches) matchesByLine.computeIfAbsent(match.line(), ignored -> new ArrayList<>()).add(match);
        matchIndex = previous == null ? -1 : matches.indexOf(previous);
        if (matchIndex < 0 && !matches.isEmpty()) {
            matchIndex = 0;
            revealMatch();
        }
    }

    private void nextMatch(int direction) {
        if (matches.isEmpty()) return;
        matchIndex = matchIndex < 0 ? (direction > 0 ? 0 : matches.size() - 1)
            : Math.floorMod(matchIndex + direction, matches.size());
        revealMatch();
    }

    private void revealMatch() {
        SourceSyntax.Match match = matches.get(matchIndex);
        selectLine(match.line());
        lineOffset = match.line() - 1;
        if (match.line() <= codeLines.size()) {
            updateInlineLayout();
            SourceCodeLine code = codeLines.get(match.line() - 1);
            int start = inlineLayout == null ? code.x(match.start()) : inlineLayout.x(match.start());
            int end = inlineLayout == null ? code.x(match.end()) : inlineLayout.before(match.end());
            int visible = codeWidth();
            if (start < horizontalOffset || end > horizontalOffset + visible)
                horizontalOffset = Math.clamp(start - 10, 0, maxHorizontalOffset());
        }
        rememberView();
    }

    private void renderSourceText(GuiGraphicsExtractor graphics, SourceCodeLine code, SourceLineLayout layout, int line,
                                  int x, int rowY, int width) {
        int y = rowY + 5;
        SourceSyntax.Match currentMatch = matchIndex < 0 ? null : matches.get(matchIndex);
        for (SourceLineLayout.Segment segment : layout.segments()) {
            for (SourceSyntax.Match match : matchesByLine.getOrDefault(line, List.of())) {
                int from = Math.max(match.start(), segment.start()), to = Math.min(match.end(), segment.end());
                if (from >= to) continue;
                int start = x + layout.x(from) - horizontalOffset;
                int end = x + layout.before(to) - horizontalOffset;
                if (end <= x || start >= x + width) continue;
                graphics.fill(Math.max(x, start), y - 2, Math.min(x + width, end), y + 10, DebuggerTheme.color(AMBER_SURFACE));
                if (match.equals(currentMatch)) graphics.outline(Math.max(x, start), y - 2,
                    Math.max(1, Math.min(x + width, end) - Math.max(x, start)), 12, DebuggerTheme.color(AMBER));
            }
            SourceCodeLine.Slice slice = code.slice(horizontalOffset - segment.inset(), width, segment.start(), segment.end());
            graphics.text(font, slice.text(), x + slice.x(), y, DebuggerTheme.foreground(TEXT), false);
        }
        List<SourceSyntax.Span> spans = code.spans();
        for (int i = 0; i + 1 < spans.size(); i++) {
            SourceSyntax.Span span = spans.get(i);
            boolean functionCommand = span.kind() == SourceSyntax.Kind.COMMAND;
            boolean functionCondition = i > 0 && spans.get(i - 1).kind() == SourceSyntax.Kind.KEYWORD
                && Set.of("if", "unless").contains(code.source().substring(spans.get(i - 1).start(), spans.get(i - 1).end()));
            if ((!functionCommand && !functionCondition) || !code.source().substring(span.start(), span.end()).equals("function")) continue;
            SourceSyntax.Span reference = spans.get(i + 1);
            String token = code.source().substring(reference.start(), reference.end());
            FunctionId called = SourceReferences.loadedFunction(token, sources.functions());
            if (called == null) continue;
            int start = x + layout.x(reference.start()) - horizontalOffset;
            int end = x + layout.before(reference.end()) - horizontalOffset;
            int visibleStart = Math.max(x, start), visibleEnd = Math.min(x + width, end);
            if (visibleEnd > visibleStart) {
                graphics.fill(visibleStart, y + 9, visibleEnd, y + 10, DebuggerTheme.color(TEAL));
                var bounds = SourceInteraction.clippedRowHit(visibleStart, visibleEnd, rowY, ROW_HEIGHT,
                    x, sourceLineTop(), width, sourceRows() * ROW_HEIGHT);
                if (bounds != null) functionHits.add(new FunctionHit(bounds, called));
            }
        }
    }

    private void renderHorizontalScrollbar(GuiGraphicsExtractor graphics, int sourceLeft, int sourceWidth) {
        int x = sourceLeft + gutterWidth(), width = codeWidth(), y = horizontalTrackY();
        int maximum = maxHorizontalOffset();
        int thumb = Math.min(width, Math.max(18, width * width / Math.max(width, displayedWidth())));
        int offset = maximum == 0 ? 0 : horizontalOffset * (width - thumb) / maximum;
        graphics.fill(x, y, x + width, y + 6, ROW_HOVER);
        graphics.fill(x + offset, y, x + offset + thumb, y + 6, maximum == 0 ? DIVIDER : SCROLLBAR);
        scrollbars.add("horizontal", true, x, y, width, 6, thumb, horizontalOffset, maximum,
            value -> { horizontalOffset = value; horizontalRemainder = 0; clearSourceHits(); rememberView(); });
    }

    private void renderVerticalScrollbar(GuiGraphicsExtractor graphics, String id, int x, int y, int height,
                                         int offset, int maximum, int rows, java.util.function.IntConsumer setter) {
        int thumb = Math.min(height, Math.max(12, height * rows / Math.max(rows, rows + maximum)));
        int at = maximum == 0 ? 0 : offset * (height - thumb) / maximum;
        graphics.fill(x, y, x + 4, y + height, ROW_HOVER);
        graphics.fill(x, y + at, x + 4, y + at + thumb, maximum == 0 ? DIVIDER : SCROLLBAR);
        scrollbars.add(id, false, x, y, height, 4, thumb, offset, maximum, setter);
    }

    private BreakpointDefinition breakpoint(SourceLocation.Function location) {
        ClientDebuggerState state = CodonClientMod.state();
        if (state == null) return null;
        return state.breakpoints().get(BreakpointTarget.whole(location));
    }

    private String sourceStatus() {
        return switch (sources.sourceStatus()) {
            case LOADING, IDLE -> tr("codon.source.loading_source");
            case NOT_FOUND -> tr("codon.source.not_found");
            case UNAUTHORIZED -> tr("codon.source.owner_required");
            case ERROR -> tr("codon.source.read_failed");
            case READY -> "";
        };
    }

    private boolean isActualStop(FunctionId function, int line) {
        ClientDebuggerState state = CodonClientMod.state();
        if (state == null || !state.isPaused() || state.snapshot() == null) return false;
        return state.snapshot().location() instanceof SourceLocation.Function location
            && location.location().function().equals(function) && location.location().line() == line;
    }

    private String executionStatus(FunctionId function) {
        ClientDebuggerState state = CodonClientMod.state();
        if (state == null || state.inspectionSnapshot() == null) return tr("codon.source.record_unavailable");
        if (state.inspectionSnapshot().location() instanceof SourceLocation.Function location
            && location.location().function().equals(function)) return tr(state.isPaused() ? "codon.source.actual_stop"
                : "codon.source.record_line", location.location().line());
        boolean inStack = state.inspectionSnapshot().callStack().stream().anyMatch(frame ->
            frame.location() instanceof SourceLocation.Function location && location.location().function().equals(function));
        return inStack ? tr("codon.source.record_in_stack") : tr("codon.source.record_unavailable");
    }

    private boolean stageEligible(FunctionSourceDocument document, int line) {
        if (line < 1 || line > document.lines().size()) return false;
        String source = document.lines().get(line - 1).stripLeading();
        return !source.isEmpty() && !source.startsWith("#") && !source.startsWith("$");
    }

    private boolean wholeEligible(FunctionSourceDocument document, int line) {
        if (line < 1 || line > document.lines().size()) return false;
        String source = document.lines().get(line - 1).stripLeading();
        return !source.isEmpty() && !source.startsWith("#");
    }

    private @org.jspecify.annotations.Nullable BreakpointTarget selectedStageTarget() {
        if (selectedLine < 1 || selectedStageIndex < 0 || sources.selected() == null) return null;
        ClientDebuggerState state = CodonClientMod.state();
        if (state == null) return null;
        SourceLocation.Function location = new SourceLocation.Function(new FunctionLocation(sources.selected(), selectedLine));
        ClientStagePreviewState.Preview preview = state.stagePreviews().get(location);
        FunctionSourceDocument document = sources.document();
        if (document == null || !previewMatchesLine(document, selectedLine, preview) || preview.spans().size() <= 1
            || preview.spans().stream().noneMatch(span -> span.index() == selectedStageIndex)) return null;
        return BreakpointTarget.stage(location, selectedStageIndex, preview.savedCommand());
    }

    private @org.jspecify.annotations.Nullable BreakpointTarget selectedLineTarget() {
        FunctionSourceDocument document = sources.document();
        if (document == null || sources.selected() == null || selectedLine < 1 || selectedLine > document.lines().size()) return null;
        if (!wholeEligible(document, selectedLine)) return null;
        return BreakpointTarget.whole(new SourceLocation.Function(new FunctionLocation(sources.selected(), selectedLine)));
    }

    private void openCondition(BreakpointTarget target, Bounds anchor, boolean direct) {
        ClientDebuggerState debugger = CodonClientMod.state();
        FunctionSourceDocument document = sources.document();
        if (debugger == null || document == null || !(target.location() instanceof SourceLocation.Function location)) return;
        int line = location.location().line();
        if (!wholeEligible(document, line)) return;
        stagesForLine(line, true);
        var level = minecraft.level;
        scrollbars.release();
        resizingTree = forwardingParentDrag = false;
        if (parent instanceof CodonScreen codon) codon.cancelPanelResize();
        java.util.function.BooleanSupplier current = () -> minecraft.level == level && sources.document() == document
                && sources.sourceStatus() != ClientFunctionSourceState.Status.LOADING
                && Objects.equals(sources.selected(), location.location().function())
                && (target.wholeCommand() || matchingStageContext(debugger, document, line, target));
        if (direct) {
            if (!current.getAsBoolean()) return;
            focusedBreakpoint = target;
            setFocused(null);
            BreakpointContextMenu.openEditor(this, debugger, target, anchor, current, () -> focusedBreakpoint = target);
        } else BreakpointContextMenu.open(this, debugger, target, anchor, current, () -> { });
    }

    private boolean matchingStageContext(ClientDebuggerState debugger, FunctionSourceDocument document,
                                         int line, BreakpointTarget target) {
        var preview = debugger.stagePreviews().get(target.location());
        // The condition editor may refresh its own parse preview. Keep the exact document
        // target during that request; dismiss if the acknowledged command/stage changed.
        return preview != null && (preview.status() == ClientStagePreviewState.Status.LOADING
            || previewMatchesLine(document, line, preview) && preview.spans().stream()
                .anyMatch(span -> span.index() == target.stageIndex()));
    }

    private void rememberView() {
        sources.rememberBrowseView(listOffset, lineOffset, selectedLine, selectedStageIndex, 0, horizontalOffset);
    }

    private void setDrawerOpen(boolean open) {
        if (!drawerMode || drawerOpen == open) return;
        drawerOpen = open;
        init();
    }

    private void goBack() {
        if (!sources.goBack()) return;
        restoreBrowseView();
        if (drawerMode) setDrawerOpen(false);
    }

    private void restoreBrowseView() {
        focusedBreakpoint = null;
        ClientFunctionSourceState.BrowseView view = sources.browseView();
        listOffset = view.treeOffset();
        lineOffset = view.lineOffset();
        selectedLine = view.selectedLine();
        selectedStageIndex = view.selectedStageIndex();
        horizontalOffset = view.horizontalOffset();
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (ScreenLayers.get(this) != null) return true;
        if (!containsPanel(event.x(), event.y())) {
            if (!docked) return false;
            forwardingParentDrag = parent.mouseClicked(event, doubleClick);
            if (forwardingParentDrag) { parentOwnsContextKeys = true; setFocused(null); }
            return forwardingParentDrag;
        }
        parentOwnsContextKeys = false;
        focusedBreakpoint = null;
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (splitterContains(event.x(), event.y())) {
                resizingTree = true;
                treeGrab = event.x() - left - treeWidth;
                scrollbars.release();
                setFocused(null);
                return true;
            }
            if (scrollbars.click(event.x(), event.y())) { setFocused(null); return true; }
        }
        ClientDebuggerState debugger = CodonClientMod.state();
        for (FunctionHit hit : functionHits) {
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && hit.contains(event.x(), event.y()) && sources.follow(hit.function())) {
                restoreBrowseView();
                rememberView();
                return true;
            }
        }
        for (StageHit hit : stageHits) {
            if (!hit.contains(event.x(), event.y()) || debugger == null) continue;
            if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                openCondition(hit.target(), new Bounds(hit.x(), hit.y(), hit.width(), hit.height()), hit.control());
                return true;
            }
            BreakpointDefinition definition = debugger.breakpoints().get(hit.target());
            selectedStageIndex = hit.target().stageIndex();
            selectedLine = ((SourceLocation.Function) hit.target().location()).location().line();
            rememberView();
            setFocused(null);
            if (hit.control()) focusedBreakpoint = hit.target();
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && hit.control() && !debugger.breakpoints().pending(hit.target())) {
                ClientNetworking.sendBreakpointEdit(debugger, ClientBreakpointState.Action.TOGGLE,
                    definition == null ? BreakpointDefinition.plain(hit.target()) : definition);
            }
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT || event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            FunctionSourceDocument document = sources.document();
            int sourceLeft = left + treeWidth + 8;
            int lineTop = sourceLineTop();
            if (!drawerOpen && document != null && event.x() >= sourceLeft + 3 && event.x() < codeRight()
                && event.y() >= lineTop && event.y() < lineTop + sourceRows() * ROW_HEIGHT) {
                int line = lineHits.stream().filter(hit -> hit.contains(event.y())).map(LineHit::line)
                    .findFirst().orElse(-1);
                if (line >= 1 && line <= document.lines().size() && sources.selected() != null) {
                    SourceLocation.Function location = new SourceLocation.Function(new FunctionLocation(sources.selected(), line));
                    if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                        if (wholeEligible(document, line)) openCondition(BreakpointTarget.whole(location),
                            new Bounds((int) event.x(), (int) event.y(), 1, 1),
                            lineMarkerContains(event.x(), isActualStop(sources.selected(), line)));
                        return true;
                    }
                    if (lineMarkerContains(event.x(), isActualStop(sources.selected(), line))
                        && debugger != null && wholeEligible(document, line)) {
                        selectedLine = line;
                        selectedStageIndex = -1;
                        BreakpointTarget target = BreakpointTarget.whole(location);
                        focusedBreakpoint = target;
                        if (debugger.breakpoints().ready() && !debugger.breakpoints().pending(target)) {
                            var existing = debugger.breakpoints().get(target);
                            ClientNetworking.sendBreakpointEdit(debugger, ClientBreakpointState.Action.TOGGLE,
                                existing == null ? BreakpointDefinition.plain(target) : existing);
                        }
                        rememberView();
                    } else selectLine(line);
                    setFocused(null);
                    return true;
                }
            }
            int listTop = top + 54;
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.x() >= left + 5 && event.x() < treeRowRight() && event.y() >= listTop
                && event.y() < listTop + visibleRows() * ROW_HEIGHT) {
                int index = listOffset + (int) ((event.y() - listTop) / ROW_HEIGHT);
                if (index < entries.size()) {
                    switch (entries.get(index)) {
                        case Entry.Group group -> {
                            if (search.getValue().isBlank()) {
                                if (!collapsed.add(group.key())) collapsed.remove(group.key());
                                rebuildEntries();
                            }
                        }
                        case Entry.Function function -> {
                            sources.select(function.id());
                            restoreBrowseView();
                            if (drawerMode) setDrawerOpen(false);
                            rememberView();
                        }
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (ScreenLayers.get(this) != null) return true;
        if (!containsPanel(x, y)) return docked && parent.mouseScrolled(x, y, scrollX, scrollY);
        int delta = -(int) Math.signum(scrollY) * 3;
        if (x < left + treeWidth && y >= top + 54 && y < top + 54 + visibleRows() * ROW_HEIGHT)
            listOffset = Math.clamp(listOffset + delta, 0, maximumListOffset());
        else if (!drawerOpen && sources.document() != null && x >= sourceLeft() + 3
            && y >= sourceLineTop() && y < horizontalTrackY() + 6) {
            boolean shift = Minecraft.getInstance().hasShiftDown();
            if (scrollX != 0 || shift) {
                horizontalRemainder += SourceInteraction.horizontalMovement(scrollX, scrollY, shift);
                int movement = (int) horizontalRemainder;
                horizontalRemainder -= movement;
                horizontalOffset = Math.clamp(horizontalOffset + movement, 0, maxHorizontalOffset());
                if (horizontalOffset == 0 || horizontalOffset == maxHorizontalOffset()) horizontalRemainder = 0;
            } else {
                lineOffset = Math.clamp(lineOffset + delta, 0, maximumLineOffset());
            }
            clearSourceHits();
        }
        rememberView();
        return true;
    }

    private void selectLine(int line) {
        revealTarget = null;
        focusedBreakpoint = null;
        FunctionSourceDocument document = sources.document();
        if (document == null || document.lines().isEmpty() || sources.selected() == null) return;
        int nextLine = Math.clamp(line, 1, document.lines().size());
        boolean changed = nextLine != selectedLine;
        selectedLine = nextLine;
        selectedStageIndex = -1;
        lineOffset = Math.clamp(lineOffset, Math.max(0, selectedLine - sourceRows()), selectedLine - 1);
        ClientDebuggerState debugger = CodonClientMod.state();
        // Find may move between matches on the same line while typing. Retain its acknowledged
        // preview instead of restarting a server parse and hiding the stage controls each time.
        if (changed && debugger != null && stageEligible(document, selectedLine)) ClientNetworking.requestStagePreview(debugger,
            new SourceLocation.Function(new FunctionLocation(sources.selected(), selectedLine)));
        rememberView();
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (ScreenLayers.get(this) != null) return true;
        if (event.key() == InputConstants.KEY_F10 && event.hasShiftDown() && parentOwnsContextKeys && docked)
            return parent.keyPressed(event);
        if (event.key() == InputConstants.KEY_F10 && event.hasShiftDown() && getFocused() == null && !drawerOpen) {
            BreakpointTarget target = focusedBreakpoint != null ? focusedBreakpoint : selectedStageTarget();
            if (target == null) target = selectedLineTarget();
            if (target != null) {
                var input = CodonClientMod.input();
                if (input != null) while (input.breakpointKey.consumeClick()) { }
                StageHit stage = null;
                for (StageHit hit : stageHits) if (hit.target().equals(target)) { stage = hit; break; }
                int y = sourceLineTop() + Math.clamp(selectedLine - lineOffset - 1, 0, sourceRows() - 1) * ROW_HEIGHT;
                openCondition(target, stage == null ? new Bounds((sourceLeft() + gutterWidth()), y, Math.max(1, codeRight() - (sourceLeft() + gutterWidth())), ROW_HEIGHT)
                    : new Bounds(stage.x(), stage.y(), stage.width(), stage.height()), focusedBreakpoint != null);
                return true;
            }
        }
        if (event.key() == InputConstants.KEY_F && event.hasControlDownWithQuirk() && sourceSearch.visible) {
            focusedBreakpoint = null;
            setFocused(sourceSearch);
            sourceSearch.setHighlightPos(0);
            return true;
        }
        if (event.key() == InputConstants.KEY_F3 || sourceSearch.isFocused() && event.key() == InputConstants.KEY_RETURN) {
            nextMatch(event.hasShiftDown() ? -1 : 1);
            return true;
        }
        if (event.key() == InputConstants.KEY_ESCAPE) {
            if (sourceSearch.isFocused()) { setFocused(null); return true; }
            onClose(); return true;
        }
        if (event.key() == InputConstants.KEY_TAB) {
            focusedBreakpoint = null;
            List<AbstractWidget> eligible = children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(widget -> widget.visible && widget.active).toList();
            if (!eligible.isEmpty()) {
                int index = eligible.indexOf(getFocused());
                int next = index < 0 ? (event.hasShiftDown() ? eligible.size() - 1 : 0)
                    : Math.floorMod(index + (event.hasShiftDown() ? -1 : 1), eligible.size());
                setFocused(eligible.get(next));
            }
            return true;
        }
        if (getFocused() == null && !drawerOpen && sources.document() != null) {
            int delta = switch (event.key()) {
                case InputConstants.KEY_UP -> -1;
                case InputConstants.KEY_DOWN -> 1;
                case InputConstants.KEY_PAGEUP -> -sourceRows();
                case InputConstants.KEY_PAGEDOWN -> sourceRows();
                default -> 0;
            };
            if (delta != 0) { selectLine((selectedLine > 0 ? selectedLine : lineOffset + 1) + delta); return true; }
            if (event.key() == InputConstants.KEY_HOME || event.key() == InputConstants.KEY_END) {
                selectLine(event.key() == InputConstants.KEY_HOME ? 1 : sources.document().lines().size()); return true;
            }
            if (event.key() == InputConstants.KEY_LEFT || event.key() == InputConstants.KEY_RIGHT) {
                focusedBreakpoint = null;
                horizontalOffset = Math.clamp(horizontalOffset + (event.key() == InputConstants.KEY_RIGHT ? 30 : -30), 0, maxHorizontalOffset());
                rememberView(); return true;
            }
        }
        if (super.keyPressed(event)) return true;
        // Printable input arrives later through charTyped. Keep its preceding key press
        // away from parent shortcuts while a visible, editable text field owns focus.
        if (getFocused() instanceof EditBox field && field.visible && field.canConsumeInput()) return true;
        return docked && parent.keyPressed(event);
    }

    @Override public boolean keyReleased(KeyEvent event) {
        // Vanilla toggles its debug overlay on F3 release, after an unconsumed screen event.
        return event.key() == InputConstants.KEY_F3 || super.keyReleased(event);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (ScreenLayers.get(this) != null) { resizingTree = forwardingParentDrag = false; scrollbars.release(); return true; }
        if (resizingTree) { resizeTree(event.x()); return true; }
        if (scrollbars.drag(event.x(), event.y())) return true;
        return forwardingParentDrag ? parent.mouseDragged(event, dx, dy) : super.mouseDragged(event, dx, dy);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (ScreenLayers.get(this) != null) { resizingTree = forwardingParentDrag = false; scrollbars.release(); return true; }
        if (resizingTree) { resizingTree = false; return true; }
        if (scrollbars.release()) return true;
        if (!forwardingParentDrag) return super.mouseReleased(event);
        forwardingParentDrag = false;
        return parent.mouseReleased(event);
    }

    @Override public void removed() {
        if (parent instanceof CodonScreen codon) codon.cancelPanelResize();
        super.removed();
    }

    @Override public void onClose() { rememberView(); Minecraft.getInstance().gui.setScreen(parent); }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }

    private static String tr(String key, Object... args) { return Component.translatable(key, args).getString(); }
}
