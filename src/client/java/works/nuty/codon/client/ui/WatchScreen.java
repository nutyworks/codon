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
public final class WatchScreen extends Screen {
    private final InputManager input;
    private final ClientDebuggerState state;
    private final DebuggerOverlay overlay;
    private final long editId;
    private final EnumMap<WatchSpec.Kind, ClientWatchEditorState.Draft> drafts = new EnumMap<>(WatchSpec.Kind.class);
    private final Map<String, EditBox> fields = new LinkedHashMap<>();
    private final List<AbstractWidget> tabOrder = new ArrayList<>();
    private final List<AbstractWidget> browseControls = new ArrayList<>();
    private WatchSpec.Kind kind;
    private DebuggerButton submit;
    private DebuggerButton retry;
    private int left, top, panelWidth, panelHeight;
    private String feedback = "";
    private int feedbackColor = MUTED;
    private boolean attempted;

    public WatchScreen(InputManager input, ClientDebuggerState state, DebuggerOverlay overlay) {
        this(input, state, overlay, -1);
    }

    private WatchScreen(InputManager input, ClientDebuggerState state, DebuggerOverlay overlay, long editId) {
        super(text(editId > 0 ? "editor.edit_title" : "editor.title"));
        this.input = input;
        this.state = state;
        this.overlay = overlay;
        this.editId = editId;
        kind = state.watchEditor().kind();
        for (WatchSpec.Kind type : WatchSpec.Kind.values()) drafts.put(type, state.watchEditor().draft(type));
        if (editId > 0) {
            var entry = state.watches().entries().stream().filter(row -> row.id() == editId).findFirst().orElse(null);
            if (entry != null) {
                var spec = entry.spec();
                kind = spec.kind();
                drafts.put(kind, new ClientWatchEditorState.Draft(spec.target(), spec.path(),
                    spec.executor() == null ? "" : spec.executor().toString()));
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
        tabOrder.clear();
        browseControls.clear();
        panelWidth = Math.max(1, Math.min(460, width - 16));
        panelHeight = Math.max(1, Math.min(250, height - 12));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        int typeWidth = (panelWidth - 20) / 3;
        List<AbstractWidget> types = new ArrayList<>();
        for (var type : WatchSpec.Kind.values()) {
            DebuggerButton button = WatchUi.button(left + 8 + type.ordinal() * (typeWidth + 2), top + 28,
                typeWidth, 20, text("kind." + type.name().toLowerCase(java.util.Locale.ROOT)), () -> chooseKind(type));
            button.configure(button.getX(), button.getY(), button.getWidth(), button.getHeight(), button.getMessage(),
                true, type == kind, false, false, () -> chooseKind(type));
            addRenderableWidget(button);
            types.add(button);
        }
        var draft = drafts.getOrDefault(kind, ClientWatchEditorState.Draft.EMPTY);
        int firstY = top + 65;
        int secondY = top + 112;
        if (kind == WatchSpec.Kind.SCORE) {
            field("target", firstY, text("editor.objective"), "points", draft.target(), WatchEditorQuery.Mode.OBJECTIVES);
            field("entity", secondY, text("editor.entity"), text("editor.context").getString(), draft.entity(), WatchEditorQuery.Mode.ENTITIES);
        } else if (kind == WatchSpec.Kind.ENTITY_NBT) {
            field("path", firstY, text("path"), "Health, Pos[0]", draft.path(), WatchEditorQuery.Mode.NBT);
            field("entity", secondY, text("editor.entity"), text("editor.context").getString(), draft.entity(), WatchEditorQuery.Mode.ENTITIES);
        } else {
            field("target", firstY, text("editor.storage"), "demo:state", draft.target(), WatchEditorQuery.Mode.STORAGES);
            field("path", secondY, text("path"), "counter", draft.path(), WatchEditorQuery.Mode.NBT);
        }
        submit = addRenderableWidget(WatchUi.button(left + panelWidth - 80, top + panelHeight - 35, 72, 20,
            text(editId > 0 ? "editor.save" : "add"), () -> submit(false)));
        tabOrder.add(submit);
        tabOrder.addAll(browseControls);
        retry = addRenderableWidget(WatchUi.button(left + panelWidth - 58, top + 157, 50, 18,
            text("retry"), () -> state.watchEditor().retry()));
        retry.visible = retry.active = false;
        tabOrder.add(retry);
        DebuggerButton close = addRenderableWidget(WatchUi.button(left + panelWidth - 54, top + 4, 46, 18,
            text("close"), this::onClose));
        tabOrder.addAll(types);
        tabOrder.add(close);
        for (int i = 0; i < tabOrder.size(); i++) tabOrder.get(i).setTabOrderGroup(i);
        state.watchEditor().cancel();
        setFocused(primary());
        refreshValidation();
    }

    private void field(String id, int y, Component label, String hint, String value, WatchEditorQuery.Mode mode) {
        EditBox field = new EditBox(font, left + 8, y, Math.max(1, panelWidth - 76), 20, label);
        field.setMaxLength(id.equals("entity") ? 128 : WatchSpec.MAX_INPUT_LENGTH);
        field.setHint(Component.literal(hint));
        field.setTooltip(Tooltip.create(label));
        field.setValue(value);
        field.setResponder(ignored -> {
            feedback = "";
            rememberDraft();
            refreshValidation();
        });
        fields.put(id, addRenderableWidget(field));
        tabOrder.add(field);
        DebuggerButton browse = addRenderableWidget(WatchUi.button(left + panelWidth - 62, y, 54, 20,
            text(id.equals("entity") ? "editor.choose" : "editor.browse"), () -> browse(mode, id)));
        browse.setTooltip(Tooltip.create(text("editor.browse_hint")));
        browseControls.add(browse);
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
        try { return UUID.fromString(entity); } catch (IllegalArgumentException ignored) { return null; }
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
        if (!entity.isEmpty() && executor() == null) errors.put("entity", text("error.entity"));
        if (errors.isEmpty()) {
            try { specification(); }
            catch (IllegalArgumentException error) { errors.put(kind == WatchSpec.Kind.ENTITY_NBT ? "path" : "target", text("error.characters")); }
        }
        return errors;
    }

    private WatchSpec specification() {
        return new WatchSpec(kind, kind == WatchSpec.Kind.ENTITY_NBT ? "" : value("target"),
            kind == WatchSpec.Kind.SCORE ? "" : value("path"), kind == WatchSpec.Kind.STORAGE_NBT ? null : executor());
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
        graphics.fill(0, 0, width, height, 0x70000000);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
        graphics.outline(left, top, panelWidth, panelHeight, BORDER);
        graphics.fill(left, top, left + 2, top + 24, TEAL);
        WatchUi.line(graphics, font, title.getString(), left + 8, top + 9, panelWidth - 72, TEXT);
        var errors = errors();
        submit.active = errors.isEmpty();
        fields.forEach((id, field) -> {
            WatchUi.line(graphics, font, field.getMessage().getString(), field.getX(), field.getY() - 11, panelWidth - 16, MUTED);
            Component error = errors.get(id);
            if (error != null && (attempted || !field.getValue().isBlank())) {
                WatchUi.line(graphics, font, error.getString(), field.getX(), field.getY() + 23, panelWidth - 16, RED);
                if (mouseX >= field.getX() && mouseX < left + panelWidth - 8 && mouseY >= field.getY() + 22 && mouseY < field.getY() + 34)
                    graphics.setTooltipForNextFrame(font, error, mouseX, mouseY);
            }
        });
        renderPreview(graphics, errors.isEmpty());
        if (!feedback.isEmpty()) WatchUi.line(graphics, font, feedback, left + 8, top + panelHeight - 49, panelWidth - 16, feedbackColor);
        WatchUi.line(graphics, font, text(editId > 0 ? "editor.edit_keys" : "editor.keys").getString(),
            left + 8, top + panelHeight - 11, panelWidth - 16, MUTED);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void renderPreview(GuiGraphicsExtractor graphics, boolean valid) {
        int y = top + 148;
        if (kind != WatchSpec.Kind.STORAGE_NBT) {
            var current = WatchUi.currentEntity(state);
            String scope = executor() != null ? text("editor.bound", executor().toString().substring(0, 8)).getString()
                : current == null ? text("editor.no_entity").getString()
                : text("editor.following", current.name() + " #" + current.uuid().toString().substring(0, 8)).getString();
            WatchUi.line(graphics, font, scope, left + 8, y, panelWidth - 16, MUTED);
        }
        retry.visible = retry.active = false;
        if (!valid || !state.isPaused()) {
            state.watchEditor().cancel();
            WatchUi.line(graphics, font, text(state.isPaused() ? "editor.preview_hint" : "editor.preview_running").getString(),
                left + 8, y + 16, panelWidth - 16, MUTED);
            return;
        }
        var spec = specification();
        state.watchEditor().request(WatchUi.pause(state), state.selectedPauseSourceIndex(),
            new WatchEditorQuery(WatchEditorQuery.Mode.PREVIEW, kind, spec.target(), spec.path(), spec.executor(), "", 0));
        var page = state.watchEditor().page();
        String value;
        if (page == null) value = text("editor.loading").getString();
        else {
            WatchResult result = page.preview();
            value = result != null && result.status() == WatchResult.Status.VALUE ? result.value()
                : WatchFormatting.status(result == null ? page.status() : result.status()).getString();
            retry.visible = retry.active = state.watchEditor().timedOut()
                || page.status() == WatchResult.Status.ERROR || page.status() == WatchResult.Status.UNAVAILABLE;
        }
        WatchUi.line(graphics, font, text("editor.preview", value).getString(), left + 8, y + 16,
            panelWidth - (retry.visible ? 76 : 16), TEXT);
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
    @Override public void onClose() { rememberDraft(); Minecraft.getInstance().gui.setScreen(new CodonScreen(input, overlay)); }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
