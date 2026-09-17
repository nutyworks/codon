package works.nuty.bastion;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import works.nuty.bastion.adapter.DebuggerTaskQueue;
import works.nuty.bastion.adapter.McExecutionController;
import works.nuty.bastion.command.BastionCommand;
import works.nuty.bastion.network.BastionNetworking;
import works.nuty.bastion.network.NetworkDebuggerEventSink;
import works.nuty.bastion.persistence.WorldBreakpointPersistence;
import works.nuty.bastion.core.service.BreakpointRegistry;
import works.nuty.bastion.core.service.CallStack;
import works.nuty.bastion.core.service.DebuggerEngine;
import works.nuty.bastion.core.service.ExecutionFlowHistory;
import works.nuty.bastion.core.service.StepController;

/**
 * Common entry point and composition root. Constructs the Minecraft-free core and wires it to its
 * Minecraft adapters by hand (constructor injection — the hexagonal "DI" without a framework).
 *
 * <p>The wired {@link DebuggerEngine} is exposed through a single static accessor: this is the one
 * sanctioned global, the seam mixins reach through since they are instantiated by the mixin
 * framework and cannot receive injected dependencies.
 */
public final class BastionMod implements ModInitializer {
    public static final String MOD_ID = "bastion";
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
        McExecutionController executionController = new McExecutionController(() -> server);
        NetworkDebuggerEventSink eventSink = new NetworkDebuggerEventSink(() -> server);
        WorldBreakpointPersistence persistence = new WorldBreakpointPersistence(breakpoints, eventSink,
            failure -> LOGGER.warn("Could not persist Bastion world breakpoints", failure));

        DebuggerEngine wiredEngine = new DebuggerEngine(breakpoints, step, callStack, executionController, persistence, flows);
        engine = wiredEngine;
        executionFlows = flows;

        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            wiredEngine.resetSession();
            persistence.openWorld(s.getWorldPath(LevelResource.ROOT));
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerLifecycleEvents.BEFORE_SAVE.register((s, flush, force) -> persistence.flush());
        // Finish disk writes while the server still owns the world's session lock.
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> persistence.closeWorld());
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            wiredEngine.resetSession();
            DebuggerTaskQueue.clear(s);
            server = null;
        });
        ServerTickEvents.START_SERVER_TICK.register(DebuggerTaskQueue::drain);
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            DebuggerTaskQueue.drain(s);
            wiredEngine.onTickBoundary();
        });

        BastionNetworking.registerPayloadTypes();
        BastionNetworking.registerJoinSync(wiredEngine);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            BastionCommand.register(dispatcher, wiredEngine));
    }
}
