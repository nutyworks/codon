package works.nuty.codon.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.input.InputManager;

import java.util.ArrayList;
import java.util.List;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Central home for debugger guidance, with wrapping and scrolling at every GUI scale. */
public final class DebuggerHelpScreen extends ScaledCodonScreen {
    private final @Nullable Screen parent;
    private final InputManager input;
    private record Line(FormattedCharSequence text, int color, @Nullable DebuggerIcon icon) { }
    private final List<Line> lines = new ArrayList<>();
    private static final String[] TOPICS = {"basics", "controls", "sources", "flow", "watches"};
    private final ScrollbarInput scrollbars = new ScrollbarInput();
    private int topic;
    private int offset;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private @Nullable DebuggerButton selectedTopicTab;
    private final List<DebuggerButton> topicTabs = new ArrayList<>();
    private DebuggerButton closeButton;
    private int focusedTopic;

    public DebuggerHelpScreen(@Nullable Screen parent, InputManager input) {
        super(Component.translatable("codon.ui.information"), preferencesFor(parent));
        this.parent = parent;
        this.input = input;
    }

    @Override
    protected void init() {
        scrollbars.release();
        scrollbars.beginFrame();
        panelWidth = Math.max(1, Math.min(420, width - 12));
        panelHeight = Math.max(1, Math.min(300, height - 12));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        lines.clear();
        int tabWidth = Math.max(1, (panelWidth - 16) / TOPICS.length);
        selectedTopicTab = null;
        topicTabs.clear();
        for (int index = 0; index < TOPICS.length; index++) {
            final int selected = index;
            var tab = new DebuggerButton();
            tab.configure(left + 8 + index * tabWidth, top + 27, tabWidth - 2, 20,
                help("tab." + TOPICS[index]), true, topic == index, false, false,
                () -> selectTopic(selected, tab));
            addRenderableWidget(tab);
            topicTabs.add(tab);
            if (topic == index) selectedTopicTab = tab;
        }
        var client = Minecraft.getInstance();
        switch (topic) {
            case 0 -> {
                entry("start", null, TEAL, keybind(input.breakpointKey.getTranslatedKeyMessage()), keybind(input.menuKey.getTranslatedKeyMessage()));
                entry("breakpoints", null, TEAL);
                entry("session", null, TEAL);
                entry("stop", null, AMBER);
                entry("camera", null, TEAL, keybind(input.menuKey.getTranslatedKeyMessage()),
                    keybind(client.options.keyUp.getTranslatedKeyMessage()), keybind(client.options.keyLeft.getTranslatedKeyMessage()),
                    keybind(client.options.keyDown.getTranslatedKeyMessage()), keybind(client.options.keyRight.getTranslatedKeyMessage()),
                    keybind(client.options.keyJump.getTranslatedKeyMessage()), keybind(client.options.keyShift.getTranslatedKeyMessage()),
                    keybind(client.options.keySprint.getTranslatedKeyMessage()));
                entry("keep_freecam", DebuggerIcon.FREECAM, TEAL, keybind(input.keepFreecamKey.getTranslatedKeyMessage()));
                if (input.hideUiKey != null) entry("hide_ui", null, TEAL, keybind(input.hideUiKey.getTranslatedKeyMessage()));
            }
            case 1 -> {
                for (InputManager.Control control : InputManager.Control.values()) {
                    DebuggerIcon icon = switch (control) {
                        case RESUME -> DebuggerIcon.CONTINUE;
                        case OVER -> DebuggerIcon.STEP_OVER;
                        case INTO -> DebuggerIcon.STEP_INTO;
                        case OUT -> DebuggerIcon.STEP_OUT;
                    };
                    add(Component.translatable(control.translationKey()).append("  ").append(keybind(input.keyLabel(control))), TEAL, icon);
                    add(help("control." + control.name().toLowerCase(java.util.Locale.ROOT)), TEXT, null);
                    blank();
                }
                entry("availability", null, TEAL);
                entry("keyboard", null, TEAL, keybind(Component.literal("Tab / Shift+Tab")),
                    keybind(Component.literal("↑ / ↓ / ← / →")), keybind(Component.literal("Enter / Space")));
                entry("scrolling", null, TEAL);
                entry("source_viewer", null, TEAL);
                entry("gizmo", DebuggerIcon.GIZMO_GROUPED, TEAL);
                entry("labels", DebuggerIcon.GIZMO_LABELS, TEAL);
                entry("details", DebuggerIcon.DETAILS_OPEN, TEAL);
                entry("opacity", null, TEAL, keybind(Component.literal("← / →")));
            }
            case 2 -> {
                entry("source", null, TEAL);
                entry("coordinates", null, TEAL);
                entry("move_camera", DebuggerIcon.FREECAM, TEAL);
                entry("created", DebuggerIcon.SOURCE_CREATED, GREEN);
                entry("changed", DebuggerIcon.SOURCE_CHANGED, PURPLE);
                entry("excluded", DebuggerIcon.SOURCE_EXCLUDED, RED);
                entry("offscreen", DebuggerIcon.OUTSIDE_VIEWPORT, AMBER);
                entry("uuid", DebuggerIcon.COPY_UUID, TEAL);
            }
            case 3 -> {
                entry("flow", null, TEAL);
                entry("counts", null, TEAL);
                entry("recording", null, AMBER);
                entry("history", null, TEAL);
                entry("stack", null, TEAL);
            }
            case 4 -> {
                entry("watch", null, TEAL);
                entry("types", null, TEAL);
                entry("score_holders", null, TEAL);
                entry("pin", DebuggerIcon.PIN, TEAL);
                entry("grouping", null, TEAL);
                entry("values", null, TEAL);
                entry("changes", null, AMBER);
                entry("watch_live", null, TEAL);
                entry("nbt", DebuggerIcon.EXPAND, TEAL);
                entry("nbt_pin", DebuggerIcon.PIN, TEAL);
                entry("saving", null, TEAL);
            }
            default -> throw new IllegalStateException("Unknown help topic");
        }
        offset = Math.clamp(offset, 0, maxOffset());
        var close = new DebuggerButton();
        close.configure(left + panelWidth - 56, top + 4, 50, 18,
            Component.translatable("gui.done"), true, false, false, false, this::onClose);
        closeButton = addRenderableWidget(close);
    }

    private static Component help(String key, Object... args) {
        return Component.translatable("codon.ui.help." + key, args);
    }

    private void entry(String key, @Nullable DebuggerIcon icon, int color, Object... args) {
        add(help(key + ".title"), color, icon);
        add(help(key + ".body", args), TEXT, null);
        blank();
    }

    private void blank() { lines.add(new Line(FormattedCharSequence.EMPTY, TEXT, null)); }

    private void add(Component text, int color, @Nullable DebuggerIcon icon) {
        boolean first = true;
        for (var line : font.split(text, Math.max(1, panelWidth - 44))) {
            lines.add(new Line(line, color, first ? icon : null));
            first = false;
        }
    }

    private int visibleLines() { return Math.max(1, (panelHeight - 78) / (font.lineHeight + 3)); }
    private int maxOffset() { return Math.max(0, lines.size() - visibleLines()); }

    private void selectTopic(int selected, DebuggerButton tab) {
        boolean restoreKeyboardFocus = getFocused() == tab && Minecraft.getInstance().getLastInputType().isKeyboard();
        topic = selected;
        focusedTopic = selected;
        offset = 0;
        rebuildWidgets();
        if (restoreKeyboardFocus && selectedTopicTab != null) setFocused(selectedTopicTab);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        scrollbars.beginFrame();
        graphics.fill(left, top, left + panelWidth, top + panelHeight, DebuggerTheme.color(SURFACE));
        graphics.outline(left, top, panelWidth, panelHeight, DebuggerTheme.color(BORDER));
        graphics.text(font, title, left + 8, top + 9, DebuggerTheme.foreground(TEAL), false);
        graphics.enableScissor(left + 6, top + 54, left + panelWidth - 6, top + panelHeight - 22);
        for (int row = 0; row < visibleLines() && offset + row < lines.size(); row++) {
            Line line = lines.get(offset + row);
            int y = top + 54 + row * (font.lineHeight + 3);
            if (line.icon() != null) line.icon().draw(graphics, left + 9, y - 1, DebuggerTheme.color(line.color()));
            graphics.text(font, line.text(), left + 26, y, DebuggerTheme.foreground(line.color()), false);
        }
        graphics.disableScissor();
        if (maxOffset() > 0) {
            int track = Math.max(1, panelHeight - 80);
            int thumb = Math.max(4, track * visibleLines() / lines.size());
            scrollbars.add("information", false, left + panelWidth - 5, top + 55, track, 2,
                thumb, offset, maxOffset(), value -> offset = value);
            int y = top + 55 + (track - thumb) * offset / maxOffset();
            graphics.fill(left + panelWidth - 5, y, left + panelWidth - 3, y + thumb, DebuggerTheme.color(TEAL));
        }
        scrollbars.endFrame();
        graphics.text(font, help("navigation", keybind(Component.literal("↑ / ↓ / PgUp / PgDn")),
            keybind(Component.literal("Esc"))), left + 8, top + panelHeight - 14, DebuggerTheme.foreground(MUTED), false);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && scrollbars.click(event.x(), event.y())) return true;
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && scrollbars.drag(event.x(), event.y())) return true;
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && scrollbars.release()) return true;
        return super.mouseReleased(event);
    }

    @Override
    public void removed() {
        scrollbars.release();
        super.removed();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        offset = Math.clamp(offset - (int) Math.signum(scrollY) * 3, 0, maxOffset());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_TAB) {
            int current = topicTabs.indexOf(getFocused());
            if (current >= 0) {
                focusedTopic = current;
                setFocused(closeButton);
            } else setFocused(topicTabs.get(focusedTopic));
            return true;
        }
        if (event.key() == InputConstants.KEY_HOME || event.key() == InputConstants.KEY_END) {
            offset = event.key() == InputConstants.KEY_HOME ? 0 : maxOffset();
            return true;
        }
        int delta = switch (event.key()) {
            case InputConstants.KEY_UP -> -1;
            case InputConstants.KEY_DOWN -> 1;
            case InputConstants.KEY_PAGEUP -> -visibleLines();
            case InputConstants.KEY_PAGEDOWN -> visibleLines();
            default -> 0;
        };
        if (delta != 0) {
            offset = Math.clamp(offset + delta, 0, maxOffset());
            return true;
        }
        return DebuggerNavigation.navigateWithin(event,
            topicTabs.contains(getFocused()) ? topicTabs : List.of(closeButton), getFocused(), this::setFocused)
            || super.keyPressed(event);
    }

    @Override
    public void onClose() { Minecraft.getInstance().gui.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean isInGameUi() { return true; }
}
