package works.nuty.codon.client.ui;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import works.nuty.codon.client.ui.layout.SourceSyntax;

import static works.nuty.codon.client.ui.DebuggerTheme.*;

/** Cached glyph geometry; only the visible slice is submitted to Minecraft's text renderer. */
final class SourceCodeLine {
    static final Style CODE_STYLE = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("codon", "code")));
    record Slice(Component text, int x) { }
    private final String source;
    private final List<SourceSyntax.Span> spans;
    private final int[] positions;
    private final int[] indices;
    private final int[] pixels;

    SourceCodeLine(String source, Font font, Map<Integer, Integer> glyphWidths) {
        this.source = source;
        spans = SourceSyntax.spans(source);
        positions = new int[source.length() + 1];
        int count = source.codePointCount(0, source.length());
        indices = new int[count + 1];
        pixels = new int[count + 1];
        int column = 0;
        int x = 0;
        for (int at = 0; at < source.length();) {
            int cp = source.codePointAt(at);
            indices[column] = at;
            pixels[column++] = x;
            int advance = glyphWidths.computeIfAbsent(cp, value -> font.width(plain(new String(Character.toChars(value)))));
            positions[at] = x;
            if (Character.charCount(cp) == 2) positions[at + 1] = x;
            at += Character.charCount(cp);
            x += advance;
            positions[at] = x;
        }
        indices[count] = source.length();
        pixels[count] = x;
    }

    static Component plain(String source) { return Component.literal(source).setStyle(CODE_STYLE); }
    int width() { return positions[source.length()]; }
    int x(int index) { return positions[Math.clamp(index, 0, source.length())]; }
    List<SourceSyntax.Span> spans() { return spans; }
    String source() { return source; }

    Slice slice(int offset, int width) {
        int first = Arrays.binarySearch(pixels, offset);
        if (first < 0) first = Math.max(0, -first - 2);
        int last = Arrays.binarySearch(pixels, offset + width);
        if (last < 0) last = Math.min(indices.length - 1, -last - 1);
        int start = indices[first];
        int end = indices[Math.max(first, last)];
        MutableComponent text = Component.empty().setStyle(CODE_STYLE);
        int at = start;
        for (SourceSyntax.Span span : spans) {
            if (span.end() <= start) continue;
            if (span.start() >= end) break;
            int from = Math.max(start, span.start());
            if (at < from) text.append(plain(source.substring(at, from)));
            int to = Math.min(end, span.end());
            text.append(Component.literal(source.substring(from, to)).setStyle(CODE_STYLE.withColor(color(span.kind()) & 0xFFFFFF)));
            at = to;
        }
        if (at < end) text.append(plain(source.substring(at, end)));
        return new Slice(text, pixels[first] - offset);
    }

    private static int color(SourceSyntax.Kind kind) {
        return switch (kind) {
            case COMMAND -> TEAL;
            case KEYWORD, MACRO -> PURPLE;
            case STRING -> GREEN;
            case COMMENT -> MUTED;
            case VALUE -> AMBER;
            case RESOURCE -> 0xFFB3D5FF;
            case ARGUMENT -> TEXT;
        };
    }
}
