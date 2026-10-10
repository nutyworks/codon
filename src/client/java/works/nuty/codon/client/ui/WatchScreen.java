package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientWatchEditorState;
import works.nuty.codon.client.ui.layout.WatchFormLayout;
import works.nuty.codon.core.model.WatchEditorQuery;
import works.nuty.codon.core.model.WatchResult;
import works.nuty.codon.core.model.WatchSpec;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static works.nuty.codon.client.ui.DebuggerTheme.*;
import static works.nuty.codon.client.ui.WatchUi.text;

/** A focused expression form. Watch rows and their management live exclusively in Watches. */
public final class WatchScreen extends ScaledCodonScreen {
    private final InputManager input;
    private final ClientDebuggerState state;
    private final DebuggerOverlay overlay;
    private final ScreenReturn origin;
    private final long editId;
    private final EnumMap<WatchSpec.Kind, ClientWatchEditorState.Draft> drafts = new EnumMap<>(WatchSpec.Kind.class);
    private final Map<String, EditBox> fields = new LinkedHashMap<>();
    private final Map<String, WatchEditorQuery.Mode> fieldModes = new LinkedHashMap<>();
    private final List<AbstractWidget> tabOrder = new ArrayList<>();
    private final List<DebuggerButton> inlineSuggestions = new ArrayList<>();
    private WatchSpec.Kind kind;
    private DebuggerButton submit;
    private DebuggerButton retry;
    private int left, top, panelWidth, panelHeight;
    private WatchFormLayout layout;
    private String feedback = "";
    private int feedbackColor = MUTED;
    private boolean attempted;

    public WatchScreen(InputManager input, ClientDebuggerState state, DebuggerOverlay overlay) {
        this(input, state, overlay, -1);
    }

    private WatchScreen(InputManager input, ClientDebuggerState state, DebuggerOverlay overlay, long editId) {
        super(text(editId > 0 ? "editor.edit_title" : "editor.title"), state.preferences());
        this.input = input;
        this.state = state;
        this.overlay = overlay;
        this.origin = new ScreenReturn(input, overlay);
        this.editId = editId;
        kind = state.watchEditor().kind();
        for (WatchSpec.Kind type : WatchSpec.Kind.values()) drafts.put(type, state.watchEditor().draft(type));
        if (editId > 0) {
            var entry = state.watches().entries().stream().filter(row -> row.id() == editId).findFirst().orElse(null);
            if (entry != null) {
                var spec = entry.spec();
                kind = spec.kind();
                drafts.put(kind, new ClientWatchEditorState.Draft(spec.target(), spec.path(),
                    spec.scoreHolder() != null ? quotedHolder(spec.scoreHolder())
                        : spec.executor() == null ? "" : spec.executor().toString()));
            }
        }
    }

    public static WatchScreen edit(InputManager input, ClientDebuggerState state, DebuggerOverlay overlay, long id) {
        return new WatchScreen(input, state, overlay, id);
    }

    @Override protected void init() {
        rememberDraft();
        clearWidgets();
        fields.clear();
        fieldModes.clear();
        tabOrder.clear();
        inlineSuggestions.clear();
        layout = WatchFormLayout.create(width, height);
        panelWidth = layout.panel().width();
        panelHeight = layout.panel().height();
        left = layout.panel().x();
        top = layout.panel().y();
        List<AbstractWidget> types = new ArrayList<>();
        for (var type : WatchSpec.Kind.values()) {
            var bounds = layout.kind(type.ordinal());
            DebuggerButton button = WatchUi.button(bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                text("kind." + type.name().toLowerCase(java.util.Locale.ROOT)), () -> chooseKind(type));
            button.configure(button.getX(), button.getY(), button.getWidth(), button.getHeight(), button.getMessage(),
                true, type == kind, false, false, () -> chooseKind(type));
            addRenderableWidget(button);
            types.add(button);
        }
        var draft = drafts.getOrDefault(kind, ClientWatchEditorState.Draft.EMPTY);
        if (kind == WatchSpec.Kind.SCORE) {
            field("target", 0, text("editor.objective"), "points", draft.target(), WatchEditorQuery.Mode.OBJECTIVES);
            field("entity", 1, text("editor.score_holder"), text("editor.score_holder_hint").getString(), draft.entity(), WatchEditorQuery.Mode.ENTITIES);
        } else if (kind == WatchSpec.Kind.ENTITY_NBT) {
            field("path", 0, text("path"), "Health, Pos[0]", draft.path(), WatchEditorQuery.Mode.NBT);
            field("entity", 1, text("editor.entity"), text("editor.context").getString(), draft.entity(), WatchEditorQuery.Mode.ENTITIES);
        } else {
            field("target", 0, text("editor.storage"), "demo:state", draft.target(), WatchEditorQuery.Mode.STORAGES);
            field("path", 1, text("path"), "counter", draft.path(), WatchEditorQuery.Mode.NBT);
        }
        for (int index = 0; index < 2; index++) {
            DebuggerButton choice = addRenderableWidget(WatchUi.button(0, 0, 1, 1, Component.empty(), () -> { }));
            choice.visible = choice.active = false;
            inlineSuggestions.add(choice);
        }
        var submitBounds = layout.submit();
        submit = addRenderableWidget(WatchUi.button(submitBounds.x(), submitBounds.y(), submitBounds.width(), submitBounds.height(),
            text(editId > 0 ? "editor.save" : "add"), () -> submit(false)));
        tabOrder.add(submit);
        var retryBounds = layout.retry();
        retry = addRenderableWidget(WatchUi.button(retryBounds.x(), retryBounds.y(), retryBounds.width(), retryBounds.height(),
            text("retry"), () -> state.watchEditor().retry()));
        retry.visible = retry.active = false;
        var closeBounds = layout.close();
        DebuggerButton close = addRenderableWidget(WatchUi.button(closeBounds.x(), closeBounds.y(), closeBounds.width(), closeBounds.height(),
            text("close"), this::onClose));
        tabOrder.add(close);
        tabOrder.addAll(inlineSuggestions);
        tabOrder.add(retry);
        tabOrder.addAll(types);
        for (int i = 0; i < tabOrder.size(); i++) tabOrder.get(i).setTabOrderGroup(i);
        state.watchEditor().cancel();
        setFocused(primary());
        refreshValidation();
    }

    private void field(String id, int index, Component label, String hint, String value, WatchEditorQuery.Mode mode) {
        var bounds = layout.field(index);
        EditBox field = new DebuggerEditBox(font, bounds.x(), bounds.y(), bounds.width(), bounds.height(), label);
        field.setMaxLength(id.equals("entity") && kind == WatchSpec.Kind.SCORE
            ? WatchSpec.MAX_INPUT_LENGTH * 2 + 2 : WatchSpec.MAX_INPUT_LENGTH);
        field.setHint(Component.literal(hint));
        field.setValue(value);
        field.setResponder(ignored -> {
            feedback = "";
            rememberDraft();
            refreshValidation();
        });
        fields.put(id, addRenderableWidget(field));
        fieldModes.put(id, mode);
        tabOrder.add(field);
        var browseBounds = layout.browse(index);
        DebuggerButton browse = addRenderableWidget(WatchUi.button(browseBounds.x(), browseBounds.y(), browseBounds.width(), browseBounds.height(),
            text(id.equals("entity") ? "editor.choose" : "editor.browse"), () -> browse(mode, id)));
        tabOrder.add(browse);
    }

    @Override protected void setInitialFocus() { setFocused(primary()); }

    private @Nullable EditBox primary() { return fields.get(kind == WatchSpec.Kind.ENTITY_NBT ? "path" : "target"); }

    private void chooseKind(WatchSpec.Kind next) {
        if (next == kind) return;
        rememberDraft();
        fields.clear();
        kind = next;
        if (editId < 0) state.watchEditor().kind(kind);
        attempted = false;
        feedback = "";
        rebuildWidgets();
    }

    private void rememberDraft() {
        if (fields.isEmpty()) return;
        var draft = new ClientWatchEditorState.Draft(value("target"), value("path"), value("entity"));
        drafts.put(kind, draft);
        if (editId < 0) {
            state.watchEditor().kind(kind);
            state.watchEditor().draft(kind, draft);
        }
    }

    private String value(String id) { var field = fields.get(id); return field == null ? "" : field.getValue(); }

    private @Nullable UUID executor() {
        String entity = value("entity").trim();
        if (entity.isEmpty()) return null;
        try {
            UUID id = UUID.fromString(entity);
            return id.toString().equalsIgnoreCase(entity) ? id : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    private static String quotedHolder(String name) {
        return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private @Nullable String scoreHolder() {
        String value = value("entity").trim();
        if (kind != WatchSpec.Kind.SCORE || value.isEmpty() || executor() != null) return null;
        if (value.startsWith("\"")) {
            try {
                StringReader reader = new StringReader(value);
                String name = reader.readQuotedString();
                if (reader.canRead()) throw new IllegalArgumentException("Trailing score holder input");
                return name;
            } catch (CommandSyntaxException invalid) { throw new IllegalArgumentException(invalid); }
        }
        return value;
    }

    private Map<String, Component> errors() {
        Map<String, Component> errors = new LinkedHashMap<>();
        String target = value("target").trim(), path = value("path").trim(), entity = value("entity").trim();
        if (kind != WatchSpec.Kind.ENTITY_NBT && target.isEmpty()) errors.put("target", text(kind == WatchSpec.Kind.SCORE ? "error.objective" : "error.storage"));
        if (kind == WatchSpec.Kind.STORAGE_NBT && !target.isEmpty() && !target.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+"))
            errors.put("target", text("error.storage_id"));
        if (kind != WatchSpec.Kind.SCORE) {
            if (path.isEmpty()) errors.put("path", text("error.path_required"));
            else {
                try {
                    StringReader reader = new StringReader(path);
                    NbtPathArgument.nbtPath().parse(reader);
                    if (reader.canRead()) errors.put("path", text("error.path", reader.getCursor() + 1));
                } catch (CommandSyntaxException error) {
                    errors.put("path", text("error.path", Math.max(1, error.getCursor() + 1)));
                } catch (RuntimeException error) { errors.put("path", text("error.path_incomplete")); }
            }
        }
        if (kind == WatchSpec.Kind.ENTITY_NBT && !entity.isEmpty() && executor() == null)
            errors.put("entity", text("error.entity"));
        if (kind == WatchSpec.Kind.SCORE && !entity.isEmpty()) {
            try {
                String holder = scoreHolder();
                if (holder != null) WatchSpec.scoreHolder("_", holder);
            } catch (IllegalArgumentException invalid) { errors.put("entity", text("error.score_holder")); }
        }
        if (errors.isEmpty()) {
            try { specification(); }
            catch (IllegalArgumentException error) { errors.put(kind == WatchSpec.Kind.ENTITY_NBT ? "path" : "target", text("error.characters")); }
        }
        return errors;
    }

    private WatchSpec specification() {
        return new WatchSpec(kind, kind == WatchSpec.Kind.ENTITY_NBT ? "" : value("target"),
            kind == WatchSpec.Kind.SCORE ? "" : value("path"), kind == WatchSpec.Kind.STORAGE_NBT ? null : executor(), scoreHolder());
    }

    private void refreshValidation() { if (submit != null) submit.active = errors().isEmpty(); }

    private void submit(boolean another) {
        attempted = true;
        var errors = errors();
        if (!errors.isEmpty()) {
            setFocused(fields.get(errors.keySet().iterator().next()));
            return;
        }
        WatchSpec spec = specification();
        long existing = state.watches().findId(spec);
        if (existing > 0 && existing != editId) {
            state.watches().reveal(existing);
            overlay.watchPanel().notice(text("feedback.duplicate"));
            onClose();
            return;
        }
        long id;
        if (editId > 0) {
            if (!state.watches().update(editId, spec)) {
                feedback = text("editor.no_longer_exists").getString();
                feedbackColor = RED;
                return;
            }
            id = editId;
        } else id = state.watches().addOrFind(spec);
        state.watches().reveal(id);
        overlay.watchPanel().notice(text(editId > 0 ? "feedback.updated" : "feedback.added",
            WatchFormatting.specification(spec).getString()));
        if (editId < 0) {
            // Keep the optional entity and storage ID for deliberate repeated additions.
            var draft = new ClientWatchEditorState.Draft(kind == WatchSpec.Kind.STORAGE_NBT ? value("target") : "", "", value("entity"));
            drafts.put(kind, draft);
            state.watchEditor().draft(kind, draft);
            fields.clear();
        }
        if (another && editId < 0) {
            feedback = text("feedback.added", WatchFormatting.specification(spec).getString()).getString();
            feedbackColor = TEAL;
            attempted = false;
            rebuildWidgets();
        } else onClose();
    }

    private void browse(WatchEditorQuery.Mode mode, String field) {
        rememberDraft();
        String target = value("target").trim();
        if (mode == WatchEditorQuery.Mode.NBT && kind == WatchSpec.Kind.STORAGE_NBT
            && (target.isEmpty() || !target.matches("([a-z0-9_.-]+:)?[a-z0-9_./-]+"))) {
            attempted = true;
            setFocused(fields.get("target"));
            return;
        }
        String search = mode == WatchEditorQuery.Mode.NBT ? "" : value(field);
        if (kind == WatchSpec.Kind.SCORE && field.equals("entity")) {
            try { if (scoreHolder() != null) search = scoreHolder(); }
            catch (IllegalArgumentException ignored) { search = ""; }
        }
        if (search.length() > WatchEditorQuery.MAX_SEARCH_LENGTH)
            search = search.substring(0, WatchEditorQuery.MAX_SEARCH_LENGTH);
        var query = new WatchEditorQuery(mode, kind, target, "", executor(), search, 0);
        minecraft.gui.setScreen(new WatchPickerScreen(this, state, query, option -> {
            var draft = drafts.get(kind);
            drafts.put(kind, new ClientWatchEditorState.Draft(field.equals("target") ? option.value() : draft.target(),
                field.equals("path") ? option.value() : draft.path(), field.equals("entity") ? option.value() : draft.entity()));
            fields.clear();
            if (editId < 0) state.watchEditor().draft(kind, drafts.get(kind));
        }));
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, DebuggerTheme.modalColor(0x70000000));
        graphics.fill(left, top, left + panelWidth, top + panelHeight, DebuggerTheme.modalColor(PANEL));
        graphics.outline(left, top, panelWidth, panelHeight, DebuggerTheme.modalColor(BORDER));
        graphics.fill(left, top, left + 2, top + 24, DebuggerTheme.modalColor(TEAL));
        WatchUi.line(graphics, font, title.getString(), left + 8, top + 9, panelWidth - 72, TEXT);
        var errors = errors();
        submit.active = errors.isEmpty();
        fields.forEach((id, field) -> {
            int index = field == primary() ? 0 : 1;
            WatchUi.line(graphics, font, field.getMessage().getString(), field.getX(), layout.labelY(index), layout.contentWidth(), MUTED);
            Component error = errors.get(id);
            if (error != null && (attempted || !field.getValue().isBlank())) {
                WatchUi.line(graphics, font, error.getString(), field.getX(), layout.errorY(index), layout.contentWidth(), RED);
                if (WatchUi.clipped(font, error.getString(), layout.contentWidth())
                    && mouseX >= field.getX() && mouseX < left + panelWidth - 8 && mouseY >= field.getY() + 22 && mouseY < field.getY() + 34
                    && HoverDelay.elapsed(List.of("watch.field-error", id)))
                    graphics.setTooltipForNextFrame(font, error, mouseX, mouseY);
            }
        });
        if (!renderInlineSuggestions(graphics, errors)) renderPreview(graphics, errors.isEmpty());
        if (!feedback.isEmpty()) WatchUi.line(graphics, font, feedback, left + 8, layout.feedbackY(), panelWidth - 16, feedbackColor);
        if (!errors.isEmpty()) {
            Component reason = errors.values().iterator().next();
            var bounds = layout.submitReason();
            WatchUi.line(graphics, font, reason.getString(), bounds.x(),
                bounds.y() + (bounds.height() - font.lineHeight) / 2 + 1, bounds.width(), AMBER);
            if (WatchUi.clipped(font, reason.getString(), bounds.width())
                && (bounds.contains(mouseX, mouseY) || submit.isMouseOver(mouseX, mouseY))
                && HoverDelay.elapsed("watch.submit-reason"))
                graphics.setTooltipForNextFrame(font, reason, mouseX, mouseY);
        }
        WatchUi.line(graphics, font, text(editId > 0 ? "editor.edit_keys" : "editor.keys").getString(),
            left + 8, layout.keysY(), panelWidth - 16, MUTED);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * The simple server lists (objectives, entities, and storage IDs) are useful while typing.
     * NBT stays in its hierarchical Picker because selecting a child needs its path stack.
     */
    private boolean renderInlineSuggestions(GuiGraphicsExtractor graphics, Map<String, Component> errors) {
        for (DebuggerButton choice : inlineSuggestions) choice.visible = choice.active = false;
        retry.visible = retry.active = false;
        if (!layout.inlineSuggestions() || !(getFocused() instanceof EditBox focused)) return false;
        String field = fields.entrySet().stream().filter(entry -> entry.getValue() == focused).map(Map.Entry::getKey).findFirst().orElse(null);
        if (field == null) return false;
        WatchEditorQuery.Mode mode = fieldModes.get(field);
        if (mode == null || mode == WatchEditorQuery.Mode.NBT) return false;

        String search = suggestionSearch(field);
        String target = value("target").trim();
        var query = new WatchEditorQuery(mode, kind, target, "", executor(), search, 0);
        state.watchEditor().request(WatchUi.pause(state), state.selectedPauseSourceIndex(), query);
        var page = state.watchEditor().page();
        // Keep the same request behavior, but give a visible validation error its own slot.
        if (errors.containsKey(field) && (attempted || !focused.getValue().isBlank())) return true;
        int fieldIndex = focused == primary() ? 0 : 1;
        int y = layout.suggestion(fieldIndex, 0).y();
        if (page == null) {
            WatchUi.line(graphics, font, text("editor.loading").getString(), focused.getX(), y + 3, focused.getWidth(), MUTED);
            return true;
        }
        if (page.status() != WatchResult.Status.VALUE) {
            WatchUi.line(graphics, font, WatchFormatting.status(page.status()).getString(), focused.getX(), y + 3, focused.getWidth(), AMBER);
            return true;
        }
        List<works.nuty.codon.core.model.WatchEditorPage.Option> options = page.options().stream()
            .filter(option -> !option.expandable()).limit(inlineSuggestions.size()).toList();
        if (options.isEmpty()) {
            WatchUi.line(graphics, font, text("picker.empty").getString(), focused.getX(), y + 3, focused.getWidth(), MUTED);
            return true;
        }
        for (int index = 0; index < options.size(); index++) {
            var option = options.get(index);
            DebuggerButton choice = inlineSuggestions.get(index);
            Component label = Component.literal(option.label().isBlank() ? option.value() : option.label());
            Runnable select = () -> chooseInline(field, option.value());
            var bounds = layout.suggestion(fieldIndex, index);
            choice.configure(bounds.x(), bounds.y(), bounds.width(), bounds.height(), label, true, false, false, false, select);
            choice.setTooltip(Tooltip.create(option.detail().isBlank() ? label : Component.literal(option.detail())));
            choice.visible = choice.active = true;
        }
        return true;
    }

    private String suggestionSearch(String field) {
        if (kind == WatchSpec.Kind.SCORE && field.equals("entity")) {
            try {
                String holder = scoreHolder();
                return holder == null ? "" : holder;
            } catch (IllegalArgumentException ignored) { return ""; }
        }
        String input = value(field);
        return input.length() > WatchEditorQuery.MAX_SEARCH_LENGTH
            ? input.substring(0, WatchEditorQuery.MAX_SEARCH_LENGTH) : input;
    }

    private void chooseInline(String field, String selected) {
        EditBox box = fields.get(field);
        if (box == null) return;
        box.setValue(selected);
        setFocused(box);
    }

    private void renderPreview(GuiGraphicsExtractor graphics, boolean valid) {
        int y = layout.previewY();
        if (kind != WatchSpec.Kind.STORAGE_NBT) {
            var current = WatchUi.currentEntity(state);
            String holder = valid ? scoreHolder() : null;
            String scope = holder != null ? text("scope.fixed", holder).getString()
                : executor() != null ? text("editor.bound", executor().toString().substring(0, 8)).getString()
                : current == null ? text("editor.no_entity").getString()
                : text("editor.following", current.name() + " #" + current.uuid().toString().substring(0, 8)).getString();
            WatchUi.line(graphics, font, scope, left + 8, y, panelWidth - 16, MUTED);
        }
        retry.visible = retry.active = false;
        if (!valid || !state.isPaused() && !state.isStepping()) {
            state.watchEditor().cancel();
            WatchUi.line(graphics, font, text(state.isPaused() ? "editor.preview_hint" : "editor.preview_running").getString(),
                left + 8, y + 16, panelWidth - 16, MUTED);
            return;
        }
        if (state.isPaused()) {
            var spec = specification();
            state.watchEditor().request(WatchUi.pause(state), state.selectedPauseSourceIndex(),
                new WatchEditorQuery(WatchEditorQuery.Mode.PREVIEW, kind, spec.target(), spec.path(), spec.executor(), "", 0, spec.scoreHolder()));
        }
        var page = state.watchEditor().page();
        var displayedPage = state.watchEditor().displayedPage();
        String value;
        if (displayedPage == null) value = text("editor.loading").getString();
        else {
            WatchResult result = displayedPage.preview();
            value = result != null && result.status() == WatchResult.Status.VALUE ? result.value()
                : WatchFormatting.status(result == null ? displayedPage.status() : result.status()).getString();
            retry.visible = retry.active = state.watchEditor().timedOut()
                || page != null && (page.status() == WatchResult.Status.ERROR || page.status() == WatchResult.Status.UNAVAILABLE);
        }
        WatchUi.line(graphics, font, text("editor.preview", value).getString(), left + 8, layout.previewValueY(),
            retry.visible ? layout.field(1).width() : layout.contentWidth(), TEXT);
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_TAB) {
            List<AbstractWidget> eligible = tabOrder.stream().filter(widget -> widget.active && widget.visible).toList();
            if (!eligible.isEmpty()) {
                int current = eligible.indexOf(getFocused());
                int next = Math.floorMod(current + (event.hasShiftDown() ? -1 : 1), eligible.size());
                setFocused(eligible.get(next));
            }
            return true;
        }
        if (getFocused() instanceof EditBox) {
            if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
                submit(event.hasControlDown());
                return true;
            }
            // Text and editing shortcuts never reach gameplay or execution controls.
            return super.keyPressed(event);
        }
        if (input.menuKey.matches(event)) { onClose(); return true; }
        return super.keyPressed(event);
    }

    @Override public void removed() { rememberDraft(); state.watchEditor().cancel(); }
    @Override public void onClose() { rememberDraft(); origin.restore(); }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
