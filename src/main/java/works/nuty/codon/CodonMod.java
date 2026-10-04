package works.nuty.codon;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.adapter.FunctionSourceRepository;
import works.nuty.codon.adapter.McExecutionController;
import works.nuty.codon.command.CodonCommand;
import works.nuty.codon.network.CodonNetworking;
import works.nuty.codon.network.NetworkDebuggerEventSink;
import works.nuty.codon.persistence.WorldBreakpointPersistence;
import works.nuty.codon.persistence.WorldWatchPersistence;
import works.nuty.codon.core.service.BreakpointRegistry;
import works.nuty.codon.core.service.CallStack;
import works.nuty.codon.core.service.DebuggerEngine;
import works.nuty.codon.core.service.ExecutionFlowHistory;
import works.nuty.codon.core.service.StepController;
import works.nuty.codon.core.model.FunctionId;
import works.nuty.codon.core.model.FunctionLocation;
import works.nuty.codon.core.model.FunctionSourceDocument;
import works.nuty.codon.core.model.SourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Common entry point and composition root. Constructs the Minecraft-free core and wires it to its
 * Minecraft adapters by hand (constructor injection — the hexagonal "DI" without a framework).
 *
 * <p>The wired {@link DebuggerEngine} is exposed through a single static accessor: this is the one
 * sanctioned global, the seam mixins reach through since they are instantiated by the mixin
 * framework and cannot receive injected dependencies.
 */
public final class CodonMod implements ModInitializer {
    public static final String MOD_ID = "codon";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private static @Nullable DebuggerEngine engine;
    private static @Nullable ExecutionFlowHistory executionFlows;

    private @Nullable MinecraftServer server;

    /** The wired engine, or {@code null} before {@link #onInitialize()} has run. */
    public static @Nullable DebuggerEngine engine() {
        return engine;
    }

    /** The execution-lifetime flow recorder shared by the engine and command mixins. */
    public static @Nullable ExecutionFlowHistory executionFlows() {
        return executionFlows;
    }

    @Override
    public void onInitialize() {
        BreakpointRegistry breakpoints = new BreakpointRegistry();
        StepController step = new StepController();
        CallStack callStack = new CallStack();
        ExecutionFlowHistory flows = new ExecutionFlowHistory();
        NetworkDebuggerEventSink eventSink = new NetworkDebuggerEventSink(() -> server);
        WorldBreakpointPersistence persistence = new WorldBreakpointPersistence(breakpoints, eventSink,
            failure -> LOGGER.warn("Could not persist Codon world breakpoints", failure));

        WorldWatchPersistence watches = new WorldWatchPersistence(
            failure -> LOGGER.warn("Could not persist Codon world watches", failure));
        McExecutionController executionController = new McExecutionController(() -> server, watches::expireTransfers);

        DebuggerEngine wiredEngine = new DebuggerEngine(breakpoints, step, callStack, executionController, persistence, flows);
        engine = wiredEngine;
        executionFlows = flows;

        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            eventSink.resetWatchChanges();
            wiredEngine.resetSession();
            persistence.openWorld(s.getWorldPath(LevelResource.ROOT));
            var owner = s.isSingleplayer() ? s.getSingleplayerProfile() : null;
            watches.openWorld(s.getWorldPath(LevelResource.ROOT), owner == null ? null : owner.id(),
                s.isSingleplayer() ? s.getWorldData().getSinglePlayerUUID() : null);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            revalidateFunctionStages(s, wiredEngine);
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((s, resources, success) -> {
            if (success) revalidateFunctionStages(s, wiredEngine);
        });
        ServerLifecycleEvents.BEFORE_SAVE.register((s, flush, force) -> { persistence.flush(); watches.flush(); });
        // Finish disk writes while the server still owns the world's session lock.
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { persistence.closeWorld(); watches.closeWorld(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            eventSink.resetWatchChanges();
            wiredEngine.resetSession();
            DebuggerTaskQueue.clear(s);
            server = null;
        });
        ServerTickEvents.START_SERVER_TICK.register(DebuggerTaskQueue::drain);
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            DebuggerTaskQueue.drain(s);
            watches.expireTransfers();
            wiredEngine.onTickBoundary();
        });

        CodonNetworking.registerPayloadTypes();
        CodonNetworking.registerRequests(wiredEngine, watches);
        CodonNetworking.registerJoinSync(wiredEngine, watches, eventSink);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            CodonCommand.register(dispatcher, wiredEngine));
    }

    /** A reload can change function line commands without moving their saved breakpoint targets. */
    private static void revalidateFunctionStages(MinecraftServer server, DebuggerEngine engine) {
        Map<FunctionId, Optional<FunctionSourceDocument>> documents = new HashMap<>();
        Map<FunctionLocation, String> commands = new HashMap<>();
        for (var definition : engine.breakpointDefinitions()) {
            if (definition.target().wholeCommand()
                || !(definition.target().location() instanceof SourceLocation.Function function)) continue;
            FunctionLocation location = function.location();
            Optional<FunctionSourceDocument> document = documents.computeIfAbsent(location.function(),
                id -> FunctionSourceRepository.read(server, id));
            String command = document.filter(source -> location.line() <= source.lines().size())
                .map(source -> source.lines().get(location.line() - 1).trim()).orElse("");
            commands.put(location, command);
        }
        engine.revalidateFunctionStages(commands);
    }
}
