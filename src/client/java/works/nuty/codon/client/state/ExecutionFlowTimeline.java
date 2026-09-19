package works.nuty.codon.client.state;

import works.nuty.codon.core.model.ExecutionFlowStage;
import works.nuty.codon.core.model.ExecutionFlowTrace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Consecutive visits to an invocation, ordered by actual stage observations across all traces. */
final class ExecutionFlowTimeline {
    private ExecutionFlowTimeline() { }

    record Visit(int flowIndex, List<Integer> stageIndices) {
        Visit { stageIndices = List.copyOf(stageIndices); }
    }

    private record Position(int flowIndex, int stageIndex, long order) { }

    static List<Visit> visits(List<ExecutionFlowTrace> flows) {
        List<Position> positions = new ArrayList<>();
        boolean ordered = true;
        for (int flowIndex = 0; flowIndex < flows.size(); flowIndex++) {
            List<ExecutionFlowStage> stages = flows.get(flowIndex).stages();
            for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
                long order = stages.get(stageIndex).observationOrder();
                ordered &= order >= 0;
                positions.add(new Position(flowIndex, stageIndex, order));
            }
        }
        // Trace storage order cannot reconstruct function entry/return. Keep explicit frame and
        // clause selection available, but do not present unknown chronology as an ordered path.
        if (!ordered) return List.of();
        positions.sort(Comparator.comparingLong(Position::order));
        List<Visit> result = new ArrayList<>();
        int flowIndex = -1;
        List<Integer> stages = new ArrayList<>();
        for (Position position : positions) {
            if (position.flowIndex() != flowIndex) {
                if (!stages.isEmpty()) result.add(new Visit(flowIndex, stages));
                flowIndex = position.flowIndex();
                stages.clear();
            }
            stages.add(position.stageIndex());
        }
        if (!stages.isEmpty()) result.add(new Visit(flowIndex, stages));
        return List.copyOf(result);
    }
}
