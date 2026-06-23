package works.nuty.bastion.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.core.model.CallFrame;
import works.nuty.bastion.core.model.PauseSnapshot;

import java.util.List;

/**
 * Window that renders the debugger call stack (bottom frame first), each frame showing where it
 * paused and the command being executed. Reads from the synced {@link ClientDebuggerState}.
 */
public final class CallStackWindow extends Window {
    private static final int LINE_HEIGHT = 11;

    private final ClientDebuggerState state;

    public CallStackWindow(ClientDebuggerState state, double x, double y, double width, double height) {
        super(x, y, width, height);
        this.state = state;
        this.title = Component.translatable("bastion.ui.callstack.title");
    }

    @Override
    public void render(GuiGraphicsExtractor context, int mouseX, int mouseY) {
        super.render(context, mouseX, mouseY);

        PauseSnapshot snapshot = state.snapshot();
        if (!state.isPaused() || snapshot == null) {
            return;
        }

        int padding = 2;
        int bottomPadding = 10;
        int contentWidth = (int) this.width - padding * 2;

        context.enableScissor((int) x, (int) (y + HEADER_HEIGHT), (int) (x + width), (int) (y + height));

        int currentY = (int) (this.y + HEADER_HEIGHT + 2 - scrollY);

        // Snapshot frames are top-first; render bottom-first to match a conventional stack view.
        List<CallFrame> frames = snapshot.callStack();
        for (int f = frames.size() - 1; f >= 0; f--) {
            CallFrame frame = frames.get(f);

            Component header = Component.translatable(
                "bastion.debugger.breakpoint",
                ClientFormatting.sourceLocation(frame.location()).withStyle(ChatFormatting.GOLD)
            ).withStyle(ChatFormatting.RED);
            Component command = ClientFormatting.command(frame.command());

            List<FormattedCharSequence> lines = client.font.split(command, contentWidth - 10);
            int entryHeight = 24 + LINE_HEIGHT * (lines.size() - 1);

            if (currentY + entryHeight > y + HEADER_HEIGHT && currentY < y + height - bottomPadding) {
                context.fill((int) x + padding, currentY, (int) (x + width) - padding, currentY + entryHeight, 0x80000000);
                context.text(client.font, header, (int) x + padding + 5, currentY + 5, 0xFFFFFFFF, true);
                for (int i = 0; i < lines.size(); i++) {
                    context.text(client.font, lines.get(i), (int) x + padding + 5, currentY + 17 + i * LINE_HEIGHT, 0xFFFFFFFF, true);
                }
            }

            currentY += entryHeight + 3;
        }

        context.disableScissor();
    }
}
