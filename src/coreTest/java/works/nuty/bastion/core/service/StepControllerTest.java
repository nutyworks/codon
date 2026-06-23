package works.nuty.bastion.core.service;

import org.junit.jupiter.api.Test;
import works.nuty.bastion.core.model.StepMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepControllerTest {
    private final StepController step = new StepController();

    @Test
    void noneNeverPauses() {
        assertFalse(step.isStepping());
        assertFalse(step.shouldPauseAt(0));
        assertFalse(step.shouldPauseAt(100));
    }

    @Test
    void intoPausesAtAnyDepth() {
        step.stepInto();
        assertTrue(step.isStepping());
        assertTrue(step.shouldPauseAt(0));
        assertTrue(step.shouldPauseAt(99));
    }

    @Test
    void overPausesAtSameOrShallowerDepthThanPause() {
        step.onPaused(5);
        step.stepOver();
        assertTrue(step.shouldPauseAt(5));
        assertTrue(step.shouldPauseAt(4));
        assertFalse(step.shouldPauseAt(6));
    }

    @Test
    void outPausesOnlyAtShallowerDepthThanPause() {
        step.onPaused(5);
        step.stepOut();
        assertFalse(step.shouldPauseAt(5));
        assertTrue(step.shouldPauseAt(4));
    }

    @Test
    void onPausedClearsModeAndRecordsDepth() {
        step.stepInto();
        step.onPaused(7);
        assertEquals(StepMode.NONE, step.mode());
        // A subsequent stepOver targets the newly recorded depth.
        step.stepOver();
        assertTrue(step.shouldPauseAt(7));
        assertFalse(step.shouldPauseAt(8));
    }

    @Test
    void clearStopsStepping() {
        step.stepInto();
        step.clear();
        assertFalse(step.isStepping());
    }
}
