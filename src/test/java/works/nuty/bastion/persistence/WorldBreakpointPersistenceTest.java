package works.nuty.bastion.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.FunctionId;
import works.nuty.bastion.core.model.FunctionLocation;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.port.DebuggerEventSink;
import works.nuty.bastion.core.port.ExecutionController;
import works.nuty.bastion.core.service.BreakpointRegistry;
import works.nuty.bastion.core.service.CallStack;
import works.nuty.bastion.core.service.DebuggerEngine;
import works.nuty.bastion.core.service.StepController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorldBreakpointPersistenceTest {
    @TempDir Path directory;
    private static final BlockLocation OVERWORLD = new BlockLocation(-12, 64, 8, "minecraft:overworld");
    private static final BlockLocation NETHER = new BlockLocation(-12, 64, 8, "minecraft:the_nether");
    private static final FunctionLocation FUNCTION = new FunctionLocation(new FunctionId("test", "nested/tick"), 27);

    @Test
    void mutationsSurviveAFreshProcessWithoutWaitingForShutdown() {
        Harness original = new Harness();
        original.persistence.openWorld(directory);
        original.engine.toggleBlockBreakpoint(OVERWORLD);
        original.engine.toggleBlockBreakpoint(NETHER);
        original.engine.toggleFunctionBreakpoint(FUNCTION);

        Harness reopened = new Harness();
        reopened.persistence.openWorld(directory);
        assertEquals(Set.of(OVERWORLD, NETHER), reopened.engine.blockBreakpoints());
        assertEquals(Set.of(FUNCTION), reopened.engine.functionBreakpoints());
        assertTrue(original.errors.isEmpty());
        assertTrue(reopened.errors.isEmpty());
        assertEquals(3, original.sink.changes, "both breakpoint types still notify the network sink");
    }

    @Test
    void switchingWorldsClearsTheOldRegistryAndRestoresOnlyThatWorld() {
        Harness harness = new Harness();
        Path first = directory.resolve("first");
        Path second = directory.resolve("second");
        harness.persistence.openWorld(first);
        harness.engine.toggleBlockBreakpoint(OVERWORLD);
        harness.engine.toggleFunctionBreakpoint(FUNCTION);
        harness.persistence.openWorld(second);
        assertFalse(harness.engine.hasBreakpoints());
        harness.engine.toggleBlockBreakpoint(NETHER);

        harness.persistence.closeWorld();
        assertFalse(harness.engine.hasBreakpoints());
        harness.persistence.openWorld(first);
        assertEquals(Set.of(OVERWORLD), harness.engine.blockBreakpoints());
        assertEquals(Set.of(FUNCTION), harness.engine.functionBreakpoints());
        harness.persistence.openWorld(second);
        assertEquals(Set.of(NETHER), harness.engine.blockBreakpoints());
        assertTrue(harness.engine.functionBreakpoints().isEmpty());
    }

    @Test
    void removedAndClearedBreakpointsDoNotReappear() {
        Harness harness = new Harness();
        harness.persistence.openWorld(directory);
        harness.engine.toggleBlockBreakpoint(OVERWORLD);
        harness.engine.toggleFunctionBreakpoint(FUNCTION);
        harness.engine.toggleFunctionBreakpoint(FUNCTION);
        harness.persistence.openWorld(directory);
        assertEquals(Set.of(OVERWORLD), harness.engine.blockBreakpoints());
        assertTrue(harness.engine.functionBreakpoints().isEmpty());
        harness.engine.clearBreakpoints();
        harness.persistence.closeWorld();
        harness.persistence.openWorld(directory);
        assertFalse(harness.engine.hasBreakpoints());
    }

    @Test
    void duplicateEntriesRemainOneBreakpoint() throws Exception {
        write("""
            {"version":1,"blocks":[
              {"dimension":"minecraft:overworld","x":-12,"y":64,"z":8},
              {"dimension":"minecraft:overworld","x":-12,"y":64,"z":8}],
             "functions":[{"function":"test:nested/tick","line":27},
                          {"function":"test:nested/tick","line":27}]}
            """);
        Harness harness = new Harness();
        harness.persistence.openWorld(directory);
        assertEquals(2, harness.engine.breakpointCount());
        assertTrue(harness.errors.isEmpty());
    }

    @Test
    void malformedOrUnsupportedDocumentsArePreservedWithoutPartialRestore() throws Exception {
        for (String invalid : List.of(
            "{broken", "null", "[]", "{\"version\":2,\"blocks\":[],\"functions\":[]}",
            "{\"version\":1,\"blocks\":null,\"functions\":[]}",
            """
                {"version":1,"blocks":[{"dimension":"minecraft:overworld","x":-12,"y":64,"z":8}],
                 "functions":[{"function":"test:nested/tick","line":0}]}
                """,
            """
                {"version":1,"blocks":[{"dimension":"minecraft:overworld","x":2147483648,"y":64,"z":8}],"functions":[]}
                """,
            """
                {"version":1,"blocks":[],"functions":[{"function":"bad id","line":1}]}
                """
        )) {
            write(invalid);
            Harness harness = new Harness();
            harness.persistence.openWorld(directory);
            assertFalse(harness.engine.hasBreakpoints(), invalid);
            assertEquals(1, harness.errors.size(), invalid);
            harness.engine.toggleBlockBreakpoint(NETHER);
            harness.persistence.closeWorld();
            assertEquals(invalid, Files.readString(file()), "failed load must not overwrite the original");
        }
    }

    @Test
    void failedWriteKeepsMemoryAndNetworkWorkingAndRetriesOnFlush() throws Exception {
        Harness harness = new Harness();
        harness.persistence.openWorld(directory);
        Path data = directory.resolve("data");
        Files.writeString(data, "temporary obstruction");
        harness.engine.toggleBlockBreakpoint(OVERWORLD);
        assertEquals(Set.of(OVERWORLD), harness.engine.blockBreakpoints());
        assertEquals(1, harness.sink.changes);
        assertEquals(1, harness.errors.size());

        Files.delete(data);
        harness.persistence.flush();
        Harness reopened = new Harness();
        reopened.persistence.openWorld(directory);
        assertEquals(Set.of(OVERWORLD), reopened.engine.blockBreakpoints());
        assertTrue(reopened.errors.isEmpty());
    }

    @Test
    void closingADamagedWorldDoesNotDisablePersistenceInTheNextWorld() throws Exception {
        write("not json");
        Harness harness = new Harness();
        harness.persistence.openWorld(directory);
        harness.engine.toggleBlockBreakpoint(OVERWORLD);
        Path healthy = directory.resolve("healthy");
        harness.persistence.openWorld(healthy);
        assertFalse(harness.engine.hasBreakpoints());
        harness.engine.toggleFunctionBreakpoint(FUNCTION);
        harness.persistence.openWorld(healthy);
        assertEquals(Set.of(FUNCTION), harness.engine.functionBreakpoints());
    }

    @Test
    void steppingAndContinuingRemainDistinctFromTerminalResumeThroughThePersistenceAdapter() {
        Harness harness = new Harness();
        harness.persistence.stepping();
        assertEquals(1, harness.sink.steps);
        assertEquals(0, harness.sink.resumes);
        harness.persistence.continued();
        assertEquals(1, harness.sink.continues);
        assertEquals(0, harness.sink.resumes);
        harness.persistence.resumed();
        assertEquals(1, harness.sink.resumes);
    }

    private Path file() { return directory.resolve("data").resolve(WorldBreakpointPersistence.FILE_NAME); }

    private void write(String json) throws Exception {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), json);
    }

    private static final class Harness {
        final BreakpointRegistry registry = new BreakpointRegistry();
        final List<Exception> errors = new ArrayList<>();
        final Sink sink = new Sink();
        final WorldBreakpointPersistence persistence = new WorldBreakpointPersistence(registry, sink, errors::add);
        final DebuggerEngine engine = new DebuggerEngine(registry, new StepController(), new CallStack(),
            resumed -> ExecutionController.ParkResult.RESUMED, persistence);
    }

    private static final class Sink implements DebuggerEventSink {
        int changes;
        int resumes;
        int steps;
        int continues;
        @Override public void paused(PauseSnapshot snapshot) { }
        @Override public void resumed() { resumes++; }
        @Override public void stepping() { steps++; }
        @Override public void continued() { continues++; }
        @Override public void breakpointsChanged(Set<BlockLocation> blocks) { changes++; }
    }
}
