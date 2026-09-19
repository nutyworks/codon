package works.nuty.codon.client.state;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PendingDisplayTest {
    @Test
    void retainsOnlyUntilTheExactGraceBoundary() {
        AtomicLong now = new AtomicLong();
        PendingDisplay<String> display = new PendingDisplay<>(now::get);
        display.retain("previous");

        now.set(PendingDisplay.GRACE_NANOS - 1);
        assertEquals("previous", display.resolve(null));
        now.set(PendingDisplay.GRACE_NANOS);
        assertNull(display.resolve(null));
    }

    @Test
    void repeatedInvalidationWithoutAReplyDoesNotExtendTheOriginalHold() {
        AtomicLong now = new AtomicLong();
        PendingDisplay<String> display = new PendingDisplay<>(now::get);
        display.retain("previous");

        now.set(100_000_000L);
        display.retain(null);
        now.set(PendingDisplay.GRACE_NANOS - 1);
        assertEquals("previous", display.resolve(null));
        now.set(PendingDisplay.GRACE_NANOS);
        assertNull(display.resolve(null));
    }

    @Test
    void freshValuesClearTheOldTimerIncludingZeroAndEmptyValues() {
        AtomicLong now = new AtomicLong();
        PendingDisplay<Integer> numbers = new PendingDisplay<>(now::get);
        numbers.retain(7);
        now.set(100_000_000L);
        assertEquals(0, numbers.resolve(0));
        assertNull(numbers.resolve(null), "a fresh zero clears the previous retained value");

        PendingDisplay<String> text = new PendingDisplay<>(now::get);
        text.retain("previous");
        assertEquals("", text.resolve(""));
        assertNull(text.resolve(null), "a fresh empty value also clears the previous retained value");
    }

    @Test
    void clearDropsTheHeldValueImmediately() {
        AtomicLong now = new AtomicLong();
        PendingDisplay<String> display = new PendingDisplay<>(now::get);
        display.retain("previous");
        display.clear();

        assertNull(display.resolve(null));
    }
}
