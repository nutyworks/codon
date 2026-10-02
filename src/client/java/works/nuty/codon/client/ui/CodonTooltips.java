package works.nuty.codon.client.ui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;
import org.joml.Vector2i;

/** Text tooltips share the logical viewport of their owning Codon render pass. */
public final class CodonTooltips {
    private CodonTooltips() { }

    public static int wrapWidth(int viewportWidth) { return Math.max(1, Math.min(240, viewportWidth - 16)); }

    public static List<FormattedCharSequence> fit(Font font, List<? extends FormattedCharSequence> text,
                                                 int viewportWidth) {
        int width = wrapWidth(viewportWidth);
        var lines = new ArrayList<FormattedCharSequence>();
        for (var line : text) {
            boolean[] newline = {false};
            line.accept((index, style, codePoint) -> { if (codePoint == '\n') newline[0] = true; return true; });
            if (!newline[0] && font.width(line) <= width) { lines.add(line); continue; }
            // The input is already in visual order. Preserve that order and each style
            // when splitting; applying Language's bidi conversion again would reverse it.
            var formatted = Component.empty();
            line.accept((index, style, codePoint) -> {
                formatted.append(Component.literal(Character.toString(codePoint)).setStyle(style));
                return true;
            });
            for (var part : font.splitIgnoringLanguage(formatted, width))
                lines.add(sink -> StringDecomposer.iterateFormatted(part, Style.EMPTY, sink));
        }
        // Keep diagnostics intact: this may be their only readable presentation.
        // Extremely tall content needs a separate detail surface, not silent truncation.
        return List.copyOf(lines);
    }

    public static ClientTooltipPositioner withinViewport(ClientTooltipPositioner preferred) {
        return (width, height, mouseX, mouseY, tooltipWidth, tooltipHeight) -> {
            var point = preferred.positionTooltip(width, height, mouseX, mouseY, tooltipWidth, tooltipHeight);
            return new Vector2i(Math.clamp(point.x(), 4, Math.max(4, width - tooltipWidth - 4)),
                Math.clamp(point.y(), 4, Math.max(4, height - tooltipHeight - 4)));
        };
    }
}
