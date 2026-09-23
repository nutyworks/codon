package works.nuty.codon.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointCondition;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.core.port.DebuggerEventSink;
import works.nuty.codon.core.port.ExecutionController;
import works.nuty.codon.core.service.BreakpointRegistry;
import works.nuty.codon.core.service.CallStack;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.core.service.StepController;

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
    void disabledBreakpointSurvivesReopenUntilExplicitClear() {
        Harness harness = new Harness();
        harness.persistence.openWorld(directory);
        harness.engine.toggleBlockBreakpoint(OVERWORLD);
        harness.engine.toggleFunctionBreakpoint(FUNCTION);
        harness.engine.toggleFunctionBreakpoint(FUNCTION);
        harness.persistence.openWorld(directory);
        assertEquals(Set.of(OVERWORLD), harness.engine.blockBreakpoints());
        assertTrue(harness.engine.functionBreakpoints().isEmpty());
        assertEquals(2, harness.engine.breakpointCount(), "disabled definition remains available to re-enable");
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
    void versionTwoRestoresDisabledStageConditionsWithoutRetargetingTheirSavedCommand() throws Exception {
        String command = "execute as @s run say ready";
        BreakpointTarget target = BreakpointTarget.stage(new SourceLocation.Function(FUNCTION), 1, command);
        BreakpointCondition condition = BreakpointCondition.count(BreakpointCondition.Kind.CHANGED_COUNT,
            BreakpointCondition.Comparison.GE, 2);
        write("""
            {"version":2,"breakpoints":[{
              "type":"function","function":"test:nested/tick","line":27,
              "stage":1,"fingerprint":"%s","enabled":false,
              "condition":"CHANGED_COUNT","comparison":"GE","threshold":2
            }]}
            """.formatted(target.commandFingerprint()));

        Harness harness = new Harness();
        harness.persistence.openWorld(directory);

        assertEquals(List.of(new BreakpointDefinition(target, false, condition)), harness.engine.breakpointDefinitions());
        assertFalse(harness.engine.hasBreakpoints(), "a saved disabled definition must not activate debugging");
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
    void oversizedSavedDefinitionsAreRejectedAndTheSourceFileIsPreserved() throws Exception {
        String entry = "{\"dimension\":\"minecraft:overworld\",\"x\":0,\"y\":64,\"z\":0}";
        String oversized = "{\"version\":1,\"blocks\":["
            + String.join(",", java.util.Collections.nCopies(BreakpointRegistry.MAX_DEFINITIONS + 1, entry))
            + "],\"functions\":[]}";
        write(oversized);

        Harness harness = new Harness();
        harness.persistence.openWorld(directory);
        assertTrue(harness.engine.breakpointDefinitions().isEmpty());
        assertEquals(1, harness.errors.size());
        harness.persistence.closeWorld();
        assertEquals(oversized, Files.readString(file()));
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
