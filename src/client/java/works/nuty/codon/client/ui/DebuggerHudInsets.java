package works.nuty.codon.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.ChatVisiblity;
import works.nuty.codon.client.state.DebuggerPreferences;
import works.nuty.codon.mixin.client.ChatComponentAccessor;

/** Vanilla GUI units are converted to Codon's independently scaled workspace. */
public final class DebuggerHudInsets {
    public static int bottom(DebuggerPreferences preferences) {
        var client = Minecraft.getInstance();
        if (client.gui.hud.isHidden()) return 0;
        int inset = 60; // Hotbar, experience, health/food and the usual armor/air rows.
        if (client.options.chatVisibility().get() != ChatVisiblity.HIDDEN) {
            var chat = client.gui.hud.getChat();
            var access = (ChatComponentAccessor) chat;
            var lines = access.codon$trimmedMessages();
            int visibleRows = 0;
            for (int row = 0; row < Math.min(lines.size(), chat.getLinesPerPage()); row++) {
                if (client.gui.hud.getGuiTicks() - lines.get(row).addedTime() < 200) visibleRows = row + 1;
            }
            if (visibleRows > 0) {
                inset = Math.max(inset, 40 + (int) Math.ceil(visibleRows * access.codon$lineHeight()
                    * client.options.chatScale().get()) + 4);
            }
        }
        return (int) Math.ceil(ScaledCodonScreen.scale(preferences).toLocal(inset));
    }

    private DebuggerHudInsets() { }
}
