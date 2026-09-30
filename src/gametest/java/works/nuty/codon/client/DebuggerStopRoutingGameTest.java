package works.nuty.codon.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.adapter.McExecutionController;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.PauseReason;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Native outer Commands context; stop is replaced only in this disposable server dispatcher. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerStopRoutingGameTest implements FabricClientGameTest {
    private static final Vec3 POSITION = new Vec3(71, 80, 0);
    private static final BlockLocation BREAKPOINT = new BlockLocation(71, 80, 0, "minecraft:overworld");

    @Override
    public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            MinecraftServer server = world.getServer().computeOnServer(value -> value);
            Fixture fixture = world.getServer().computeOnServer(Fixture::new);
            try {
                for (boolean step : new boolean[]{true, false}) runScenario(context, server, fixture, step);
            } finally {
                AtomicBoolean restored = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().resetSession();
                    fixture.restore(server);
                    restored.set(true);
                });
                context.waitFor(client -> restored.get(), 200);
            }
        }
    }

    private static void runScenario(ClientGameTestContext context, MinecraftServer server, Fixture fixture, boolean step) {
        AtomicBoolean returned = new AtomicBoolean();
        AtomicBoolean submitted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var engine = CodonMod.engine();
        fixture.stops.set(0);
        fixture.terminals.set(0);
        fixture.prefixes.set(0);
        server.execute(() -> {
            try {
                engine.toggleBlockBreakpoint(BREAKPOINT);
                // performPrefixedCommand creates vanilla's real thread-local outer ExecutionContext.
                server.getCommands().performPrefixedCommand(fixture.source, "execute positioned ~ ~ ~ run say tail");
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                returned.set(true);
            }
        });
        try {
            context.waitFor(client -> engine.isPaused() && engine.currentSnapshot() != null, 200);
            require(engine.currentSnapshot().command().text().equals("execute positioned ~ ~ ~ run say tail"),
                "native execute command owns the first pause");
            // 26.3 exposes the run redirect as its own modifier stage between positioned and say.
            for (int stage = 0; stage < 3 && !atTerminal(); stage++) {
                long previousPause = engine.currentSnapshot().pauseId();
                DebuggerTaskQueue.execute(server, engine::stepInto);
                context.waitFor(client -> engine.isPaused() && engine.currentSnapshot().pauseId() != previousPause, 200);
            }
            var terminal = engine.currentSnapshot();
            require(terminal.executionFlows().stream().flatMap(flow -> flow.stages().stream())
                    .anyMatch(stage -> stage.terminal() && stage.command().equals(terminal.command())),
                "positioned consumed quota before a recorded terminal pause: " + terminal);
            DebuggerTaskQueue.execute(server, () -> {
                try {
                    require(engine.isPaused() && McExecutionController.isParked(), "mailbox starts at the real terminal pause");
                    if (step) engine.stepInto(); else engine.resume();
                    require(!engine.isPaused() && McExecutionController.isParked(), "control clears paused before this drain exits");
                    server.getCommands().performPrefixedCommand(fixture.source, "stop");
                    server.getCommands().performPrefixedCommand(fixture.source.withPermission(PermissionSet.NO_PERMISSIONS), "stop");
                    server.getCommands().performPrefixedCommand(fixture.source, "stopSomething");
                    server.getCommands().performPrefixedCommand(fixture.source, "say mailbox-tail");
                    submitted.set(true);
                } catch (Throwable problem) {
                    failure.set(problem);
                    submitted.set(true);
                }
            });
            context.waitFor(client -> submitted.get(), 200);
            if (step) {
                context.waitFor(client -> engine.isPaused() && engine.currentSnapshot().reason() == PauseReason.EXECUTION_COMPLETE, 200);
                DebuggerTaskQueue.execute(server, engine::resume);
            }
            context.waitFor(client -> returned.get(), 200);
            if (failure.get() != null) throw new AssertionError("native stop routing fixture failed", failure.get());
            require(fixture.stops.get() == 1, "authorized stop executes exactly once after " + (step ? "step" : "resume")
                + "; unauthorized stop stays rejected; actual=" + fixture.stops.get());
            require(fixture.terminals.get() == 0, "quota still suppresses both original and mailbox say terminals");
            require(fixture.prefixes.get() == 0, "stopSomething retains ordinary quota-bound routing");
            CodonMod.LOGGER.info("Native stop routing PASS: step={}, stop=1, terminal=0, stopSomething=0", step);
        } finally {
            DebuggerTaskQueue.execute(server, () -> {
                engine.clearBreakpoints();
                engine.resetSession();
            });
            context.waitFor(client -> returned.get() && !engine.isPaused(), 200);
        }
    }

    private static boolean atTerminal() {
        var snapshot = CodonMod.engine().currentSnapshot();
        return snapshot != null && snapshot.executionFlows().stream().flatMap(flow -> flow.stages().stream())
            .anyMatch(stage -> stage.terminal() && stage.command().equals(snapshot.command()));
    }

    private static final class Fixture {
        private final CommandSourceStack source;
        private final int originalLimit;
        private final List<SavedMap> originals = new ArrayList<>();
        private final AtomicInteger stops = new AtomicInteger();
        private final AtomicInteger terminals = new AtomicInteger();
        private final AtomicInteger prefixes = new AtomicInteger();

        @SuppressWarnings("unchecked")
        private Fixture(MinecraftServer server) {
            source = server.createCommandSourceStack().withPosition(POSITION).withSuppressedOutput();
            originalLimit = server.getGameRules().get(GameRules.MAX_COMMAND_SEQUENCE_LENGTH);
            var dispatcher = server.getCommands().getDispatcher();
            var root = dispatcher.getRoot();
            var stop = root.getChild("stop");
            var say = root.getChild("say");
            require(say != null && root.getChild("stopSomething") == null, "preserve vanilla command registrations");
            try {
                // Brigadier has no removeChild API. Save/restore its maps; never mutate original nodes.
                for (String name : new String[]{"children", "literals", "arguments"}) {
                    var field = CommandNode.class.getDeclaredField(name);
                    field.setAccessible(true);
                    var map = (Map<String, CommandNode<CommandSourceStack>>) field.get(root);
                    originals.add(new SavedMap(map, new LinkedHashMap<>(map)));
                    map.remove("stop");
                    map.remove("say");
                }
                // Integrated servers omit stop. Use the exact 26.3 StopCommand owner requirement
                // there; never register or invoke the real server-halting implementation.
                dispatcher.register(Commands.literal("stop").requires(stop == null
                    ? Commands.hasPermission(Commands.LEVEL_OWNERS) : stop.getRequirement())
                    .executes(command -> stops.incrementAndGet()));
                dispatcher.register(Commands.literal("say").requires(say.getRequirement()).then(
                    Commands.argument("message", StringArgumentType.greedyString()).executes(command -> terminals.incrementAndGet())));
                dispatcher.register(Commands.literal("stopSomething").executes(command -> prefixes.incrementAndGet()));
                server.getGameRules().set(GameRules.MAX_COMMAND_SEQUENCE_LENGTH, 1, server);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                restore(server);
                throw new AssertionError("install harmless command fixture", failure);
            }
        }

        private void restore(MinecraftServer server) {
            originals.forEach(saved -> { saved.map().clear(); saved.map().putAll(saved.original()); });
            server.getGameRules().set(GameRules.MAX_COMMAND_SEQUENCE_LENGTH, originalLimit, server);
        }

        private record SavedMap(Map<String, CommandNode<CommandSourceStack>> map,
                                Map<String, CommandNode<CommandSourceStack>> original) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
