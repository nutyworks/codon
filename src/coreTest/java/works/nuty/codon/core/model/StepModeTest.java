package works.nuty.codon.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StepModeTest {
    @Test
    void hasTheFourStepModes() {
        assertEquals(4, StepMode.values().length);
    }
}
