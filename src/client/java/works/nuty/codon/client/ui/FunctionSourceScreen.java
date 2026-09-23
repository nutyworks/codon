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
    private int listOffset, lineOffset, stageScrollOffset;
    private int selectedLine = -1;
    private int selectedStageIndex = -1;
    private final List<StageHit> stageHits = new ArrayList<>();
    private final List<LineHit> lineHits = new ArrayList<>();
    private final List<FunctionHit> functionHits = new ArrayList<>();
    private EditBox search;
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
    private record InlinePart(int stageIndex, int row, int x, boolean first, String text, int textWidth) { }
    private record FunctionHit(int x, int y, int width, int height, FunctionId function) {
        boolean contains(double px, double py) { return px >= x && px < x + width && py >= y && py < y + height; }
    }

    public FunctionSourceScreen(Screen parent, ClientFunctionSourceState sources) {
        super(Component.translatable("codon.source.title"));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    @Override protected void init() {
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
        search.setResponder(ignored -> { listOffset = 0; rebuildEntries(); });
        refresh = addRenderableWidget(WatchUi.button(drawerMode ? left + 66 : left + treeWidth + 8, top + 5, 58, 18,
            Component.translatable("codon.source.refresh"), () -> { sources.refreshList(); listOffset = 0; }));
        reread = addRenderableWidget(WatchUi.button(drawerMode ? left + 126 : left + treeWidth + 68, top + 5, 54, 18,
            Component.translatable("codon.source.reload"), () -> {
                sources.refreshSource();
                lineOffset = 0;
                ClientDebuggerState debugger = CodonClientMod.state();
                if (debugger != null && sources.selected() != null && selectedLine > 0)
                    ClientNetworking.requestStagePreview(debugger, new SourceLocation.Function(
                        new FunctionLocation(sources.selected(), selectedLine)));
            }));
        lineCondition = addRenderableWidget(WatchUi.button(compactSourceControls ? sourceLeft : left + treeWidth + 124,
            compactSourceControls ? top + 29 : top + 5, compactSourceControls ? sourceWidth : 92, 18,
            Component.translatable("codon.source.line_condition"), this::editLineCondition));
        stageCondition = addRenderableWidget(WatchUi.button(compactSourceControls ? sourceLeft : left + treeWidth + 218,
            compactSourceControls ? top + 51 : top + 5, compactSourceControls ? sourceWidth : 98, 18,
            Component.translatable("codon.source.stage_condition"), this::editStageCondition));
        close = addRenderableWidget(WatchUi.button(left + panelWidth - 58, top + 5, 50, 18,
            Component.translatable("codon.breakpoint.close"), this::onClose));
        drawerButton = addRenderableWidget(WatchUi.button(left + 8, top + 5, 56, 18,
            Component.translatable("codon.source.functions"), () -> setDrawerOpen(!drawerOpen)));
        backButton = addRenderableWidget(WatchUi.button(left + panelWidth - 116, top + 5, 54, 18,
            Component.translatable("codon.source.back"), this::goBack));
        reread.visible = reread.active = sources.selected() != null;
        drawerButton.visible = drawerButton.active = drawerMode;
        backButton.visible = backButton.active = !drawerOpen && sources.canGoBack();
        search.visible = search.active = !drawerMode || drawerOpen;
        setFocused(search);
        ClientFunctionSourceState.BrowseView view = sources.browseView();
        listOffset = view.treeOffset();
        lineOffset = view.lineOffset();
        selectedLine = view.selectedLine();
        selectedStageIndex = view.selectedStageIndex();
        stageScrollOffset = view.stageScrollOffset();
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
    private int sourceLineTop() { return top + (compactSourceControls ? 118 : 76); }
    private int sourceRows() { return Math.max(1, (top + panelHeight - 38 - sourceLineTop()) / ROW_HEIGHT); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        stageHits.clear();
        lineHits.clear();
        functionHits.clear();
        rebuildEntries();
        reread.visible = reread.active = !drawerOpen && sources.selected() != null && sources.sourceStatus() != ClientFunctionSourceState.Status.LOADING;
        drawerButton.visible = drawerButton.active = drawerMode;
        backButton.visible = backButton.active = !drawerOpen && sources.canGoBack();
        search.visible = search.active = !drawerMode || drawerOpen;
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
        WatchUi.line(graphics, font, selected.toString(), sourceLeft + 6, compactSourceControls ? top + 75 : top + 31,
            sourceWidth - 12, TEAL);
        FunctionSourceDocument document = sources.document();
        if (document == null) {
            WatchUi.line(graphics, font, sourceStatus(), sourceLeft + 6, compactSourceControls ? top + 92 : top + 52, sourceWidth - 12,
                sources.sourceStatus() == ClientFunctionSourceState.Status.ERROR ? AMBER : MUTED);
            return;
        }
        String metadata = (document.provider().isEmpty() ? "" : document.provider() + " · ")
            + document.revision().substring(0, Math.min(12, document.revision().length()));
        if (document.truncated()) metadata += " · " + tr("codon.source.truncated");
        WatchUi.line(graphics, font, metadata, sourceLeft + 6, compactSourceControls ? top + 92 : top + 48,
            sourceWidth - 12, MUTED);
        WatchUi.line(graphics, font, executionStatus(selected), sourceLeft + 6,
            compactSourceControls ? top + 104 : top + 60, sourceWidth - 12, MUTED);
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
        graphics.enableScissor(sourceLeft + 3, lineTop, sourceLeft + sourceWidth - 3, lineBottom);
        for (int index = lineOffset, y = lineTop; index < document.lines().size() && y < lineBottom; index++) {
            String number = Integer.toString(index + 1);
            SourceLocation.Function location = new SourceLocation.Function(new FunctionLocation(selected, index + 1));
            BreakpointDefinition definition = breakpoint(location);
            int color = definition == null ? MUTED : definition.enabled() ? RED : MUTED;
            if (isActualStop(selected, index + 1)) graphics.fill(sourceLeft + 3, y, sourceLeft + 5, y + ROW_HEIGHT - 1,
                DebuggerTheme.color(AMBER));
            WatchUi.line(graphics, font, number, sourceLeft + 21, y + 5, 30, MUTED);
            if (selectedLine == index + 1) graphics.fill(sourceLeft + 52, y, sourceLeft + sourceWidth - 4,
                y + ROW_HEIGHT - 1, DebuggerTheme.color(TEAL_SURFACE));
            int rowHeight = ROW_HEIGHT;
            ClientStagePreviewState.Preview preview = debugger == null ? null : debugger.stagePreviews().get(location);
            if (selectedLine == index + 1 && stageEligible(document, index + 1)
                && previewMatchesLine(document, index + 1, preview)) {
                stageScrollOffset = Math.clamp(stageScrollOffset, 0, Math.max(0,
                    inlineStageRows(preview, sourceWidth - 62) - sourceRows()));
                rowHeight = renderInlineStages(graphics, sourceLeft + 55, sourceWidth - 62, y, lineBottom, location,
                    preview, debugger, stageScrollOffset, mouseX, mouseY);
            } else {
                int textWidth = sourceWidth - 62;
                int count = stageCounts.getOrDefault(index + 1, 0);
                if (count > 0 && selectedLine != index + 1) {
                    String summary = tr("codon.source.stage_breakpoints", count);
                    int summaryWidth = Math.min(textWidth / 2, font.width(summary) + 4);
                    textWidth -= summaryWidth + 3;
                    WatchUi.line(graphics, font, summary, sourceLeft + 55 + textWidth + 3, y + 5,
                        summaryWidth, MUTED);
                }
                renderSourceText(graphics, document.lines().get(index), selected, sourceLeft + 55, y + 5,
                    Math.max(1, textWidth));
            }
            boolean hovered = mouseX >= sourceLeft + 3 && mouseX < sourceLeft + sourceWidth - 3
                && mouseY >= y && mouseY < Math.min(y + rowHeight, lineBottom);
            if (definition != null || hovered && wholeEligible(document, index + 1)) {
                WatchUi.line(graphics, font, BreakpointUi.glyph(definition), sourceLeft + 5, y + 5, 13, color);
            }
            lineHits.add(new LineHit(y, Math.min(rowHeight, lineBottom - y), index + 1));
            y += rowHeight;
        }
        graphics.disableScissor();
        renderSelectedStageDetail(graphics, sourceLeft, sourceWidth, null);
    }

    private static Map<Integer, Integer> stageBreakpointCounts(FunctionId function, ClientDebuggerState debugger) {
        Map<Integer, Integer> counts = new HashMap<>();
        if (debugger == null) return counts;
        for (BreakpointDefinition definition : debugger.breakpoints().definitions()) {
            BreakpointTarget target = definition.target();
            if (target.stageIndex() >= 0 && target.location() instanceof SourceLocation.Function location
                && location.location().function().equals(function)) {
                counts.merge(location.location().line(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Draws stages in command order, using the next row only when the next stage cannot fit. */
    private int renderInlineStages(GuiGraphicsExtractor graphics, int x, int width, int y, int bottom,
                                   SourceLocation.Function location, ClientStagePreviewState.Preview preview,
                                   ClientDebuggerState state, int scrollOffset, int mouseX, int mouseY) {
        List<InlinePart> parts = layoutInlineStages(preview, width);
        int visibleRows = Math.max(1, (bottom - y) / ROW_HEIGHT);
        Set<Integer> hoveredStages = new HashSet<>();
        for (InlinePart part : parts) {
            if (part.stageIndex() < 0 || part.row() < scrollOffset || part.row() >= scrollOffset + visibleRows) continue;
            int partX = x + part.x();
            int partY = y + (part.row() - scrollOffset) * ROW_HEIGHT;
            int partWidth = part.textWidth() + 7 + (part.first() ? 19 : 0);
            if (mouseX >= partX && mouseX < partX + partWidth && mouseY >= partY && mouseY < partY + ROW_HEIGHT) {
                hoveredStages.add(part.stageIndex());
            }
        }
        for (InlinePart part : parts) {
            if (part.row() < scrollOffset || part.row() >= scrollOffset + visibleRows) continue;
            int partY = y + (part.row() - scrollOffset) * ROW_HEIGHT;
            int partX = x + part.x();
            if (part.stageIndex() == -2) {
                WatchUi.line(graphics, font, part.text(), partX, partY + 4, part.textWidth(), TEXT);
                continue;
            }
            BreakpointTarget target = BreakpointTarget.stage(location, part.stageIndex(), preview.savedCommand());
            BreakpointDefinition definition = state.breakpoints().get(target);
            boolean selected = part.stageIndex() == selectedStageIndex;
            int surface = selected ? TEAL_SURFACE : RAISED;
            int textX = partX + (part.first() ? 19 : 0);
            if (part.first()) {
                graphics.fill(partX, partY, partX + 18, partY + 17, DebuggerTheme.color(definition != null && definition.enabled()
                    ? RED_SURFACE : surface));
                if (definition != null || hoveredStages.contains(part.stageIndex())) {
                    WatchUi.line(graphics, font, BreakpointUi.glyph(definition), partX + 4, partY + 4, 12,
                        definition != null && definition.enabled() ? RED : MUTED);
                }
                stageHits.add(new StageHit(partX, partY, 18, 17, target, true));
            }
            graphics.fill(textX, partY, textX + part.textWidth() + 7, partY + 17, DebuggerTheme.color(surface));
            WatchUi.line(graphics, font, part.text(), textX + 4, partY + 4, part.textWidth(), TEXT);
            stageHits.add(new StageHit(textX, partY, part.textWidth() + 7, 17, target, false));
        }
        return Math.max(ROW_HEIGHT, Math.min(visibleRows, inlineStageRows(parts) - scrollOffset) * ROW_HEIGHT);
    }

    private int inlineStageRows(ClientStagePreviewState.Preview preview, int width) {
        return inlineStageRows(layoutInlineStages(preview, width));
    }

    private static int inlineStageRows(List<InlinePart> parts) {
        return parts.isEmpty() ? 1 : parts.getLast().row() + 1;
    }

    private List<InlinePart> layoutInlineStages(ClientStagePreviewState.Preview preview, int width) {
        List<InlinePart> parts = new ArrayList<>();
        int row = 0;
        int x = 0;
        int prefixEnd = CommandFlowLayout.executePrefixEnd(preview.savedCommand());
        if (prefixEnd > 0 && !preview.spans().isEmpty()) {
            String prefix = preview.savedCommand().substring(0, prefixEnd);
            parts.add(new InlinePart(-2, row, x, false, prefix, font.width(prefix)));
            x = font.width(prefix);
        }
        boolean firstSpan = true;
        for (ClientStagePreviewState.StageSpan span : preview.spans()) {
            int start = firstSpan ? Math.max(span.start(), prefixEnd) : span.start();
            String fragment = preview.savedCommand().substring(start, span.end()).trim();
            if (fragment.isEmpty()) continue;
            if (x > 0 && !firstSpan && font.width(fragment) + 26 > width - x) { row++; x = 0; }
            int from = 0;
            boolean first = true;
            while (from < fragment.length()) {
                int lead = first ? 19 : 0;
                int available = width - x - lead - 7;
                if (available <= 0 && x > 0) { row++; x = 0; continue; }
                available = Math.max(1, available);
                String text = font.plainSubstrByWidth(fragment.substring(from), available);
                if (text.isEmpty()) text = fragment.substring(from, from + 1);
                int textWidth = Math.min(available, font.width(text));
                parts.add(new InlinePart(span.index(), row, x, first, text, textWidth));
                from += text.length();
                x += lead + textWidth + 11;
                first = false;
                if (from < fragment.length()) { row++; x = 0; }
            }
            firstSpan = false;
        }
        return parts;
    }

    private static boolean previewMatchesLine(FunctionSourceDocument document, int line,
                                               ClientStagePreviewState.@org.jspecify.annotations.Nullable Preview preview) {
        return preview != null && preview.status() == ClientStagePreviewState.Status.READY
            && line >= 1 && line <= document.lines().size()
            && preview.savedCommand().equals(document.lines().get(line - 1).trim());
    }

    private int selectedInlineStageRows() {
        if (selectedLine < 1 || sources.selected() == null) return 0;
        ClientDebuggerState state = CodonClientMod.state();
        if (state == null) return 0;
        SourceLocation.Function location = new SourceLocation.Function(new FunctionLocation(sources.selected(), selectedLine));
        ClientStagePreviewState.Preview preview = state.stagePreviews().get(location);
        FunctionSourceDocument document = sources.document();
        return document == null || !previewMatchesLine(document, selectedLine, preview) ? 0
            : inlineStageRows(preview, panelWidth - treeWidth - 78);
    }

    private void renderSourceText(GuiGraphicsExtractor graphics, String source, FunctionId current, int x, int y, int width) {
        FunctionId called = calledFunction(source, current.namespace());
        if (called == null) {
            WatchUi.line(graphics, font, source, x, y, width, TEXT);
            return;
        }
        String token = source.substring(source.indexOf("function") + "function".length()).trim().split("\\s+", 2)[0];
        int tokenAt = source.indexOf(token);
        if (tokenAt < 0) { WatchUi.line(graphics, font, source, x, y, width, TEXT); return; }
        String before = source.substring(0, tokenAt);
        String after = source.substring(tokenAt + token.length());
        int beforeWidth = Math.min(font.width(before), width);
        WatchUi.line(graphics, font, before, x, y, width, TEXT);
        WatchUi.line(graphics, font, token, x + beforeWidth, y, Math.max(0, width - beforeWidth), TEAL);
        WatchUi.line(graphics, font, after, x + beforeWidth + font.width(token), y,
            Math.max(0, width - beforeWidth - font.width(token)), TEXT);
        int visibleTokenWidth = Math.min(font.width(token), width - beforeWidth);
        if (visibleTokenWidth > 0)
            functionHits.add(new FunctionHit(x + beforeWidth, y - 4, visibleTokenWidth, ROW_HEIGHT, called));
    }

    private FunctionId calledFunction(String source, String defaultNamespace) {
        String trimmed = source.stripLeading();
        int index = trimmed.indexOf("function");
        if (index < 0 || (index > 0 && Character.isLetterOrDigit(trimmed.charAt(index - 1)))) return null;
        String tail = trimmed.substring(index + "function".length()).trim();
        if (tail.isEmpty()) return null;
        String token = tail.split("\\s+", 2)[0];
        if (!token.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) return null;
        FunctionId id = token.contains(":") ? new FunctionId(token.substring(0, token.indexOf(':')),
            token.substring(token.indexOf(':') + 1)) : new FunctionId(defaultNamespace, token);
        return sources.functions().contains(id) ? id : null;
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
        if (state == null || state.inspectionSnapshot() == null) return false;
        return state.inspectionSnapshot().location() instanceof SourceLocation.Function location
            && location.location().function().equals(function) && location.location().line() == line;
    }

    private String executionStatus(FunctionId function) {
        ClientDebuggerState state = CodonClientMod.state();
        if (state == null || state.inspectionSnapshot() == null) return tr("codon.source.record_unavailable");
        if (state.inspectionSnapshot().location() instanceof SourceLocation.Function location
            && location.location().function().equals(function)) return tr("codon.source.actual_stop", location.location().line());
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
        Minecraft.getInstance().gui.setScreen(new BreakpointConditionScreen(this, debugger,
            existing == null ? BreakpointDefinition.plain(target) : existing, anchor));
    }

    private void renderSelectedStageDetail(GuiGraphicsExtractor graphics, int sourceLeft, int sourceWidth,
                                           ClientStagePreviewState.@org.jspecify.annotations.Nullable Preview preview) {
        int y = top + panelHeight - 30;
        BreakpointTarget stage = selectedStageTarget();
        BreakpointTarget line = selectedLineTarget();
        if (stage != null) {
            ClientDebuggerState state = CodonClientMod.state();
            BreakpointDefinition definition = state == null ? null : state.breakpoints().get(stage);
            String detail = tr("codon.breakpoint.stage_target", selectedStageIndex + 1) + " · "
                + (definition == null ? tr("codon.source.no_breakpoint") : BreakpointUi.condition(definition.condition()));
            WatchUi.line(graphics, font, detail, sourceLeft + 5, y + 4, sourceWidth - 10, TEXT);
        } else if (line != null) {
            ClientDebuggerState state = CodonClientMod.state();
            BreakpointDefinition definition = state == null ? null : state.breakpoints().get(line);
            WatchUi.line(graphics, font, tr("codon.source.line") + " · "
                    + (definition == null ? tr("codon.source.no_breakpoint") : BreakpointUi.condition(definition.condition())),
                sourceLeft + 5, y + 4, sourceWidth - 10, TEXT);
        } else {
            WatchUi.line(graphics, font, tr("codon.source.select_command_or_stage"), sourceLeft + 5,
                y + 4, sourceWidth - 10, MUTED);
        }
        if (preview != null && preview.spans().isEmpty())
            WatchUi.line(graphics, font, tr("codon.source.no_stage_here"),
                sourceLeft + 5, y + 16, sourceWidth - 10, MUTED);
    }

    private void rememberView() {
        sources.rememberBrowseView(listOffset, lineOffset, selectedLine, selectedStageIndex, stageScrollOffset);
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
        stageScrollOffset = view.stageScrollOffset();
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (docked && event.y() < top) {
            forwardingParentDrag = parent.mouseClicked(event, doubleClick);
            return forwardingParentDrag;
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
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && hit.control()) {
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
            if (document != null && event.x() >= sourceLeft + 3 && event.x() < left + panelWidth - 8
                && event.y() >= lineTop && event.y() < lineTop + sourceRows() * ROW_HEIGHT) {
                int line = lineHits.stream().filter(hit -> hit.contains(event.y())).map(LineHit::line)
                    .findFirst().orElse(-1);
                if (line >= 1 && line <= document.lines().size() && debugger != null && sources.selected() != null) {
                    SourceLocation.Function location = new SourceLocation.Function(new FunctionLocation(sources.selected(), line));
                    if (event.x() < sourceLeft + 20 && wholeEligible(document, line)) {
                        selectedLine = line;
                        selectedStageIndex = -1;
                        stageScrollOffset = 0;
                        BreakpointTarget target = BreakpointTarget.whole(location);
                        BreakpointDefinition definition = debugger.breakpoints().get(target);
                        ClientNetworking.sendBreakpointEdit(debugger, ClientBreakpointState.Action.TOGGLE,
                            definition == null ? BreakpointDefinition.plain(target) : definition);
                        rememberView();
                    } else {
                        selectedLine = line;
                        selectedStageIndex = -1;
                        stageScrollOffset = 0;
                        if (stageEligible(document, line)) ClientNetworking.requestStagePreview(debugger, location);
                        rememberView();
                    }
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
        else if (sources.document() != null) {
            int stageRows = selectedInlineStageRows();
            if (stageRows > sourceRows()) stageScrollOffset = Math.clamp(stageScrollOffset + delta, 0, stageRows - sourceRows());
            else lineOffset = Math.clamp(lineOffset + delta, 0, Math.max(0, sources.document().lines().size() - sourceRows()));
        }
        rememberView();
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(event) || docked && parent.keyPressed(event);
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return forwardingParentDrag ? parent.mouseDragged(event, dx, dy) : super.mouseDragged(event, dx, dy);
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
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
