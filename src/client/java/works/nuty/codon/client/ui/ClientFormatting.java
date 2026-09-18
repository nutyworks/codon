package works.nuty.codon.client.ui;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.SourceLocation;

/**
 * Renders core value types into Minecraft chat components for the in-game UI. This is the
 * presentation-side counterpart to {@code SourceMapper} — it keeps component/styling concerns out
 * of the core.
 */
public final class ClientFormatting {
    private static final int DIM = 0xAAAAAA;
    private static final int BRIGHT = 0xFFFFFF;

    private ClientFormatting() {
    }

    public static MutableComponent sourceLocation(SourceLocation location) {
        return switch (location) {
            case SourceLocation.Player p ->
                Component.translatable("codon.debugger.paused.player", p.name());
            case SourceLocation.Block b ->
                Component.translatable("codon.debugger.paused.block", b.block().x(), b.block().y(), b.block().z());
            case SourceLocation.Function f ->
                Component.translatable("codon.debugger.paused.function", f.location().function().toString(), f.location().line());
        };
    }

    /** Renders the command text with the actively-executing range highlighted bright over a dim rest. */
    public static MutableComponent command(CommandSnippet snippet) {
        String text = snippet.text();
        int start = Math.max(0, Math.min(snippet.highlightStart(), text.length()));
        int end = Math.max(start, Math.min(snippet.highlightEnd(), text.length()));
        return Component.literal(text.substring(0, start)).withColor(DIM)
            .append(Component.literal(text.substring(start, end)).withColor(BRIGHT))
            .append(Component.literal(text.substring(end)));
    }
}
