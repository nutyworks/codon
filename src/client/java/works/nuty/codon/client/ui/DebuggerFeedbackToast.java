package works.nuty.codon.client.ui;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MusicToastDisplayState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientRequestFeedback;

/** Vanilla's topmost, wrapped toast stays readable in the HUD, Source and block editors. */
public final class DebuggerFeedbackToast extends SystemToast {
    private static final SystemToastId CONTROL_TOKEN = new SystemToastId();
    private static final SystemToastId BREAKPOINT_TOKEN = new SystemToastId();
    private static @Nullable ToastManager manager;
    private final ClientRequestFeedback feedback;
    private ClientRequestFeedback.Notice notice;

    private DebuggerFeedbackToast(ClientRequestFeedback feedback, ClientRequestFeedback.Notice notice) {
        super(token(notice.kind()), Component.translatable(notice.titleKey()), Component.translatable(notice.messageKey()));
        this.feedback = feedback;
        this.notice = notice;
    }

    public static void register(ClientDebuggerState state) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (manager == null) {
                manager = new ToastManager(client, client.options);
                manager.setMusicToastDisplayState(MusicToastDisplayState.NEVER);
            }
            for (var kind : ClientRequestFeedback.Kind.values()) {
                var notice = state.feedback().current(kind);
                if (notice == null) continue;
                var toast = manager.getToast(DebuggerFeedbackToast.class, token(kind));
                if (toast == null) manager.addToast(new DebuggerFeedbackToast(state.feedback(), notice));
                else if (toast.notice != notice) {
                    toast.notice = notice;
                    toast.reset(Component.translatable(notice.titleKey()), Component.translatable(notice.messageKey()));
                }
            }
        });
    }

    private static SystemToastId token(ClientRequestFeedback.Kind kind) {
        return kind == ClientRequestFeedback.Kind.CONTROL ? CONTROL_TOKEN : BREAKPOINT_TOKEN;
    }

    /** Feedback has its own slots; ordinary notifications retain their paused clocks and queue. */
    public static void extractRenderState(GuiGraphicsExtractor graphics) {
        if (manager != null) {
            manager.update();
            manager.extractRenderState(graphics);
        }
    }

    public static void clear() { if (manager != null) manager.clear(); }

    public ClientRequestFeedback.Notice notice() { return notice; }

    @Override public Visibility getWantedVisibility() {
        // Receipt-time expiry also clears queued notices, replacement and disconnect feedback.
        return feedback.current(notice.kind()) == notice && Minecraft.getInstance().level != null ? Visibility.SHOW : Visibility.HIDE;
    }
}
