package works.nuty.codon.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.core.model.CallFrame;
import works.nuty.codon.core.model.PauseSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Window that renders the debugger call stack (bottom frame first), each frame showing where it
 * paused and the command being executed. New pauses reveal the active (last rendered) frame;
 * manual scrolling can still inspect callers. Reads from the synced {@link ClientDebuggerState}.
 */
public final class CallStackWindow extends Window {
    private static final int LINE_HEIGHT = 11;
    private static final int ENTRY_GAP = 3;

    private final ClientDebuggerState state;
    private @Nullable PauseSnapshot lastSnapshot;
    private int lastContentWidth = -1;
    private int lastViewportHeight = -1;

    public CallStackWindow(ClientDebuggerState state, double x, double y, double width, double height) {
        super(x, y, width, height);
        this.state = state;
        this.title = Component.translatable("codon.ui.callstack.title");
    }

    @Override
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        super.render(context, mouseX, mouseY);

        PauseSnapshot snapshot = state.snapshot();
        if (!state.isPaused() || snapshot == null) {
            lastSnapshot = null;
            return;
        }

        int padding = 2;
        int contentWidth = Math.max(1, (int) this.width - padding * 2);
        int contentTop = (int) (y + HEADER_HEIGHT + padding);
        int contentBottom = Math.max(contentTop, (int) (y + height - 6));
        int viewportHeight = contentBottom - contentTop;

        // Measure exactly what we render, including wrapped commands and the last line's height.
        List<FrameRow> rows = new ArrayList<>();
        int contentHeight = 0;
        List<CallFrame> frames = snapshot.callStack();
        for (int f = frames.size() - 1; f >= 0; f--) {
            CallFrame frame = frames.get(f);
            List<FormattedCharSequence> lines = client.font.split(
                ClientFormatting.command(frame.command()), Math.max(1, contentWidth - 10));
            int entryHeight = 17 + LINE_HEIGHT * Math.max(0, lines.size() - 1) + client.font.lineHeight + 3;
            rows.add(new FrameRow(frame, lines, entryHeight));
            contentHeight += entryHeight + ENTRY_GAP;
        }
        if (!rows.isEmpty()) contentHeight -= ENTRY_GAP;

        double maxScroll = Math.max(0, contentHeight - viewportHeight);
        scrollY = Math.max(0, Math.min(scrollY, maxScroll));
        if (!rows.isEmpty() && (snapshot != lastSnapshot
            || contentWidth != lastContentWidth || viewportHeight != lastViewportHeight)) {
            int activeHeight = rows.getLast().height();
            int activeTop = contentHeight - activeHeight;
            if (activeHeight > viewportHeight || scrollY > activeTop) {
                scrollY = activeTop;
            } else if (contentHeight > scrollY + viewportHeight) {
                scrollY = contentHeight - viewportHeight;
            }
            scrollY = Math.max(0, Math.min(scrollY, maxScroll));
        }
        lastSnapshot = snapshot;
        lastContentWidth = contentWidth;
        lastViewportHeight = viewportHeight;

        context.enableScissor((int) x + padding, contentTop, (int) (x + width) - padding, contentBottom);
        int currentY = contentTop - (int) scrollY;
        for (FrameRow row : rows) {
            CallFrame frame = row.frame();

            Component header = Component.translatable(
                "codon.debugger.breakpoint",
                ClientFormatting.sourceLocation(frame.location()).withStyle(ChatFormatting.GOLD)
            ).withStyle(ChatFormatting.RED);
            List<FormattedCharSequence> lines = row.lines();
            int entryHeight = row.height();

            if (currentY + entryHeight > contentTop && currentY < contentBottom) {
                context.fill((int) x + padding, currentY, (int) (x + width) - padding, currentY + entryHeight, 0x80000000);
                context.text(client.font, header, (int) x + padding + 5, currentY + 5, 0xFFFFFFFF, true);
                for (int i = 0; i < lines.size(); i++) {
                    context.text(client.font, lines.get(i), (int) x + padding + 5, currentY + 17 + i * LINE_HEIGHT, 0xFFFFFFFF, true);
                }
            }

            currentY += entryHeight + ENTRY_GAP;
        }

        context.disableScissor();
    }

    private record FrameRow(CallFrame frame, List<FormattedCharSequence> lines, int height) {}
}
