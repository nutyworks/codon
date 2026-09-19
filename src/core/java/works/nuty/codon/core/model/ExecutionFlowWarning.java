package works.nuty.codon.core.model;

import java.util.Objects;

/** A specific reason why a recorded execution flow is incomplete. */
public record ExecutionFlowWarning(Reason reason, int stageIndex, CommandSnippet command, int limit, String detail) {
    public static final int MAX_DETAIL_LENGTH = 256;
    /** One first-occurrence warning per retained stage plus the first omitted stage. */
    public static final int MAX_WARNINGS = 375;

    public ExecutionFlowWarning {
        reason = Objects.requireNonNull(reason, "reason");
        command = Objects.requireNonNull(command, "command");
        detail = Objects.requireNonNull(detail, "detail");
        if (detail.length() > MAX_DETAIL_LENGTH) detail = detail.substring(0, MAX_DETAIL_LENGTH);
    }

    public enum Reason {
        CONTEXT_LIMIT, STAGE_LIMIT, EDGE_LIMIT, CALL_STACK_LIMIT, MISSING_CONTEXT, MAPPING_SHORTFALL,
        MODIFIER_RESULT_MISMATCH, UNSUPPORTED_MODIFIER, FORK_LIMIT, EXECUTION_ERROR, UNFINISHED_STAGE,
        CONTINUATION_NOT_RESUMED, UNSPECIFIED, COMMAND_LIMIT, QUEUE_LIMIT
    }
}
