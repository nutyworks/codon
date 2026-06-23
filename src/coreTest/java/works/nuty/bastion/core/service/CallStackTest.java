package works.nuty.bastion.core.service;

import org.junit.jupiter.api.Test;
import works.nuty.bastion.core.model.CallFrame;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.SourceLocation;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallStackTest {
    private final CallStack stack = new CallStack();

    private static CallFrame frame(int depth) {
        return new CallFrame(
            depth,
            new SourceLocation.Player(new UUID(0L, 0L), "Steve"),
            CommandSnippet.plain("say " + depth)
        );
    }

    @Test
    void deeperFramesAccumulate() {
        stack.push(frame(0));
        stack.push(frame(1));
        stack.push(frame(2));
        assertEquals(List.of(2, 1, 0), depths(), "top (most recent) first");
    }

    @Test
    void pushingAtSameDepthReplacesTop() {
        stack.push(frame(0));
        stack.push(frame(1));
        stack.push(frame(1));
        assertEquals(List.of(1, 0), depths());
    }

    @Test
    void pushingAtShallowerDepthPopsDeeperFrames() {
        stack.push(frame(0));
        stack.push(frame(1));
        stack.push(frame(2));
        stack.push(frame(1));
        assertEquals(List.of(1, 0), depths());
    }

    @Test
    void clearEmptiesTheStack() {
        stack.push(frame(0));
        stack.clear();
        assertTrue(stack.isEmpty());
    }

    private List<Integer> depths() {
        return stack.frames().stream().map(CallFrame::depth).toList();
    }
}
