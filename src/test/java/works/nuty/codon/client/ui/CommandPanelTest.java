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
import works.nuty.codon.core.model.ExecutionFlowTrace;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.core.model.ExecutionFlowWarning;
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

import static org.junit.jupiter.api.Assertions.*;

class CommandPanelTest {
    @Test void warningCommandExcerptIsBoundedBeforeTooltipExpansion() {
        var warning = new ExecutionFlowWarning(ExecutionFlowWarning.Reason.CONTEXT_LIMIT, 0,
            CommandSnippet.plain("x".repeat(32_767)), 128, "Context detail limit reached");
        String text = CommandPanel.warningText(warning);
        assertTrue(text.length() < 1_024, "Repeated warnings must not expand the full command");
        assertTrue(text.contains("…"), "Omitted text must be visible to the reader");
        assertTrue(text.endsWith("Context detail limit reached"));
    }

    @Test void sharedCommandWarningsHaveBoundedStageAndFlowTooltipsWithoutLosingEvidence() {
        String command = "x" + " ".repeat(32_766);
        recorder.beginStage(new CommandSnippet(command, 0, 1), List.of(input), 1, false);
        var recorded = recorder.snapshot();
        var warning = new ExecutionFlowWarning(ExecutionFlowWarning.Reason.CONTEXT_LIMIT, 0,
            CommandSnippet.plain(command), 128, "Captured explanation");
        var warnings = java.util.Collections.nCopies(375, warning);
        var flow = new ExecutionFlowTrace(recorded.invocationId(), recorded.location(), recorded.stages(), true, warnings);
        var payload = new ExecutionFlowSyncPayload(List.of(flow));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ExecutionFlowSyncPayload.CODEC.encode(buffer, payload);
            var decoded = ExecutionFlowSyncPayload.CODEC.decode(buffer).flows().getFirst();
            assertEquals(flow, decoded);
            for (String detail : List.of(CommandPanel.stageDetails(decoded.stages().getFirst(), decoded),
                CommandPanel.warningDetails(decoded.warnings()))) {
                assertTrue(detail.length() <= CommandPanel.MAX_WARNING_TEXT);
                assertTrue(detail.contains("… +"), "Tooltip explicitly counts omitted warnings");
                assertTrue(detail.contains("Captured explanation"));
            }
            assertEquals(375, decoded.warnings().size());
            assertEquals(command, decoded.warnings().getLast().command().text());
        } finally { buffer.release(); }
    }

    @Test void warningExcerptKeepsUnicodeAndTheFirstOmittedStageIdentity() {
        var warning = new ExecutionFlowWarning(ExecutionFlowWarning.Reason.STAGE_LIMIT, 24,
            CommandSnippet.plain("a".repeat(255) + "😀"), 24, "");
        String text = CommandPanel.warningText(warning);
        assertTrue(text.contains("stage 25"));
        assertTrue(text.endsWith("a…"));
        assertFalse(Character.isSurrogate(text.charAt(text.length() - 2)));
    }

    @Test void unavailableTargetDoesNotCreateAToggleOrConditionAction() throws Exception {
        var state = new ClientDebuggerState();
        state.breakpoints().acceptPage(1, 0, true, List.of());
        var panel = new CommandPanel(state, () -> { });
        var toggle = CommandPanel.class.getDeclaredMethod("toggleExact", BreakpointTarget.class);
        toggle.setAccessible(true);
        assertDoesNotThrow(() -> toggle.invoke(panel, new Object[] {null}));
        var action = CommandPanel.class.getDeclaredMethod("conditionAction", DebuggerButton.class,
            String.class, ExecutionFlowTrace.class, BreakpointTarget.class, String.class, boolean.class, boolean.class);
        action.setAccessible(true);
        var button = new DebuggerButton();
        assertDoesNotThrow(() -> action.invoke(panel, button, "unavailable", null, null, "say test", false, true));
        assertFalse(panel.openContextMenu(button));
        assertTrue(state.breakpoints().definitions().isEmpty());
    }

    @Test void textCacheReusesStableFramesAndInvalidatesAllLayoutInputs() {
        var cache = new CommandPanel.TextCache<Object>();
        var command = CommandSnippet.plain("hello 😀 world");
        Object flow = new Object(), preview = new Object(), font = new Object(), language = new Object();
        int[] builds = {0};
        java.util.function.Supplier<Object> build = () -> { builds[0]++; return new Object(); };
        Object first = cache.get(command, flow, preview, font, language, 300, 30, 0, build);
        for (int frame = 0; frame < 100; frame++)
            assertSame(first, cache.get(command, flow, preview, font, language, 300, 30, 0, build));
        assertEquals(1, builds[0]);
        assertNotSame(first, cache.get(new CommandSnippet(command.text(), 1, 4), flow, preview, font, language, 300, 30, 0, build));
        Object previous = cache.get(command, flow, preview, font, language, 300, 30, 0, build);
        for (int change = 0; change < 7; change++) {
            Object next = cache.get(command, change == 0 ? new Object() : flow, change == 1 ? new Object() : preview,
                change == 2 ? new Object() : font, change == 3 ? new Object() : language,
                change == 4 ? 200 : 300, change == 5 ? 17 : 30, change == 6 ? 1 : 0, build);
            assertNotSame(previous, next);
            previous = next;
        }
        cache.clear();
        assertNotSame(previous, cache.get(command, flow, preview, font, language, 300, 30, 1, build));
    }

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
        recorder.beginStage(new CommandSnippet("execute as @e run say ok", 8, 13), List.of(input), 1, false);
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

    @Test
    void warningDetailsRoundTripAndNameTheActualStageAndLimit() {
        recorder.beginStage(new CommandSnippet("execute as @e run say ok", 8, 13), List.of(input), 1, false);
        recorder.markTruncated(ExecutionFlowWarning.Reason.CONTEXT_LIMIT, 128, "Context detail limit reached");
        ExecutionFlowSyncPayload payload = new ExecutionFlowSyncPayload(List.of(recorder.snapshot()));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ExecutionFlowSyncPayload.CODEC.encode(buffer, payload);
            var decoded = ExecutionFlowSyncPayload.CODEC.decode(buffer);
            assertEquals(payload, decoded);
            assertEquals("Context detail limit (128) — stage 1",
                CommandPanel.warningSummary(decoded.flows().getFirst()));
            assertEquals("Context detail limit (128) — stage 1: as @e\nContext detail limit reached",
                CommandPanel.warningText(decoded.flows().getFirst().warnings().getFirst()));
        } finally {
            buffer.release();
        }
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
