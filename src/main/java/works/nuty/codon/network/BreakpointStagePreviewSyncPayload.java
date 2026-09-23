package works.nuty.codon.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.NonNull;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.service.ExecutionFlowRecorder;

/** A bounded parse-only preview whose ranges use the exact saved command returned alongside it. */
public record BreakpointStagePreviewSyncPayload(long requestId, Status status, SourceLocation location,
                                                String savedCommand, List<StageSpan> stages)
    implements CustomPacketPayload {
    public enum Status { READY, NOT_FOUND, UNAUTHORIZED, INVALID }
    public record StageSpan(int index, int start, int end, boolean terminal) {
        public StageSpan {
            if (index < 0 || index >= ExecutionFlowRecorder.MAX_STAGES || start < 0 || end < start)
                throw new IllegalArgumentException("invalid stage preview span");
        }
    }

    public static final Type<BreakpointStagePreviewSyncPayload> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("codon", "breakpoint_stage_preview_sync"));
    public static final StreamCodec<FriendlyByteBuf, BreakpointStagePreviewSyncPayload> CODEC = StreamCodec.of((buf, payload) -> {
        buf.writeVarLong(payload.requestId); buf.writeEnum(payload.status);
        BreakpointStagePreviewWire.writeLocation(buf, payload.location);
        buf.writeUtf(payload.savedCommand, BreakpointStagePreviewWire.MAX_COMMAND_LENGTH);
        buf.writeVarInt(payload.stages.size());
        for (StageSpan stage : payload.stages) {
            buf.writeVarInt(stage.index); buf.writeVarInt(stage.start); buf.writeVarInt(stage.end); buf.writeBoolean(stage.terminal);
        }
    }, buf -> {
        long requestId = buf.readVarLong();
        Status status = buf.readEnum(Status.class);
        SourceLocation location = BreakpointStagePreviewWire.readLocation(buf);
        String command = buf.readUtf(BreakpointStagePreviewWire.MAX_COMMAND_LENGTH);
        int count = buf.readVarInt();
        if (count < 0 || count > ExecutionFlowRecorder.MAX_STAGES) throw new IllegalArgumentException("invalid stage preview count");
        List<StageSpan> stages = new ArrayList<>(count);
        for (int index = 0; index < count; index++)
            stages.add(new StageSpan(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
        return new BreakpointStagePreviewSyncPayload(requestId, status, location, command, stages);
    });

    public BreakpointStagePreviewSyncPayload {
        if (requestId <= 0) throw new IllegalArgumentException("invalid stage preview response");
        status = Objects.requireNonNull(status, "status");
        location = BreakpointStagePreviewWire.validateLocation(location);
        savedCommand = BreakpointStagePreviewWire.command(savedCommand);
        stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
        if (stages.size() > ExecutionFlowRecorder.MAX_STAGES) throw new IllegalArgumentException("too many stage preview spans");
        int previousEnd = -1;
        boolean terminal = false;
        for (int index = 0; index < stages.size(); index++) {
            StageSpan stage = stages.get(index);
            if (stage.index != index || stage.start < previousEnd || stage.end > savedCommand.length())
                throw new IllegalArgumentException("non-contiguous stage preview spans");
            if (stage.terminal && (terminal || index != stages.size() - 1))
                throw new IllegalArgumentException("invalid terminal stage preview span");
            previousEnd = stage.end;
            terminal = stage.terminal;
        }
        if (status == Status.READY) {
            if (savedCommand.isEmpty() || stages.isEmpty() || !terminal)
                throw new IllegalArgumentException("ready stage preview must be complete");
        } else if (!savedCommand.isEmpty() || !stages.isEmpty()) {
            throw new IllegalArgumentException("failed stage preview must be empty");
        }
    }

    @Override public @NonNull Type<? extends CustomPacketPayload> type() { return TYPE; }
}
