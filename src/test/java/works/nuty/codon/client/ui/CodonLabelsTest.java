package works.nuty.codon.client.ui;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.WatchSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodonLabelsTest {
    @Test
    void englishAndKoreanKeysAndPlaceholdersAgree() throws Exception {
        var english = translations("en_us");
        var korean = translations("ko_kr");
        assertEquals(english.keySet(), korean.keySet());
        var placeholder = Pattern.compile("%(?:\\d+\\$)?[sd]");
        for (String key : english.keySet()) {
            assertEquals(placeholder.matcher(english.get(key)).results().map(match -> match.group()).toList(),
                placeholder.matcher(korean.get(key)).results().map(match -> match.group()).toList(), key);
        }
        assertEquals("Breakpoint limit reached", english.get("command.codon.breakpoint.error.limit"));
        assertEquals("중단점 개수 제한에 도달했습니다", korean.get("command.codon.breakpoint.error.limit"));
    }

    @Test
    void watchKindPrefixesTranslateWithoutChangingIdentifiers() throws Exception {
        var previous = Language.getInstance();
        var method = WatchDetailsScreen.class.getDeclaredMethod("expression", WatchSpec.class);
        method.setAccessible(true);
        var specs = List.of(new WatchSpec(WatchSpec.Kind.SCORE, "points", ""),
            new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Health"),
            new WatchSpec(WatchSpec.Kind.ENTITY_NBT, "", "Pos[0]"),
            new WatchSpec(WatchSpec.Kind.STORAGE_NBT, "demo:state", "counter"));
        try {
            for (String language : List.of("en_us", "ko_kr")) {
                var values = translations(language);
                Language.inject(new Language() {
                    @Override public String getOrDefault(String key, String fallback) { return values.getOrDefault(key, fallback); }
                    @Override public boolean has(String key) { return values.containsKey(key); }
                    @Override public boolean isDefaultRightToLeft() { return false; }
                    @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return previous.getVisualOrder(text); }
                });
                var expected = language.equals("en_us")
                    ? List.of("Score: points", "Entity NBT: Health", "Entity NBT: Pos[0]", "Storage NBT: demo:state / counter")
                    : List.of("점수: points", "엔티티 NBT: Health", "엔티티 NBT: Pos[0]", "저장소 NBT: demo:state / counter");
                for (int i = 0; i < specs.size(); i++)
                    assertEquals(expected.get(i), ((Component) method.invoke(null, specs.get(i))).getString());
            }
        } finally { Language.inject(previous); }
    }

    private static Map<String, String> translations(String language) throws Exception {
        try (var reader = new InputStreamReader(Objects.requireNonNull(CodonLabelsTest.class.getResourceAsStream(
            "/assets/codon/lang/" + language + ".json")), StandardCharsets.UTF_8)) {
            var result = new HashMap<String, String>();
            JsonParser.parseReader(reader).getAsJsonObject().entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsString()));
            return result;
        }
    }
}
