package works.nuty.bastion.core.support;

import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.port.DebuggerEventSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Test double that records the snapshots and resume notifications the engine publishes. */
public final class RecordingEventSink implements DebuggerEventSink {
    public final List<PauseSnapshot> pauses = new ArrayList<>();
    public int resumes = 0;
    public int breakpointChanges = 0;

    @Override
    public void paused(PauseSnapshot snapshot) {
        pauses.add(snapshot);
    }

    @Override
    public void resumed() {
        resumes++;
    }

    @Override
    public void breakpointsChanged(Set<BlockLocation> blockBreakpoints) {
        breakpointChanges++;
    }

    public PauseSnapshot lastPause() {
        return pauses.get(pauses.size() - 1);
    }
}
