package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
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
import works.nuty.codon.client.ui.layout.CommandFlowLayout;
import works.nuty.codon.client.ui.layout.SourceSyntax;
import works.nuty.codon.client.ui.layout.SourceLineLayout;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.FunctionSourceDocument;
import works.nuty.codon.core.model.SourceLocation;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Read-only searchable tree and source view for all functions loaded by the current server. */
public final class FunctionSourceScreen extends Screen {
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
    private int listOffset, lineOffset, horizontalOffset;
    private boolean draggingHorizontal;
    private FunctionSourceDocument cachedDocument;
    private final List<SourceCodeLine> codeLines = new ArrayList<>();
    private List<SourceSyntax.Match> matches = List.of();
    private final Map<Integer, List<SourceSyntax.Match>> matchesByLine = new HashMap<>();
    private int matchIndex = -1;
    private int widestLine;
    private int selectedLine = -1;
    private int selectedStageIndex = -1;
    private final List<StageHit> stageHits = new ArrayList<>();
    private final List<LineHit> lineHits = new ArrayList<>();
    private final List<FunctionHit> functionHits = new ArrayList<>();
    private EditBox search, sourceSearch;
    private DebuggerButton previousMatch, nextMatch;
    private DebuggerButton refresh, close, reread, stageCondition, lineCondition, drawerButton, backButton;


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
    private SourceCodeLine inlineCode;
    private ClientStagePreviewState.Preview inlinePreview;
    private List<InlineStage> inlineStages = List.of();
    private SourceLineLayout inlineLayout;
    private record FunctionHit(int x, int y, int width, int height, FunctionId function) {
        boolean contains(double px, double py) { return px >= x && px < x + width && py >= y && py < y + height; }
    }

    public FunctionSourceScreen(Screen parent, ClientFunctionSourceState sources) {
        super(Component.translatable("codon.source.title"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    @Override protected void init() {
        String searchValue = search == null ? "" : search.getValue();
        String sourceSearchValue = sourceSearch == null ? "" : sourceSearch.getValue();
        cachedDocument = null;
        clearWidgets();
        ClientFunctionSourceState.ScreenLayout layout = ClientFunctionSourceState.ScreenLayout.forScreen(width, height);
        panelWidth = layout.panelWidth();
        panelHeight = layout.panelHeight();
        docked = parent instanceof CodonScreen && width >= 640 && height >= 360;
        if (docked) panelHeight = Math.min(panelHeight, height - 106);
        left = (width - panelWidth) / 2;
        top = docked ? height - panelHeight - 6 : (height - panelHeight) / 2;
        drawerMode = layout.drawerMode();
        if (drawerMode && sources.selected() == null) drawerOpen = true;
        treeWidth = drawerMode ? (drawerOpen ? panelWidth : 0) : layout.treeWidth();
        int sourceLeft = left + treeWidth + 8;
        int sourceWidth = panelWidth - treeWidth - 16;
        compactSourceControls = !drawerMode && sourceWidth < 330 || drawerMode;
        search = addRenderableWidget(new DebuggerEditBox(font, left + 8, top + 29, Math.max(1, treeWidth - 16), 20,
            Component.translatable("codon.source.search")));
        search.setHint(Component.translatable("codon.source.search"));
        search.setMaxLength(128);
        search.setValue(searchValue);
        search.setResponder(ignored -> { listOffset = 0; rebuildEntries(); rememberView(); });
        refresh = addRenderableWidget(WatchUi.button(drawerMode ? left + 74 : left + treeWidth + 8, top + 5, 58, 18,
            Component.translatable("codon.source.refresh"), () -> { sources.refreshList(); listOffset = 0; }));
        reread = addRenderableWidget(WatchUi.button(drawerMode ? left + 134 : left + treeWidth + 68, top + 5, 54, 18,
            Component.translatable("codon.source.reload"), () -> {
                sources.refreshSource();
                lineOffset = 0;
                ClientDebuggerState debugger = CodonClientMod.state();
                if (debugger != null && sources.selected() != null && selectedLine > 0)
                    ClientNetworking.requestStagePreview(debugger, new SourceLocation.Function(
                        new FunctionLocation(sources.selected(), selectedLine)));
            }));
        lineCondition = addRenderableWidget(WatchUi.button(compactSourceControls ? sourceLeft : left + treeWidth + 124,
            compactSourceControls ? top + 29 : top + 5, compactSourceControls ? (sourceWidth - 4) / 2 : 92, 18,
            Component.translatable("codon.source.line_condition"), this::editLineCondition));
        stageCondition = addRenderableWidget(WatchUi.button(compactSourceControls ? sourceLeft + (sourceWidth - 4) / 2 + 4 : left + treeWidth + 218,
            compactSourceControls ? top + 29 : top + 5, compactSourceControls ? (sourceWidth - 4) / 2 : 98, 18,
            Component.translatable("codon.source.stage_condition"), this::editStageCondition));
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
        int findY = top + (compactSourceControls ? 92 : 75);
        sourceSearch = addRenderableWidget(new DebuggerEditBox(font, sourceLeft + 5, findY,
            Math.max(1, sourceWidth - 85), 20, Component.translatable("codon.source.find")));
        sourceSearch.setHint(Component.translatable("codon.source.find"));
        sourceSearch.setTooltip(Tooltip.create(Component.translatable("codon.source.find_hint")));
        sourceSearch.setMaxLength(128);
        sourceSearch.setValue(sourceSearchValue);
        sourceSearch.setResponder(ignored -> { matchIndex = -1; rebuildMatches(); if (!matches.isEmpty()) nextMatch(1); });
        previousMatch = addRenderableWidget(WatchUi.button(sourceLeft + sourceWidth - 41, findY, 18, 20,
            Component.literal("<"), () -> nextMatch(-1)));
        nextMatch = addRenderableWidget(WatchUi.button(sourceLeft + sourceWidth - 21, findY, 18, 20,
            Component.literal(">"), () -> nextMatch(1)));
        previousMatch.setTooltip(Tooltip.create(Component.translatable("codon.source.previous_match")));
        nextMatch.setTooltip(Tooltip.create(Component.translatable("codon.source.next_match")));
        setFocused(search.visible ? search : null);
        ClientFunctionSourceState.BrowseView view = sources.browseView();
        listOffset = view.treeOffset();
        lineOffset = view.lineOffset();
        selectedLine = view.selectedLine();
        selectedStageIndex = view.selectedStageIndex();
        horizontalOffset = view.horizontalOffset();
        sources.open();
        if (sources.selected() != null && selectedLine > 0) {
            ClientDebuggerState debugger = CodonClientMod.state();
            if (debugger != null) ClientNetworking.requestStagePreview(debugger, new SourceLocation.Function(
                new FunctionLocation(sources.selected(), selectedLine)));
        }
        rebuildEntries();
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
        listOffset = Math.clamp(listOffset, 0, Math.max(0, entries.size() - visibleRows()));
    }

    private boolean expanded(String key, String needle) { return !needle.isEmpty() || !collapsed.contains(key); }
    private int visibleRows() { return Math.max(1, (panelHeight - 76) / ROW_HEIGHT); }
    private int sourceLineTop() { return top + (compactSourceControls ? 118 : 100); }
    private int sourceRows() { return Math.max(1, (top + panelHeight - 22 - sourceLineTop()) / ROW_HEIGHT); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        stageHits.clear();
        lineHits.clear();
        functionHits.clear();
        rebuildEntries();
        reread.visible = reread.active = !drawerOpen && sources.selected() != null && sources.sourceStatus() != ClientFunctionSourceState.Status.LOADING;
        drawerButton.visible = drawerButton.active = drawerMode;
        backButton.visible = backButton.active = !drawerOpen && sources.canGoBack();
        search.visible = search.active = !drawerMode || drawerOpen;
        updateCodeCache();
        updateInlineLayout();
        sourceSearch.visible = sourceSearch.active = !drawerOpen && sources.document() != null;
        previousMatch.visible = nextMatch.visible = sourceSearch.visible;
        previousMatch.active = nextMatch.active = !matches.isEmpty();
        updateConditionControls();
        if (docked) parent.extractRenderState(graphics, mouseX, mouseY, partialTick);
        else graphics.fill(0, 0, width, height, DebuggerTheme.color(0x70000000));
        graphics.fill(left, top, left + panelWidth, top + panelHeight, DebuggerTheme.color(PANEL));
        graphics.outline(left, top, panelWidth, panelHeight, DebuggerTheme.color(BORDER));
        graphics.fill(left, top, left + 2, top + 24, DebuggerTheme.color(TEAL));
        WatchUi.line(graphics, font, tr(drawerOpen ? "codon.source.functions" : "codon.source.title"),
            left + 8, top + 9, Math.max(1, treeWidth - 20), TEXT);
        if (!drawerMode || drawerOpen) {
            if (!drawerMode) graphics.fill(left + treeWidth, top + 25, left + treeWidth + 1, top + panelHeight - 8, DebuggerTheme.color(BORDER));
            renderTree(graphics, mouseX, mouseY);
            if (!drawerOpen) renderSource(graphics, mouseX, mouseY);
        } else renderSource(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void renderTree(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int listTop = top + 54;
        int rows = visibleRows();
        graphics.enableScissor(left + 4, listTop, left + treeWidth - 4, listTop + rows * ROW_HEIGHT);
        for (int row = 0; row < rows && listOffset + row < entries.size(); row++) {
            Entry entry = entries.get(listOffset + row);
            int y = listTop + row * ROW_HEIGHT;
            boolean selected = entry instanceof Entry.Function function && function.id().equals(sources.selected());
            boolean hovered = mouseX >= left + 5 && mouseX < left + treeWidth - 5 && mouseY >= y && mouseY < y + ROW_HEIGHT;
            if (selected || hovered) graphics.fill(left + 5, y, left + treeWidth - 5, y + ROW_HEIGHT - 1,
                DebuggerTheme.color(selected ? TEAL_SURFACE : RAISED));
            switch (entry) {
                case Entry.Group group -> {
                    boolean open = expanded(group.key(), search.getValue());
                    WatchUi.line(graphics, font, (open ? "− " : "+ ") + group.label(), left + 9 + group.depth() * 10,
                        y + 5, treeWidth - 16 - group.depth() * 10, open ? TEAL : MUTED);
                }
                case Entry.Function function -> WatchUi.line(graphics, font, function.label(),
                    left + 18 + function.depth() * 10, y + 5, treeWidth - 26 - function.depth() * 10, TEXT);
            }
        }
        graphics.disableScissor();
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
        WatchUi.line(graphics, font, path, sourceLeft + 6, compactSourceControls ? top + 53 : top + 31,
            sourceWidth - 12, TEAL);
        int pathY = compactSourceControls ? top + 53 : top + 31;
        if (mouseX >= sourceLeft + 6 && mouseX < sourceLeft + sourceWidth - 6 && mouseY >= pathY && mouseY < pathY + 10)
            graphics.setTooltipForNextFrame(font, Component.literal(path), mouseX, mouseY);
        FunctionSourceDocument document = sources.document();
        if (document == null) {
            WatchUi.line(graphics, font, sourceStatus(), sourceLeft + 6, compactSourceControls ? top + 65 : top + 52, sourceWidth - 12,
                sources.sourceStatus() == ClientFunctionSourceState.Status.ERROR ? AMBER : MUTED);
            return;
        }
        String metadata = (document.provider().isEmpty() ? "" : document.provider() + " · ")
            + document.revision().substring(0, Math.min(12, document.revision().length()));
        if (document.truncated()) metadata += " · " + tr("codon.source.truncated");
        WatchUi.line(graphics, font, metadata, sourceLeft + 6, compactSourceControls ? top + 65 : top + 48,
            sourceWidth - 12, MUTED);
        WatchUi.line(graphics, font, executionStatus(selected), sourceLeft + 6,
            compactSourceControls ? top + 77 : top + 60, sourceWidth - 12, MUTED);
        WatchUi.line(graphics, font, matches.isEmpty() ? "0/0" : (matchIndex + 1) + "/" + matches.size(),
            sourceLeft + sourceWidth - 78, top + (compactSourceControls ? 98 : 81), 34, MUTED);
        int gutter = gutterWidth();
        int codeLeft = sourceLeft + gutter;
        int codeWidth = sourceWidth - gutter - 4;
        horizontalOffset = Math.clamp(horizontalOffset, 0, maxHorizontalOffset());
        int lineTop = sourceLineTop();
        int rows = sourceRows();
        if (selectedLine > document.lines().size()) {
            selectedLine = -1;
            selectedStageIndex = -1;
            rememberView();
        }
        lineOffset = Math.clamp(lineOffset, 0, Math.max(0, document.lines().size() - rows));
        int lineBottom = lineTop + rows * ROW_HEIGHT;
        ClientDebuggerState debugger = CodonClientMod.state();
        Map<Integer, Integer> stageCounts = stageBreakpointCounts(selected, debugger);
        graphics.fill(sourceLeft + 3, lineTop, sourceLeft + sourceWidth - 3, lineBottom, DebuggerTheme.color(SURFACE));
        graphics.fill(sourceLeft + gutter - 3, lineTop, sourceLeft + gutter - 2, lineBottom, DebuggerTheme.color(BORDER));
        graphics.enableScissor(sourceLeft + 3, lineTop, sourceLeft + sourceWidth - 3, lineBottom);
        for (int index = lineOffset, y = lineTop; index < document.lines().size() && y < lineBottom; index++) {
            String number = Integer.toString(index + 1);
            SourceLocation.Function location = new SourceLocation.Function(new FunctionLocation(selected, index + 1));
            BreakpointDefinition definition = breakpoint(location);
            int color = definition == null ? MUTED : definition.enabled() ? RED : MUTED;
            boolean stopped = isActualStop(selected, index + 1);
            boolean inspected = selectedLine == index + 1;
            if (stopped) {
                graphics.fill(codeLeft, y, sourceLeft + sourceWidth - 4, y + ROW_HEIGHT - 1, DebuggerTheme.color(AMBER_SURFACE));
                // Arrow + border retain the stop/selection distinction without relying on hue.
                graphics.text(font, ">", sourceLeft + 17, y + 5, DebuggerTheme.color(AMBER), false);
            }
            if (inspected) {
                if (!stopped) graphics.fill(codeLeft, y, sourceLeft + sourceWidth - 4, y + ROW_HEIGHT - 1, DebuggerTheme.color(TEAL_SURFACE));
                graphics.outline(codeLeft, y, codeWidth, ROW_HEIGHT - 1, DebuggerTheme.color(TEAL));
            }
            graphics.text(font, SourceCodeLine.plain(number), codeLeft - 7 - font.width(SourceCodeLine.plain(number)),
                y + 5, DebuggerTheme.color(stopped ? AMBER : MUTED), false);
            graphics.enableScissor(codeLeft, y, sourceLeft + sourceWidth - 4, y + ROW_HEIGHT);
            SourceCodeLine code = codeLines.get(index);
            SourceLineLayout lineLayout = inspected && inlineLayout != null ? inlineLayout
                : new SourceLineLayout(code.source().length(), List.of(), code::x);
            renderSourceText(graphics, code, lineLayout, index + 1, selected, codeLeft, y + 5, codeWidth);
            if (inspected) renderInlineMarkers(graphics, lineLayout, codeLeft, y, codeWidth, mouseX, mouseY);
            graphics.disableScissor();
            int count = stageCounts.getOrDefault(index + 1, 0);
            if (count > 0 && !stopped) {
                // Compact count is separate from the horizontally scrolling source.
                String badge = count > 99 ? "+" : Integer.toString(count);
                graphics.outline(sourceLeft + 15, y + 3, 14, 12, DebuggerTheme.color(BORDER));
                graphics.text(font, SourceCodeLine.plain(badge), sourceLeft + 22 - font.width(badge) / 2,
                    y + 5, DebuggerTheme.color(MUTED), false);
            }
            if (count > 0 && mouseX >= sourceLeft + 15 && mouseX < sourceLeft + 30 && mouseY >= y && mouseY < y + ROW_HEIGHT)
                graphics.setTooltipForNextFrame(font, Component.translatable("codon.source.stage_breakpoints", count), mouseX, mouseY);
            boolean hovered = mouseX >= sourceLeft + 3 && mouseX < sourceLeft + sourceWidth - 3
                && mouseY >= y && mouseY < y + ROW_HEIGHT;
            if (definition != null && definition.enabled() || hovered && wholeEligible(document, index + 1)) {
                BreakpointUi.icon(definition).drawSmall(graphics, sourceLeft + 5, y + 5, DebuggerTheme.color(color));
            }
            lineHits.add(new LineHit(y, ROW_HEIGHT, index + 1));
            y += ROW_HEIGHT;
        }
        graphics.disableScissor();
        renderHorizontalScrollbar(graphics, sourceLeft, sourceWidth);
        if (mouseX >= codeLeft && mouseX < sourceLeft + sourceWidth - 4
            && mouseY >= horizontalTrackY() - 2 && mouseY < horizontalTrackY() + 8)
            graphics.setTooltipForNextFrame(font, Component.translatable("codon.source.navigation_hint"), mouseX, mouseY);
    }

    private static Map<Integer, Integer> stageBreakpointCounts(FunctionId function, ClientDebuggerState debugger) {
        Map<Integer, Integer> counts = new HashMap<>();
        if (debugger == null) return counts;
        for (BreakpointDefinition definition : debugger.breakpoints().definitions()) {
            BreakpointTarget target = definition.target();
            if (definition.enabled() && target.stageIndex() >= 0 && target.location() instanceof SourceLocation.Function location
                && location.location().function().equals(function)) {
                counts.merge(location.location().line(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Use the same authoritative boundaries and icon spacing as the command-block editor. */
    private void updateInlineLayout() {
        SourceCodeLine code = selectedLine >= 1 && selectedLine <= codeLines.size() ? codeLines.get(selectedLine - 1) : null;
        ClientDebuggerState state = CodonClientMod.state();
        SourceLocation.Function location = sources.selected() == null || code == null ? null
            : new SourceLocation.Function(new FunctionLocation(sources.selected(), selectedLine));
        ClientStagePreviewState.Preview preview = state == null || location == null ? null : state.stagePreviews().get(location);
        if (inlineCode == code && inlinePreview == preview) return;
        inlineCode = code;
        inlinePreview = preview;
        inlineStages = List.of();
        inlineLayout = code == null ? null : new SourceLineLayout(code.source().length(), List.of(), code::x);
        FunctionSourceDocument document = sources.document();
        if (code == null || document == null || !stageEligible(document, selectedLine)
            || !previewMatchesLine(document, selectedLine, preview)) return;
        int leading = code.source().indexOf(preview.savedCommand());
        int prefixEnd = CommandFlowLayout.executePrefixEnd(preview.savedCommand());
        int previousEnd = 0;
        List<InlineStage> stages = new ArrayList<>();
        for (var span : preview.spans()) {
            if (span.start() < previousEnd || span.end() > preview.savedCommand().length() || span.start() >= span.end()) return;
            int start = Math.max(prefixEnd, span.start());
            while (start < span.end() && Character.isWhitespace(preview.savedCommand().charAt(start))) start++;
            previousEnd = span.end();
            if (start >= span.end()) continue;
            stages.add(new InlineStage(span.index(), leading + start, leading + span.end(),
                BreakpointTarget.stage(location, span.index(), preview.savedCommand())));
        }
        inlineStages = List.copyOf(stages);
        inlineLayout = new SourceLineLayout(code.source().length(), stages.stream().map(InlineStage::start).toList(), code::x);
    }

    private void renderInlineMarkers(GuiGraphicsExtractor graphics, SourceLineLayout layout, int x, int y, int width,
                                     int mouseX, int mouseY) {
        ClientDebuggerState state = CodonClientMod.state();
        if (state == null) return;
        for (int index = 0; index < inlineStages.size(); index++) {
            InlineStage stage = inlineStages.get(index);
            int markerX = x + layout.markerX(index) - horizontalOffset;
            int start = x + layout.x(stage.start()) - horizontalOffset;
            int end = x + layout.before(stage.end()) - horizontalOffset;
            boolean hovered = mouseY >= y && mouseY < y + ROW_HEIGHT && mouseX >= Math.max(x, markerX)
                && mouseX < Math.min(x + width, end);
            BreakpointDefinition definition = state.breakpoints().get(stage.target());
            boolean enabled = definition != null && definition.enabled();
            if (selectedStageIndex == stage.index() && end > x && start < x + width)
                graphics.outline(Math.max(x, start), y + 2, Math.min(x + width, end) - Math.max(x, start), 13, DebuggerTheme.color(TEAL));
            if (enabled || hovered || selectedStageIndex == stage.index())
                BreakpointUi.icon(definition).drawSmall(graphics, markerX + 2, y + 5, DebuggerTheme.color(enabled ? RED : MUTED));
            addStageHit(markerX, y, markerX + SourceLineLayout.MARKER_WIDTH, x, width, stage.target(), true);
            addStageHit(start, y, end, x, width, stage.target(), false);
            if (hovered && mouseX < markerX + SourceLineLayout.MARKER_WIDTH)
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
        return Math.max(48, 31 + font.width(Integer.toString(sources.document() == null ? 1 : sources.document().lines().size())));
    }

    private int displayedWidth() { return Math.max(widestLine, inlineLayout == null ? 0 : inlineLayout.width()); }
    private int maxHorizontalOffset() { return Math.max(0, displayedWidth() - (panelWidth - treeWidth - 20 - gutterWidth())); }
    private int horizontalTrackY() { return top + panelHeight - 16; }

    private void updateCodeCache() {
        FunctionSourceDocument document = sources.document();
        if (cachedDocument == document) return;
        cachedDocument = document;
        codeLines.clear();
        widestLine = 0;
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
        matches = sources.document() == null || sourceSearch == null ? List.of()
            : SourceSyntax.find(sources.document().lines(), sourceSearch.getValue());
        matchesByLine.clear();
        for (SourceSyntax.Match match : matches) matchesByLine.computeIfAbsent(match.line(), ignored -> new ArrayList<>()).add(match);
        matchIndex = previous == null ? -1 : matches.indexOf(previous);
    }

    private void nextMatch(int direction) {
        if (matches.isEmpty()) return;
        matchIndex = matchIndex < 0 ? (direction > 0 ? 0 : matches.size() - 1)
            : Math.floorMod(matchIndex + direction, matches.size());
        SourceSyntax.Match match = matches.get(matchIndex);
        selectLine(match.line());
        lineOffset = match.line() - 1;
        if (match.line() <= codeLines.size()) {
            updateInlineLayout();
            SourceCodeLine code = codeLines.get(match.line() - 1);
            int start = inlineLayout == null ? code.x(match.start()) : inlineLayout.x(match.start());
            int end = inlineLayout == null ? code.x(match.end()) : inlineLayout.before(match.end());
            int visible = panelWidth - treeWidth - 20 - gutterWidth();
            if (start < horizontalOffset || end > horizontalOffset + visible)
                horizontalOffset = Math.clamp(start - 10, 0, maxHorizontalOffset());
        }
        rememberView();
    }

    private void renderSourceText(GuiGraphicsExtractor graphics, SourceCodeLine code, SourceLineLayout layout, int line, FunctionId current,
                                  int x, int y, int width) {
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
            graphics.text(font, slice.text(), x + slice.x(), y, DebuggerTheme.color(TEXT), false);
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
            FunctionId called = calledFunction(token, current.namespace());
            if (called == null) continue;
            int start = x + layout.x(reference.start()) - horizontalOffset;
            int end = x + layout.before(reference.end()) - horizontalOffset;
            int visibleStart = Math.max(x, start), visibleEnd = Math.min(x + width, end);
            if (visibleEnd > visibleStart) {
                graphics.fill(visibleStart, y + 9, visibleEnd, y + 10, DebuggerTheme.color(TEAL));
                functionHits.add(new FunctionHit(visibleStart, y - 4, visibleEnd - visibleStart, ROW_HEIGHT, called));
            }
        }
    }

    private FunctionId calledFunction(String token, String defaultNamespace) {
        if (!token.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) return null;
        FunctionId id = token.contains(":") ? new FunctionId(token.substring(0, token.indexOf(':')),
            token.substring(token.indexOf(':') + 1)) : new FunctionId(defaultNamespace, token);
        return sources.functions().contains(id) ? id : null;
    }

    private void renderHorizontalScrollbar(GuiGraphicsExtractor graphics, int sourceLeft, int sourceWidth) {
        int x = sourceLeft + gutterWidth();
        int width = sourceWidth - gutterWidth() - 4;
        int y = horizontalTrackY();
        graphics.fill(x, y, x + width, y + 6, DebuggerTheme.color(RAISED));
        int thumb = Math.max(18, width * width / Math.max(width, displayedWidth()));
        int offset = maxHorizontalOffset() == 0 ? 0 : horizontalOffset * (width - thumb) / maxHorizontalOffset();
        graphics.fill(x + offset, y, x + offset + thumb, y + 6, DebuggerTheme.color(maxHorizontalOffset() == 0 ? BORDER : TEAL));
    }

    private void scrollHorizontally(double x) {
        int leftEdge = left + treeWidth + 8 + gutterWidth();
        int width = panelWidth - treeWidth - 20 - gutterWidth();
        int thumb = Math.max(18, width * width / Math.max(width, displayedWidth()));
        horizontalOffset = width <= thumb ? 0 : Math.clamp((int) ((x - leftEdge - thumb / 2.0) * maxHorizontalOffset() / (width - thumb)), 0, maxHorizontalOffset());
        rememberView();
    }

    private BreakpointDefinition breakpoint(SourceLocation.Function location) {
        ClientDebuggerState state = CodonClientMod.state();
        return state == null ? null : state.breakpoints().get(BreakpointTarget.whole(location));
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
        if (document == null || !previewMatchesLine(document, selectedLine, preview)
            || preview.spans().stream().noneMatch(span -> span.index() == selectedStageIndex)) return null;
        return BreakpointTarget.stage(location, selectedStageIndex, preview.savedCommand());
    }

    private @org.jspecify.annotations.Nullable BreakpointTarget selectedLineTarget() {
        FunctionSourceDocument document = sources.document();
        if (document == null || sources.selected() == null || selectedLine < 1 || selectedLine > document.lines().size()) return null;
        if (!wholeEligible(document, selectedLine)) return null;
        return BreakpointTarget.whole(new SourceLocation.Function(new FunctionLocation(sources.selected(), selectedLine)));
    }

    private void updateConditionControls() {
        BreakpointTarget line = selectedLineTarget();
        BreakpointTarget stage = selectedStageTarget();
        lineCondition.visible = lineCondition.active = !drawerOpen && line != null;
        stageCondition.visible = stageCondition.active = !drawerOpen && stage != null;
        if (line != null) lineCondition.setMessage(Component.translatable("codon.source.line_condition"));
        if (stage != null) stageCondition.setMessage(Component.translatable("codon.source.stage_condition"));
    }

    private void editLineCondition() { editCondition(selectedLineTarget()); }
    private void editStageCondition() { editCondition(selectedStageTarget()); }

    private void editCondition(@org.jspecify.annotations.Nullable BreakpointTarget target) {
        ClientDebuggerState debugger = CodonClientMod.state();
        if (debugger == null || target == null) return;
        BreakpointDefinition existing = debugger.breakpoints().get(target);
        DebuggerButton trigger = target.wholeCommand() ? lineCondition : stageCondition;
        BreakpointConditionScreen.Anchor anchor = trigger == null ? null
            : new BreakpointConditionScreen.Anchor(trigger.getX(), trigger.getY(),
                trigger.getWidth(), trigger.getHeight());
        ScreenLayers.open(this, new BreakpointConditionScreen(this, debugger,
            existing == null ? BreakpointDefinition.plain(target) : existing, anchor));
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
        ClientFunctionSourceState.BrowseView view = sources.browseView();
        listOffset = view.treeOffset();
        lineOffset = view.lineOffset();
        selectedLine = view.selectedLine();
        selectedStageIndex = view.selectedStageIndex();
        horizontalOffset = view.horizontalOffset();
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (docked && event.y() < top) {
            forwardingParentDrag = parent.mouseClicked(event, doubleClick);
            return forwardingParentDrag;
        }
        if (!drawerOpen && event.button() == InputConstants.MOUSE_BUTTON_LEFT
            && event.y() >= horizontalTrackY() - 2 && event.y() < horizontalTrackY() + 8
            && event.x() >= left + treeWidth + 8 + gutterWidth() && event.x() < left + panelWidth - 12) {
            draggingHorizontal = true;
            scrollHorizontally(event.x());
            setFocused(null);
            return true;
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
            BreakpointDefinition definition = debugger.breakpoints().get(hit.target());
            selectedStageIndex = hit.target().stageIndex();
            selectedLine = ((SourceLocation.Function) hit.target().location()).location().line();
            rememberView();
            setFocused(null);
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && hit.control() && !debugger.breakpoints().pending(hit.target())) {
                ClientNetworking.sendBreakpointEdit(debugger, ClientBreakpointState.Action.TOGGLE,
                    definition == null ? BreakpointDefinition.plain(hit.target()) : definition);
            } else if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
                editCondition(hit.target());
            }
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            FunctionSourceDocument document = sources.document();
            int sourceLeft = left + treeWidth + 8;
            int lineTop = sourceLineTop();
            if (!drawerOpen && document != null && event.x() >= sourceLeft + 3 && event.x() < left + panelWidth - 8
                && event.y() >= lineTop && event.y() < lineTop + sourceRows() * ROW_HEIGHT) {
                int line = lineHits.stream().filter(hit -> hit.contains(event.y())).map(LineHit::line)
                    .findFirst().orElse(-1);
                if (line >= 1 && line <= document.lines().size() && sources.selected() != null) {
                    SourceLocation.Function location = new SourceLocation.Function(new FunctionLocation(sources.selected(), line));
                    if (event.x() < sourceLeft + 17 && debugger != null && wholeEligible(document, line)) {
                        selectedLine = line;
                        selectedStageIndex = -1;
                        BreakpointTarget target = BreakpointTarget.whole(location);
                        BreakpointDefinition definition = debugger.breakpoints().get(target);
                        ClientNetworking.sendBreakpointEdit(debugger, ClientBreakpointState.Action.TOGGLE,
                            definition == null ? BreakpointDefinition.plain(target) : definition);
                        rememberView();
                    } else selectLine(line);
                    setFocused(null);
                    return true;
                }
            }
            int listTop = top + 54;
            if (event.x() >= left + 5 && event.x() < left + treeWidth - 5 && event.y() >= listTop
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
        if (docked && y < top) return parent.mouseScrolled(x, y, scrollX, scrollY);
        int delta = -(int) Math.signum(scrollY) * 3;
        if (x < left + treeWidth) listOffset = Math.clamp(listOffset + delta, 0, Math.max(0, entries.size() - visibleRows()));
        else if (!drawerOpen && sources.document() != null) {
            boolean shift = Minecraft.getInstance().hasShiftDown();
            if (scrollX != 0 || shift) {
                horizontalOffset = Math.clamp(horizontalOffset - (int) ((shift ? scrollY : scrollX) * 30), 0, maxHorizontalOffset());
            } else {
                lineOffset = Math.clamp(lineOffset + delta, 0, Math.max(0, sources.document().lines().size() - sourceRows()));
            }
        }
        rememberView();
        return true;
    }

    private void selectLine(int line) {
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
        if (event.key() == InputConstants.KEY_F && event.hasControlDownWithQuirk() && sourceSearch.visible) {
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
            List<AbstractWidget> eligible = children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(widget -> widget.visible && widget.active).toList();
            if (!eligible.isEmpty()) {
                int index = eligible.indexOf(getFocused());
                setFocused(eligible.get(Math.floorMod(index + (event.hasShiftDown() ? -1 : 1), eligible.size())));
            }
            return true;
        }
        boolean typing = search.visible && search.isFocused() || sourceSearch.visible && sourceSearch.isFocused();
        if (!typing && !drawerOpen && sources.document() != null) {
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
                horizontalOffset = Math.clamp(horizontalOffset + (event.key() == InputConstants.KEY_RIGHT ? 30 : -30), 0, maxHorizontalOffset());
                rememberView(); return true;
            }
        }
        return super.keyPressed(event) || docked && parent.keyPressed(event);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingHorizontal) { scrollHorizontally(event.x()); return true; }
        return forwardingParentDrag ? parent.mouseDragged(event, dx, dy) : super.mouseDragged(event, dx, dy);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingHorizontal) { draggingHorizontal = false; return true; }
        if (!forwardingParentDrag) return super.mouseReleased(event);
        forwardingParentDrag = false;
        return parent.mouseReleased(event);
    }

    @Override public void onClose() { rememberView(); Minecraft.getInstance().gui.setScreen(parent); }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }

    private static String tr(String key, Object... args) { return Component.translatable(key, args).getString(); }
}
