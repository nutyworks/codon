package works.nuty.codon.core.model;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferBudgetTest {
    @Test void acceptsEachExactLimitButNeverOneExtraEntryOrCharacter() {
        for (var limit : new TransferBudget.Limits[]{TransferBudget.WATCH_DEFINITIONS,
            TransferBudget.WATCH_CHANGES, TransferBudget.FUNCTION_LIST, TransferBudget.FUNCTION_SOURCE}) {
            var budget = new TransferBudget(limit, () -> 0);
            assertTrue(budget.accept(limit.entries(), limit.characters(), true));
            assertFalse(budget.accept(1, 0, true));
            assertFalse(budget.accept(0, 1, true));
            assertFalse(budget.accept(Integer.MAX_VALUE, Long.MAX_VALUE, true));
        }
    }

    @Test void countsPagesAndUsesAFixedDeadlineInsteadOfARefreshableLease() {
        AtomicLong now = new AtomicLong();
        var budget = new TransferBudget(TransferBudget.FUNCTION_LIST, now::get);
        assertFalse(budget.accept(0, 0, false));
        for (int i = 0; i < TransferBudget.MAX_PAGES; i++) assertTrue(budget.accept(1, 1, false));
        assertFalse(budget.accept(1, 1, true));
        budget.reset();
        assertTrue(budget.accept(1, 1, false));
        now.set(TransferBudget.TIMEOUT_NANOS - 1);
        assertTrue(budget.accept(1, 1, false));
        now.incrementAndGet();
        assertTrue(budget.expired());
        assertFalse(budget.accept(0, 0, true));
        budget.reset();
        assertTrue(budget.accept(0, 0, true));
    }
}
