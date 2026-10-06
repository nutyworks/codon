package works.nuty.codon.client.ui;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientRequestFeedback;

/** Vanilla's topmost, wrapped toast stays readable in the HUD, Source and block editors. */
public final class DebuggerFeedbackToast extends SystemToast {
    private static final SystemToastId TOKEN = new SystemToastId();
    private final ClientRequestFeedback feedback;
    private ClientRequestFeedback.Notice notice;

    private DebuggerFeedbackToast(ClientRequestFeedback feedback, ClientRequestFeedback.Notice notice) {
        super(TOKEN, Component.translatable(notice.titleKey()), Component.translatable(notice.messageKey()));
        this.feedback = feedback;
        this.notice = notice;
    }

    public static void register(ClientDebuggerState state) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            state.controlPending();
            state.breakpoints().expirePending();
            var notice = state.feedback().current();
            if (notice == null) return;
            var manager = client.gui.toastManager();
            var toast = manager.getToast(DebuggerFeedbackToast.class, TOKEN);
            if (toast == null) manager.addToast(new DebuggerFeedbackToast(state.feedback(), notice));
            else if (toast.notice != notice) {
                toast.notice = notice;
                toast.reset(Component.translatable(notice.titleKey()), Component.translatable(notice.messageKey()));
            }
        });
    }

    @Override public Visibility getWantedVisibility() {
        // Receipt-time expiry also clears queued notices, replacement and disconnect feedback.
        return feedback.current() == notice && Minecraft.getInstance().level != null ? Visibility.SHOW : Visibility.HIDE;
    }
}
