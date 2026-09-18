package works.nuty.codon.core.model;

/**
 * The text of the command at a call frame, plus the character range currently being executed
 * (so the presentation layer can highlight the active token without the core knowing anything
 * about chat-component styling). {@code highlightStart}/{@code highlightEnd} are indices into
 * {@link #text}.
 */
public record CommandSnippet(String text, int highlightStart, int highlightEnd) {
    public static CommandSnippet plain(String text) {
        return new CommandSnippet(text, 0, text.length());
    }
}
