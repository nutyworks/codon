package works.nuty.codon.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.client.gui.screens.inventory.CommandBlockEditScreen;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.client.input.InputManager;
import works.nuty.codon.client.network.ClientNetworking;
import works.nuty.codon.client.state.ClientBreakpointState;
import works.nuty.codon.client.state.ClientDebuggerState;
import works.nuty.codon.client.state.ClientRequestFeedback;
import works.nuty.codon.client.ui.CodonScreen;
import works.nuty.codon.client.ui.DebuggerOverlay;
import works.nuty.codon.client.ui.DebuggerFeedbackToast;
import works.nuty.codon.client.ui.FunctionSourceScreen;
import works.nuty.codon.client.testmixin.ToastManagerTestAccessor;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.BreakpointDefinition;
import works.nuty.codon.core.model.BreakpointTarget;
import works.nuty.codon.core.model.PauseSnapshot;
import works.nuty.codon.core.model.SourceLocation;
import works.nuty.codon.network.ControlRejectedPayload;
import works.nuty.codon.network.BreakpointEditResultPayload;

import java.util.Optional;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Real command-chain rejection transport; deliberately withheld replies verify post-send expiry. */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerRequestFeedbackGameTest implements FabricClientGameTest {
    private static final BlockLocation FIRST = new BlockLocation(4, 80, 0, "minecraft:overworld");
    private static final BlockLocation SECOND = new BlockLocation(5, 80, 0, "minecraft:overworld");
    private static final String OBJECTIVE = "request_feedback";
    private static final Set<SystemToast> renderedToasts = new HashSet<>();
    private static boolean observeDraws;
    private static boolean feedbackRendered;

    /** Test-only observer of the actual native toast rendering path. */
    public static void observeToastDraw(SystemToast toast) {
        if (!observeDraws) return;
        renderedToasts.add(toast);
        if (toast instanceof DebuggerFeedbackToast && toast.getWantedVisibility() == SystemToast.Visibility.SHOW)
            feedbackRendered = true;
    }

    @Override public void runTest(ClientGameTestContext context) {
        String oldLanguage = context.computeOnClient(client -> client.getLanguageManager().getSelected());
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            MinecraftServer server = world.getServer().computeOnServer(value -> value);
            configure(world);
            AtomicBoolean suppress = new AtomicBoolean();
            AtomicReference<Runnable> delayedReply = new AtomicReference<>();
            var original = context.computeOnClient(client -> installObserver(suppress, delayedReply));
            try {
                List<SystemToast> ordinary = context.computeOnClient(client -> {
                    var manager = client.gui.toastManager();
                    manager.clear(); // Isolated test-client fixture, before creating the five known notices.
                    observeDraws = true;
                    renderedToasts.clear();
                    var toasts = new ArrayList<SystemToast>();
                    for (int i = 0; i < 5; i++) {
                        var toast = new SystemToast(new SystemToast.SystemToastId(),
                            Component.literal("Ordinary toast " + (i + 1)), null);
                        toasts.add(toast);
                        manager.addToast(toast);
                    }
                    return List.copyOf(toasts);
                });
                waitToastAnimation(context);
                context.runOnClient(client -> require(renderedToasts.containsAll(ordinary)
                    && ((ToastManagerTestAccessor) client.gui.toastManager()).codon$occupiedSlots().cardinality() == 5,
                    "five real ordinary toasts render and occupy every vanilla slot before the debugger pause"));
                world.getServer().runCommand("setblock 3 80 0 minecraft:redstone_block");
                awaitPause(context, FIRST);
                PauseSnapshot first = context.computeOnClient(client -> state().snapshot());
                context.runOnClient(client -> {
                    client.setScreenAndShow(new CodonScreen(CodonClientMod.input(), new DebuggerOverlay(state())));
                    CodonClientMod.input().control(InputManager.Control.RESUME);
                });
                awaitPause(context, SECOND);
                PauseSnapshot second = context.computeOnClient(client -> state().snapshot());
                require(second.pauseId() != first.pauseId(), "native chain supplies a fresh stop ID");
                context.getInput().setCursorPos(0, 0);

                for (String locale : new String[]{"en_us", "ko_kr"}) {
                    language(context, locale);
                    suppress.set(true);
                    delayedReply.set(null);
                    context.runOnClient(client -> {
                        feedbackRendered = false;
                        state().applyPause(first); // Reproduce a stale client view of a real previous stop.
                        CodonClientMod.input().control(InputManager.Control.RESUME);
                        long id = state().controlRequestId();
                        CodonClientMod.input().control(InputManager.Control.OVER);
                        require(state().controlPending() && state().controlRequestId() == id,
                            "a repeated control cannot send while the first request is pending");
                    });
                    context.waitFor(client -> delayedReply.get() != null && state().feedback().current() != null
                        && state().feedback().current().messageKey().equals("codon.ui.control_timeout_detail"), 200);
                    context.runOnClient(client -> {
                        require(!state().controlPending() && state().snapshot() == first,
                            "a lost rejection expires without fabricating advancement or a rollback");
                        var timeout = state().feedback().current();
                        delayedReply.get().run(); // Deliver the same real rejection after its deadline.
                        require(state().feedback().current() == timeout, "late rejection cannot replace timeout feedback");
                        require(CodonMod.engine().currentSnapshot().pauseId() == second.pauseId(),
                            "the stale request never advances the current native stop");
                    });
                    waitToastAnimation(context);
                    requireOrdinaryToasts(context, ordinary);
                    context.takeScreenshot("codon-control-timeout-" + locale);
                    context.waitFor(client -> state().feedback().current() == null, 200);
                    waitToastAnimation(context);
                    context.takeScreenshot("codon-control-timeout-expired-" + locale);
                    requireOrdinaryToasts(context, ordinary);
                    context.runOnClient(client -> {
                        CodonMod.LOGGER.info("Occupied-toast feedback {}: slots=5, feedbackRendered={}, noticeExpired=true, ordinaryPreserved=true",
                            locale, feedbackRendered);
                        require(feedbackRendered, "timeout feedback must actually render before expiry with all five ordinary toast slots frozen");
                    });
                    suppress.set(false);
                }

                language(context, "en_us");
                for (var action : InputManager.Control.values()) {
                    context.runOnClient(client -> {
                        feedbackRendered = false;
                        state().applyPause(first);
                        CodonClientMod.input().control(action);
                    });
                    awaitMessage(context, "command.codon.error.stale_pause");
                    context.runOnClient(client -> require(!state().controlPending()
                        && state().snapshot() == first && CodonMod.engine().currentSnapshot().pauseId() == second.pauseId(),
                        "correlated stale rejection is visible and leaves the native stop unchanged: " + action));
                    if (action == InputManager.Control.RESUME) {
                        waitToastAnimation(context);
                        requireOrdinaryToasts(context, ordinary);
                        context.runOnClient(client -> require(feedbackRendered,
                            "rejection feedback actually renders while all ordinary slots remain occupied"));
                        context.takeScreenshot("codon-control-rejected-en_us");
                    }
                }
                context.runOnClient(client -> {
                    state().applyPause(second);
                    require(state().feedback().current() == null, "the replacement pause clears stale feedback");
                });

                var staleTarget = BreakpointTarget.stage(new SourceLocation.Block(FIRST), 0, "execute as @a run say changed");
                rejectInline(context, staleTarget, ClientBreakpointState.Result.STALE_SOURCE, "en_us");
                context.runOnClient(client -> client.setScreenAndShow(
                    new FunctionSourceScreen(client.gui.screen(), CodonClientMod.sources())));
                rejectInline(context, staleTarget, ClientBreakpointState.Result.STALE_SOURCE, "en_us-source");
                language(context, "ko_kr");
                context.waitFor(client -> client.level.getBlockEntity(new BlockPos(4, 80, 0)) instanceof CommandBlockEntity, 200);
                context.runOnClient(client -> {
                    var entity = (CommandBlockEntity) client.level.getBlockEntity(new BlockPos(4, 80, 0));
                    var editor = new CommandBlockEditScreen(entity);
                    client.setScreenAndShow(editor);
                    editor.updateGui();
                });
                rejectInline(context, staleTarget, ClientBreakpointState.Result.STALE_SOURCE, "ko_kr-block-editor");
                context.runOnClient(client -> client.setScreenAndShow(new CodonScreen(CodonClientMod.input(), new DebuggerOverlay(state()))));
                rejectInline(context, staleTarget, ClientBreakpointState.Result.STALE_SOURCE, "ko_kr");
                setOwner(context, server, false);
                rejectInline(context, staleTarget, ClientBreakpointState.Result.NO_PERMISSION, "ko_kr");
                setOwner(context, server, true);

                resumeAfterInlineRejection(context, staleTarget);
                world.getServer().runOnServer(value -> {
                    var player = value.getPlayerList().getPlayers().getFirst();
                    var score = value.getScoreboard().getPlayerScoreInfo(player, value.getScoreboard().getObjective(OBJECTIVE));
                    require(score != null && score.value() == 11, "recovery executes each native command exactly once");
                });
                context.runOnClient(client -> {
                    state().applyPause(second); // Cached stale view after execution has finished.
                    CodonClientMod.input().control(InputManager.Control.RESUME);
                });
                context.waitTicks(3);
                // The deliberately stale mirror also engages vanilla's integrated-server pause.
                // The engine is running, so the harness must drain its mailbox on the server thread.
                world.getServer().runOnServer(DebuggerTaskQueue::drain);
                awaitMessage(context, "command.codon.error.not_paused");
                waitToastAnimation(context);
                context.takeScreenshot("codon-control-not-paused-ko_kr");
                context.runOnClient(client -> {
                    state().reset();
                    require(state().feedback().current() == null && !state().controlPending(),
                        "disconnect reset clears feedback and pending input");
                });
                CodonMod.LOGGER.info("Request feedback PASS: native stale/not-paused replies, lost/late replies, all controls, EN/KO inline errors, recovery score=11");
            } finally {
                // Release the deliberate stale mirror before waiting for running-world cleanup.
                context.runOnClient(client -> {
                    state().reset();
                    observeDraws = false;
                    renderedToasts.clear();
                    client.gui.toastManager().clear(); // Reset the isolated test's notification fixture.
                    CodonClientMod.freecam().synchronize(client);
                    client.setScreenAndShow(null);
                });
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> cleaned.get(), 200);
                context.runOnClient(client -> {
                    ClientPlayNetworking.unregisterReceiver(ControlRejectedPayload.TYPE.id());
                    require(ClientPlayNetworking.registerReceiver(ControlRejectedPayload.TYPE, original), "restore rejection receiver");
                    state().reset();
                    CodonClientMod.freecam().synchronize(client);
                    client.setScreenAndShow(null);
                });
                language(context, oldLanguage);
            }
        }
    }

    private static void rejectInline(ClientGameTestContext context, BreakpointTarget target,
                                     ClientBreakpointState.Result result, String locale) {
        context.runOnClient(client -> {
            feedbackRendered = false;
            require(ClientNetworking.sendBreakpointEdit(state(), ClientBreakpointState.Action.TOGGLE,
                BreakpointDefinition.plain(target)), "send actual inline edit");
        });
        context.waitFor(client -> !state().breakpoints().pending(target) && state().breakpoints().error(target) == result
            && state().feedback().current() != null && state().feedback().current().messageKey().equals(
                "codon.breakpoint.error." + result.name().toLowerCase(java.util.Locale.ROOT)), 200);
        context.runOnClient(client -> require(state().breakpoints().get(target) == null
            && state().breakpoints().error(target) == result, "rejected edit does not create a breakpoint"));
        waitToastAnimation(context);
        context.runOnClient(client -> require(feedbackRendered, "inline rejection toast actually renders above the occupied slots"));
        context.takeScreenshot("codon-inline-rejected-" + result.name().toLowerCase(java.util.Locale.ROOT) + "-" + locale);
    }

    /** Send a real control in the edit reply's client frame, before its feedback can draw. */
    @SuppressWarnings("unchecked")
    private static void resumeAfterInlineRejection(ClientGameTestContext context, BreakpointTarget target) {
        AtomicReference<ClientRequestFeedback.Notice> rejection = new AtomicReference<>();
        var original = context.computeOnClient(client -> {
            var receiver = (ClientPlayNetworking.PlayPayloadHandler<BreakpointEditResultPayload>)
                ClientPlayNetworking.unregisterReceiver(BreakpointEditResultPayload.TYPE.id());
            require(receiver != null, "normal breakpoint result receiver exists");
            require(ClientPlayNetworking.registerReceiver(BreakpointEditResultPayload.TYPE, (payload, networkContext) -> {
                receiver.receive(payload, networkContext);
                networkContext.client().execute(() -> {
                    var notice = state().feedback().current();
                    require(notice != null && notice.messageKey().equals("codon.breakpoint.error.stale_source"),
                        "the actual edit rejection arrives before the control");
                    rejection.set(notice);
                    feedbackRendered = false;
                    CodonClientMod.input().control(InputManager.Control.RESUME);
                    require(state().controlPending() && state().feedback().current() == notice,
                        "sending a control cannot erase the rejected edit before its first draw");
                });
            }), "install connection-local edit/control ordering observer");
            return receiver;
        });
        try {
            context.runOnClient(client -> require(ClientNetworking.sendBreakpointEdit(state(),
                ClientBreakpointState.Action.TOGGLE, BreakpointDefinition.plain(target)), "send real rejected edit before Resume"));
            context.waitFor(client -> rejection.get() != null && !state().isPaused() && !state().isContinuing(), 200);
            waitToastAnimation(context);
            context.runOnClient(client -> require(state().feedback().current() == rejection.get() && feedbackRendered,
                "breakpoint rejection remains visible after actual authoritative advancement"));
            context.takeScreenshot("codon-inline-rejected-after-continue-ko_kr");
            CodonMod.LOGGER.info("Edit/control ordering PASS: native edit rejection, same-frame Resume send, authoritative advancement, feedback rendered");
        } finally {
            context.runOnClient(client -> {
                ClientPlayNetworking.unregisterReceiver(BreakpointEditResultPayload.TYPE.id());
                require(ClientPlayNetworking.registerReceiver(BreakpointEditResultPayload.TYPE, original), "restore breakpoint result receiver");
            });
        }
    }

    private static void configure(TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
            CodonMod.engine().clearBreakpoints();
            CodonMod.engine().toggleBlockBreakpoint(FIRST);
            CodonMod.engine().toggleBlockBreakpoint(SECOND);
        });
        world.getServer().runCommand("scoreboard objectives add " + OBJECTIVE + " dummy");
        world.getServer().runCommand("scoreboard players set @a " + OBJECTIVE + " 0");
        world.getServer().runCommand("gamemode creative @a");
        world.getServer().runCommand("tp @a 5 82 5 180 25");
        world.getServer().runCommand("fill 3 79 -1 6 79 1 minecraft:stone");
        world.getServer().runCommand("setblock 4 80 0 minecraft:command_block[facing=east]{Command:\"execute as @a run scoreboard players add @s "
            + OBJECTIVE + " 1\",auto:0b}");
        world.getServer().runCommand("setblock 5 80 0 minecraft:chain_command_block[facing=east]{Command:\"execute as @a run scoreboard players add @s "
            + OBJECTIVE + " 10\",auto:1b}");
    }

    @SuppressWarnings("unchecked")
    private static ClientPlayNetworking.PlayPayloadHandler<ControlRejectedPayload> installObserver(
            AtomicBoolean suppress, AtomicReference<Runnable> delayed) {
        var original = (ClientPlayNetworking.PlayPayloadHandler<ControlRejectedPayload>)
            ClientPlayNetworking.unregisterReceiver(ControlRejectedPayload.TYPE.id());
        require(original != null, "normal rejection receiver exists");
        require(ClientPlayNetworking.registerReceiver(ControlRejectedPayload.TYPE, (payload, networkContext) -> {
            if (suppress.get()) delayed.set(() -> original.receive(payload, networkContext));
            else original.receive(payload, networkContext);
        }), "install connection-local rejection observer");
        return original;
    }

    private static void setOwner(ClientGameTestContext context, MinecraftServer server, boolean owner) {
        AtomicBoolean changed = new AtomicBoolean();
        DebuggerTaskQueue.execute(server, () -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            if (owner) server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
            else server.getPlayerList().deop(player.nameAndId());
            changed.set(true);
        });
        context.waitFor(client -> changed.get(), 200);
    }

    private static void language(ClientGameTestContext context, String locale) {
        if (context.computeOnClient(client -> client.getLanguageManager().getSelected().equals(locale))) return;
        var reload = context.computeOnClient(client -> {
            client.getLanguageManager().setSelected(locale);
            return client.reloadResourcePacks();
        });
        context.waitFor(client -> reload.isDone() && client.gui.overlay() == null, 400);
    }

    private static void awaitPause(ClientGameTestContext context, BlockLocation location) {
        context.waitFor(client -> state().isPaused() && state().snapshot() != null
            && state().snapshot().location().equals(new SourceLocation.Block(location)), 200);
    }

    private static void awaitMessage(ClientGameTestContext context, String key) {
        context.waitFor(client -> state().feedback().current() != null && state().feedback().current().messageKey().equals(key), 200);
    }

    private static void waitToastAnimation(ClientGameTestContext context) {
        long started = System.nanoTime();
        context.waitFor(client -> System.nanoTime() - started >= 800_000_000L, 100);
        context.waitTicks(2);
    }

    private static void requireOrdinaryToasts(ClientGameTestContext context, List<SystemToast> ordinary) {
        context.runOnClient(client -> {
            var manager = client.gui.toastManager();
            require(((ToastManagerTestAccessor) manager).codon$occupiedSlots().cardinality() == 5,
                "feedback must not evict the five occupied ordinary toast slots");
            for (var toast : ordinary) require(manager.getToast(SystemToast.class, toast.getToken()) == toast
                && toast.getWantedVisibility() == SystemToast.Visibility.SHOW,
                "ordinary toast identity and frozen lifetime remain unchanged during feedback");
        });
    }

    private static ClientDebuggerState state() { return CodonClientMod.state(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
