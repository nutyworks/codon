package works.nuty.codon.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static works.nuty.codon.core.model.BreakpointCondition.Comparison.GE;
import static works.nuty.codon.core.model.BreakpointCondition.Comparison.LT;
import static works.nuty.codon.core.model.BreakpointCondition.Kind.CHANGED;
import static works.nuty.codon.core.model.BreakpointCondition.Kind.INPUT_COUNT;
import static works.nuty.codon.core.model.BreakpointCondition.Kind.REMOVED_COUNT;

class BreakpointConditionDraftTest {
    @Test
    void modeSwitchesKeepEachModesChoicesAndReturningIsNotAChange() {
        var saved = BreakpointCondition.count(REMOVED_COUNT, GE, 3);
        var draft = BreakpointConditionDraft.of(saved);
        assertEquals(BreakpointConditionDraft.Mode.COUNT, draft.mode());
        assertFalse(draft.changedFrom(saved));

        var event = draft.withEvent(CHANGED);
        assertEquals(BreakpointCondition.event(CHANGED), event.condition());
        assertTrue(event.stopsAfter());
        var always = event.withMode(BreakpointConditionDraft.Mode.ALWAYS);
        assertEquals(BreakpointCondition.ALWAYS, always.condition());
        assertFalse(always.stopsAfter());

        var back = always.withMode(BreakpointConditionDraft.Mode.COUNT);
        assertEquals(saved, back.condition());
        assertFalse(back.changedFrom(saved));
        assertEquals(BreakpointCondition.count(INPUT_COUNT, LT, 3), back.withMetric(INPUT_COUNT).withComparison(LT).condition());
    }

    @Test
    void onlyWholeNumbersAreValidAndAnInvalidNumberCountsAsAChange() {
        var saved = BreakpointCondition.count(REMOVED_COUNT, GE, 0);
        var draft = BreakpointConditionDraft.of(saved);
        for (String text : new String[] {"", "-1", "1.5", "x", "+2", "1234567890"}) {
            var invalid = draft.withThreshold(text);
            assertFalse(invalid.valid(), text);
            assertTrue(invalid.changedFrom(saved), text);
            // Another mode does not read the number.
            assertTrue(invalid.withMode(BreakpointConditionDraft.Mode.ALWAYS).valid(), text);
        }
        assertEquals(7, draft.withThreshold("007").thresholdValue());
        assertFalse(draft.withThreshold("0").changedFrom(saved));
    }

    @Test
    void stepperStaysWithinZeroAndTheLongestNumber() {
        var draft = BreakpointConditionDraft.of(BreakpointCondition.ALWAYS).withMode(BreakpointConditionDraft.Mode.COUNT);
        assertEquals("0", draft.step(-1).threshold());
        assertEquals("1", draft.step(1).threshold());
        assertEquals("1", draft.withThreshold("oops").step(1).threshold());
        assertEquals("999999999", draft.withThreshold("999999999").step(1).threshold());
    }
}
