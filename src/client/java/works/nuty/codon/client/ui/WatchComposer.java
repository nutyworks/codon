package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.ui.layout.GizmoLabelLayout.Bounds;
import works.nuty.codon.core.model.WatchEditorPage;
import works.nuty.codon.core.model.WatchEditorQuery;
import works.nuty.codon.core.model.WatchExpression;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static works.nuty.codon.client.ui.DebuggerTheme.*;
import static works.nuty.codon.client.ui.WatchUi.text;

/**
 * The one-line "add or edit watch" row at the top of the Watches panel. Completion reuses the read-only
 * editor queries; while nothing is left to complete, the same request slot shows the live value.
 */
final class WatchComposer {
    private static final int MAX_CHOICES = 4;
    private static final int BOX_HEIGHT = 18;
    private static final int ROW = 16;
    private static final int LINE = 13;

    private record Choice(String insert, String label, String detail) { }

    private final ClientDebuggerState state;
    private final List<DebuggerButton> pool = new ArrayList<>();
    private final List<DebuggerButton> active = new ArrayList<>();
    private final DebuggerButton retry = new DebuggerButton();
    private @Nullable DebuggerEditBox box;
    private long editId = -1;
    private boolean attempted;
    private boolean wantsFocus;
    private boolean previewMode;
    private String lastText = "";
    private int selected;
    private List<Choice> choices = List.of();
    /** What fills the line below the box when no completion is listed. */
    private String message = "";
    private int messageColor = MUTED;
    private boolean retryable;
    private int shownRows;

    WatchComposer(ClientDebuggerState state) { this.state = state; }

    boolean open() { return box != null; }
    @Nullable EditBox box() { return box; }
    void requestFocus() { if (box != null) wantsFocus = true; }
    boolean takeFocusRequest() { boolean value = wantsFocus; wantsFocus = false; return value; }
    boolean focused() { return box != null && box.isFocused(); }

    void begin(long id) { begin(id, ""); }

    /** A new watch (id &lt; 0) may start from a prefix, such as {@code "entity "}, with the cursor after it. */
    void begin(long id, String initial) {
        editId = id;
        attempted = false;
        previewMode = false;
        selected = 0;
        lastText = "";
        Font font = Minecraft.getInstance().font;
        box = new DebuggerEditBox(font, 0, 0, 100, BOX_HEIGHT, text("composer.label"));
        box.setMaxLength(WatchExpression.MAX_LENGTH);
        box.setHint(text("composer.hint"));
        if (id > 0) {
            var entry = state.watches().entries().stream().filter(row -> row.id() == id).findFirst().orElse(null);
            if (entry != null) box.setValue(WatchExpression.format(entry.spec()));
        } else box.setValue(initial);
        box.moveCursorToEnd(false);
        wantsFocus = true;
    }

    void close() {
        box = null;
        choices = List.of();
        active.clear();
        state.watchEditor().cancel();
    }

    List<DebuggerButton> buttons() {
        List<DebuggerButton> all = new ArrayList<>(active);
        if (retryable) all.add(retry);
        return all;
    }

    private static int pathEnd(String text) {
        StringReader reader = new StringReader(text);
        try {
            NbtPathArgument.nbtPath().parse(reader);
            return reader.getCursor();
        } catch (CommandSyntaxException | RuntimeException unfinished) {
            return -1;
        }
    }

    private WatchExpression.Result parsed() { return WatchExpression.parse(box.getValue(), WatchComposer::pathEnd); }

    /** The parent of the path's final segment: the compound whose children can complete it. */
    static String parentOf(String path) {
        int depth = 0, cut = 0;
        boolean quoted = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (quoted) {
                if (c == '\\') i++;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '[' || c == '{') {
                if (depth == 0 && c == '[') cut = i;
                depth++;
            } else if (c == ']' || c == '}') depth--;
            else if (c == '.' && depth == 0) cut = i;
        }
        return path.substring(0, cut);
    }

    /**
     * Refreshes the query and the completion list, then returns the height this composer needs.
     * Must run before {@link #paint}, once per frame.
     */
    int prepare(int maxHeight) {
        if (box == null) return 0;
        String current = box.getValue();
        if (!current.equals(lastText)) {
            lastText = current;
            previewMode = false;
            selected = 0;
            attempted = false;
        }
        var result = parsed();
        boolean paused = state.isPaused() || state.isStepping();
        long pause = WatchUi.pause(state);
        int source = state.selectedPauseSourceIndex();
        choices = List.of();
        message = "";
        messageColor = MUTED;
        retryable = false;

        if (attempted && !result.valid() && !result.error().isEmpty()) {
            message = errorText(result.error());
            messageColor = RED;
        }
        WatchEditorQuery query = previewMode && result.valid() ? previewQuery(result.spec()) : completionQuery(result);
        // Entity paths are read from the paused executor; storage and named scores need no execution.
        boolean needsPause = result.slot() == WatchExpression.Slot.ENTITY_PATH;
        if (result.slot() == WatchExpression.Slot.KEYWORD) {
            choices = keywords(result.prefix());
            state.watchEditor().cancel();
        } else if (query == null || needsPause && !paused) {
            state.watchEditor().cancel();
            if (message.isEmpty()) message = text(needsPause ? "editor.preview_running" : "composer.syntax").getString();
        } else {
            state.watchEditor().request(pause, source, query);
            var page = state.watchEditor().page();
            var shown = state.watchEditor().displayedPage();
            if (previewMode) preview(shown, page);
            else {
                if (page != null && page.status() == WatchResult.Status.VALUE) choices = choicesFrom(result, page);
                else if (page != null && message.isEmpty()) {
                    message = WatchFormatting.status(page.status()).getString();
                    messageColor = AMBER;
                } else if (message.isEmpty()) message = text("editor.loading").getString();
                retryable = state.watchEditor().timedOut()
                    || page != null && (page.status() == WatchResult.Status.ERROR
                        || page.status() == WatchResult.Status.UNAVAILABLE);
                // Nothing left to complete: show what the finished expression reads instead.
                if (page != null && page.status() == WatchResult.Status.VALUE && choices.isEmpty()
                    && result.valid() && paused) previewMode = true;
                else if (page != null && page.status() == WatchResult.Status.VALUE && choices.isEmpty()
                    && message.isEmpty()) message = text("picker.empty").getString();
            }
        }
        if (message.isEmpty() && choices.isEmpty()) message = text("composer.syntax").getString();
        selected = choices.isEmpty() ? 0 : Math.clamp(selected, 0, Math.min(MAX_CHOICES, choices.size()) - 1);
        int fixed = 3 + BOX_HEIGHT + 3 + LINE + 4;
        int rows = Math.clamp((maxHeight - fixed - LINE) / ROW, 0, Math.min(MAX_CHOICES, choices.size()));
        shownRows = rows;
        return fixed + (rows > 0 ? rows * ROW + 2 : LINE);
    }

    private void preview(@Nullable WatchEditorPage shown, @Nullable WatchEditorPage page) {
        if (shown == null) {
            message = text("editor.loading").getString();
            return;
        }
        WatchResult result = shown.preview();
        boolean ok = result != null && result.status() == WatchResult.Status.VALUE;
        String value = ok ? result.value()
            : WatchFormatting.status(result == null ? shown.status() : result.status()).getString();
        if (message.isEmpty()) {
            message = text("editor.preview", value).getString();
            messageColor = ok ? TEAL : AMBER;
        }
        retryable = state.watchEditor().timedOut()
            || page != null && (page.status() == WatchResult.Status.ERROR
                || page.status() == WatchResult.Status.UNAVAILABLE);
    }

    private static WatchEditorQuery previewQuery(WatchSpec spec) {
        return new WatchEditorQuery(WatchEditorQuery.Mode.PREVIEW, spec.kind(), spec.target(), spec.path(),
            spec.executor(), "", 0, spec.scoreHolder());
    }

    private @Nullable WatchEditorQuery completionQuery(WatchExpression.Result result) {
        String prefix = result.prefix();
        String search = prefix.startsWith("\"") ? prefix.substring(1) : prefix;
        if (search.length() > WatchEditorQuery.MAX_SEARCH_LENGTH) search = search.substring(0, WatchEditorQuery.MAX_SEARCH_LENGTH);
        try {
            return switch (result.slot()) {
                case KEYWORD -> null;
                case OBJECTIVE -> new WatchEditorQuery(WatchEditorQuery.Mode.OBJECTIVES, WatchSpec.Kind.SCORE,
                    "", "", null, search, 0);
                case HOLDER -> new WatchEditorQuery(WatchEditorQuery.Mode.ENTITIES, WatchSpec.Kind.SCORE,
                    result.id(), "", null, search, 0);
                case ENTITY_UUID -> new WatchEditorQuery(WatchEditorQuery.Mode.ENTITIES, WatchSpec.Kind.ENTITY_NBT,
                    "", "", null, search, 0);
                case STORAGE_ID -> new WatchEditorQuery(WatchEditorQuery.Mode.STORAGES, WatchSpec.Kind.STORAGE_NBT,
                    "", "", null, search, 0);
                case ENTITY_PATH -> new WatchEditorQuery(WatchEditorQuery.Mode.NBT, WatchSpec.Kind.ENTITY_NBT,
                    "", parentOf(prefix), null, nbtSearch(prefix), 0);
                case STORAGE_PATH -> new WatchEditorQuery(WatchEditorQuery.Mode.NBT, WatchSpec.Kind.STORAGE_NBT,
                    result.id(), parentOf(prefix), null, nbtSearch(prefix), 0);
            };
        } catch (IllegalArgumentException unrepresentable) {
            return null;
        }
    }

    private static List<Choice> keywords(String prefix) {
        List<Choice> all = new ArrayList<>();
        for (String word : new String[] {WatchExpression.SCORE, WatchExpression.ENTITY, WatchExpression.STORAGE}) {
            if (word.startsWith(prefix.toLowerCase(Locale.ROOT)))
                all.add(new Choice(word, word, text("composer.kw." + word).getString()));
        }
        return all;
    }

    private static String nbtSearch(String prefix) {
        String needle = childNeedle(prefix);
        return needle.length() > WatchEditorQuery.MAX_SEARCH_LENGTH
            ? needle.substring(0, WatchEditorQuery.MAX_SEARCH_LENGTH) : needle;
    }

    private static String unquote(String text) {
        if (text.length() < 2 || !text.startsWith("\"") || !text.endsWith("\"")) return text;
        return text.substring(1, text.length() - 1).replaceAll("\\\\(.)", "$1");
    }

    /** The child name being typed after the last complete path segment, without its opening quote. */
    private static String childNeedle(String prefix) {
        String tail = prefix.substring(parentOf(prefix).length());
        if (tail.startsWith(".")) tail = tail.substring(1);
        return tail.startsWith("\"") ? tail.substring(1) : tail;
    }

    private List<Choice> choicesFrom(WatchExpression.Result result, WatchEditorPage page) {
        boolean path = result.slot() == WatchExpression.Slot.ENTITY_PATH
            || result.slot() == WatchExpression.Slot.STORAGE_PATH;
        List<Choice> found = new ArrayList<>();
        if (path) {
            // The server quotes every compound key; offer the plain spelling the user would type.
            String parent = parentOf(result.prefix());
            String needle = childNeedle(result.prefix()).toLowerCase(Locale.ROOT);
            for (var option : page.options()) {
                String name = option.label();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.startsWith(needle) || lower.equals(needle)) continue;
                // Only a list element's path is the parent plus its label; a compound key such as "[0]" is quoted.
                String insert = name.startsWith("[") && option.value().equals(parent + name) ? parent + name
                    : name.matches("[A-Za-z0-9_+-]+") ? (parent.isEmpty() ? name : parent + "." + name) : option.value();
                found.add(new Choice(insert, name, option.detail()));
            }
            return found;
        }
        // A finished entry (quoted or not) is not offered again, so its live preview can show.
        String typed = unquote(result.prefix());
        for (var option : page.options()) {
            String value = option.value();
            if (!unquote(value).equalsIgnoreCase(typed))
                found.add(new Choice(value, option.label().isBlank() ? value : option.label(), option.detail()));
        }
        return found;
    }

    private String errorText(String error) {
        return switch (error) {
            case "keyword" -> text("composer.error.keyword").getString();
            case "path" -> text("composer.error.path").getString();
            default -> text("error." + error).getString();
        };
    }

    void paint(GuiGraphicsExtractor graphics, Font font, Bounds area, int mouseX, int mouseY, boolean interactive) {
        if (box == null) return;
        int x = area.x() + 4, width = area.width() - 8, y = area.y() + 3;
        box.setX(x);
        box.setY(y);
        box.setWidth(width);
        box.setHeight(BOX_HEIGHT);
        box.visible = true;
        box.extractRenderState(graphics, interactive ? mouseX : -1, interactive ? mouseY : -1, 0);
        y += BOX_HEIGHT + 3;
        active.clear();
        if (shownRows > 0) {
            for (int i = 0; i < shownRows; i++) {
                Choice choice = choices.get(i);
                while (pool.size() <= i) pool.add(new DebuggerButton());
                DebuggerButton row = pool.get(i);
                Bounds bounds = new Bounds(x, y + i * ROW, width, ROW);
                boolean hovered = interactive && bounds.contains(mouseX, mouseY);
                if (i == selected || hovered)
                    graphics.fill(x, bounds.y(), x + width, bounds.y() + ROW, DebuggerTheme.color(i == selected ? TEAL_SURFACE : ROW_HOVER));
                int detailWidth = choice.detail().isBlank() ? 0 : Math.min(width / 2, font.width(choice.detail()) + 4);
                WatchUi.line(graphics, font, choice.label(), x + 4, bounds.y() + (ROW - font.lineHeight) / 2 + 1,
                    width - 8 - detailWidth, TEXT);
                if (detailWidth > 0) WatchUi.line(graphics, font, choice.detail(), x + width - detailWidth - 2,
                    bounds.y() + (ROW - font.lineHeight) / 2 + 1, detailWidth, MUTED);
                int index = i;
                row.configure(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                    Component.literal(choice.label() + (choice.detail().isBlank() ? "" : ", " + choice.detail())),
                    true, i == selected, false, false, () -> apply(choices.get(index)));
                row.asHitSurface();
                active.add(row);
            }
            y += shownRows * ROW + 2;
        } else {
            int retryWidth = retryable ? font.width(text("retry")) + 10 : 0;
            WatchUi.line(graphics, font, message, x + 2, y + 2, width - 4 - retryWidth, messageColor);
            if (retryable) {
                retry.configure(x + width - retryWidth, y - 1, retryWidth, LINE + 2, text("retry"), true, false, false, false,
                    () -> state.watchEditor().retry());
                retry.withoutChrome();
            }
            y += LINE;
        }
        WatchUi.line(graphics, font, text(editId > 0 ? "composer.edit_keys" : "composer.keys").getString(),
            x + 2, y + 2, width - 4, MUTED);
    }

    private void apply(Choice choice) {
        if (box == null) return;
        String current = box.getValue();
        var result = parsed();
        boolean more = result.slot() == WatchExpression.Slot.KEYWORD || result.slot() == WatchExpression.Slot.STORAGE_ID;
        box.setValue(current.substring(0, Math.min(result.start(), current.length())) + choice.insert() + (more ? " " : ""));
        box.moveCursorToEnd(false);
        selected = 0;
        wantsFocus = true;
    }

    /** Returns true when the key belongs to the composer; printable text still arrives through charTyped. */
    boolean keyPressed(KeyEvent event, WatchPanel host) {
        if (box == null || !box.isFocused()) return false;
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) { close(); return true; }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            if (!parsed().valid() && !choices.isEmpty()) apply(choices.get(Math.clamp(selected, 0, choices.size() - 1)));
            else submit(event.hasControlDown(), host);
            return true;
        }
        if (key == InputConstants.KEY_TAB) {
            if (!choices.isEmpty()) apply(choices.get(Math.clamp(selected, 0, choices.size() - 1)));
            return true;
        }
        if (key == InputConstants.KEY_DOWN || key == InputConstants.KEY_UP) {
            if (!choices.isEmpty()) selected = Math.floorMod(selected + (key == InputConstants.KEY_DOWN ? 1 : -1),
                Math.min(MAX_CHOICES, choices.size()));
            return true;
        }
        box.keyPressed(event);
        return true;
    }

    private void submit(boolean another, WatchPanel host) {
        var result = parsed();
        if (!result.valid()) {
            attempted = true;
            return;
        }
        WatchSpec spec = result.spec();
        long existing = state.watches().findId(spec);
        if (existing > 0 && existing != editId) {
            state.watches().reveal(existing);
            host.notice(text("feedback.duplicate"));
            close();
            return;
        }
        long id;
        if (editId > 0) {
            if (!state.watches().update(editId, spec)) {
                host.notice(text("editor.no_longer_exists"));
                close();
                return;
            }
            id = editId;
        } else id = state.watches().addOrFind(spec);
        state.watches().reveal(id);
        host.notice(text(editId > 0 ? "feedback.updated" : "feedback.added", WatchFormatting.specification(spec).getString()));
        if (another && editId < 0) {
            // Keep the keyword so repeated additions of one kind need only the new target.
            String keyword = box.getValue().trim().split("\\s+")[0] + " ";
            box.setValue(keyword);
            box.moveCursorToEnd(false);
            attempted = false;
            wantsFocus = true;
        } else close();
    }
}
