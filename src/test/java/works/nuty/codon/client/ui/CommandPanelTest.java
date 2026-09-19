package works.nuty.codon.client.ui;

import io.netty.buffer.Unpooled;
import net.minecraft.locale.Language;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.CommandSnippet;
import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.PauseSource;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.model.Vec3d;
import works.nuty.codon.core.service.ExecutionFlowHistory;
import works.nuty.codon.core.service.ExecutionFlowRecorder;
import works.nuty.codon.network.ExecutionFlowSyncPayload;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandPanelTest {
    private Language previousLanguage;
    private final ExecutionFlowRecorder recorder = new ExecutionFlowHistory().start(1,
        new SourceLocation.Block(new BlockLocation(0, 64, 0, "minecraft:overworld")));
    private final long input = recorder.createContext(
        new PauseSource(new Vec3d(0, 64, 0), 0, 0, null, "minecraft:overworld"));

    @BeforeEach
    void loadModTranslations() throws IOException {
        previousLanguage = Language.getInstance();
        Map<String, String> translations = new HashMap<>();
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/assets/codon/lang/en_us.json"))) {
            Language.loadFromJson(stream, translations::put);
        }
        Language.inject(new Language() {
            @Override public String getOrDefault(String key, String fallback) {
                return translations.getOrDefault(key, fallback);
            }
            @Override public boolean has(String key) { return translations.containsKey(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) {
                return previousLanguage.getVisualOrder(text);
            }
        });
    }

    @AfterEach
    void restoreLanguage() { Language.inject(previousLanguage); }

    @Test
    void pendingAndAbandonedReturnRunOutputsRemainUnmeasuredAfterSync() {
        recorder.beginStage(CommandSnippet.plain("return run say ok"), List.of(input), 1, false);
        assertDisplay("1→...", "Execution contexts: 1 in → ... out");
        recorder.abandonStage();
        assertDisplay("1→...", "Execution contexts: 1 in → ... out");
    }

    @Test
    void measuredZeroOutputIsNotReplacedByAnEllipsis() {
        recorder.beginStage(CommandSnippet.plain("execute if entity @s run say ok"), List.of(input), 1, false);
        recorder.inputDropped(input);
        recorder.finishStage(0, 1);
        assertDisplay("1→0  −1", "Execution contexts: 1 in → 0 out · 1 filtered out");
    }

    @Test
    void truncatedDetailsPreserveMeasuredAggregateCounts() {
        recorder.beginStage(CommandSnippet.plain("execute as @e run say ok"), List.of(input), 1, false);
        recorder.markTruncated();
        recorder.finishStage(200, 0);
        assertDisplay("1→200", "Execution contexts: 1 in → 200 out");
    }

    @Test
    void terminalSummaryDistinguishesPendingResultsFromMeasuredZeroAfterSync() {
        recorder.beginStage(CommandSnippet.plain("say ok"), List.of(input), 1, true);
        assertDisplay("1→1", "Execution contexts: 1 · Runs: ... · Successes: ...");
        recorder.executionStarted();
        assertDisplay("1→1", "Execution contexts: 1 · Runs: 1 · Successes: ...");
        recorder.executionResult(false);
        assertDisplay("1→1", "Execution contexts: 1 · Runs: 1 · Successes: 0");
        recorder.executionStarted();
        recorder.executionResult(true);
        assertDisplay("1→1", "Execution contexts: 1 · Runs: 2 · Successes: 1");
    }

    @Test
    void terminalWithoutInputsShowsMeasuredZeroes() {
        recorder.beginStage(CommandSnippet.plain("say skipped"), List.of(), 0, true);
        assertDisplay("0→0", "Execution contexts: 0 · Runs: 0 · Successes: 0");
    }

    private void assertDisplay(String counts, String summary) {
        var payload = new ExecutionFlowSyncPayload(List.of(recorder.snapshot()));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ExecutionFlowSyncPayload.CODEC.encode(buffer, payload);
            var decoded = ExecutionFlowSyncPayload.CODEC.decode(buffer);
            assertEquals(payload, decoded);
            assertEquals(0, buffer.readableBytes());
            ExecutionFlowStage stage = decoded.flows().getFirst().stages().getLast();
            assertEquals(counts, CommandPanel.counts(stage));
            assertEquals(summary, CommandPanel.stageSummary(stage));
        } finally {
            buffer.release();
        }
    }
}
