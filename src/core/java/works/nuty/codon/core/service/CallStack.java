package works.nuty.codon.core.service;

import works.nuty.codon.core.model.CallFrame;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The live debugger call stack. As execution advances, frames at a depth greater than or equal
 * to an incoming frame are popped before the new frame is pushed — so the stack always reflects
 * the current execution path. The top of the stack is the most recently pushed frame.
 */
public final class CallStack {
    private final Deque<CallFrame> frames = new ArrayDeque<>();

    public synchronized void push(CallFrame frame) {
        while (!frames.isEmpty() && frame.depth() <= frames.peek().depth()) {
            frames.pop();
        }
        frames.push(frame);
    }

    /** An immutable snapshot, ordered top (most recent) first. */
    public synchronized List<CallFrame> frames() {
        return List.copyOf(frames);
    }

    public synchronized boolean isEmpty() {
        return frames.isEmpty();
    }

    public synchronized void clear() {
        frames.clear();
    }
}
