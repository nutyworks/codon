package works.nuty.bastion.client;

import com.mojang.blaze3d.audio.Channel;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import works.nuty.bastion.client.state.ClientDebuggerState;
import works.nuty.bastion.client.testmixin.ChannelAccessor;
import works.nuty.bastion.client.testmixin.MusicManagerAccessor;
import works.nuty.bastion.client.testmixin.SoundEngineAccessor;
import works.nuty.bastion.core.model.BlockLocation;
import works.nuty.bastion.core.model.CommandSnippet;
import works.nuty.bastion.core.model.PauseReason;
import works.nuty.bastion.core.model.PauseSnapshot;
import works.nuty.bastion.core.model.SourceLocation;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Regression coverage for debugger audio pause using a synthetic synced debugger-state fixture.
 * It observes OpenAL from each channel's sound-thread callback, rather than reading it from the
 * client thread. A real audio device is required for this client GameTest.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerSoundPauseGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getClientLevel().waitForChunksRender();
            Fixture fixture = context.computeOnClient(DebuggerSoundPauseGameTest::prepare);
            try {
                context.waitTicks(4);
                context.runOnClient(client -> {
                    require(allPlaying(engine(client), fixture.existingSounds()),
                        "static game, music, UI, and streaming channels start before debugger pause");
                    require(channelState(engine(client), fixture.streamingSound()).streaming(),
                        "the streaming fixture owns an attached stream on its sound-thread channel");
                });

                context.runOnClient(client -> fixture.state().applyPause(pauseFixture(fixture.player())));
                context.waitTicks(3);
                context.runOnClient(client -> {
                    SoundEngineAccessor engine = engine(client);
                    require(allPaused(engine, fixture.existingSounds()),
                        "debugger pause pauses existing game, music, and UI OpenAL channels");
                    require(fixture.tickableSound().ticks() > 0,
                        "the tickable game sound advanced before debugger pause");
                    fixture.tickCountAtPause = engine.bastion$tickCount();
                    fixture.tickableTicksAtPause = fixture.tickableSound().ticks();
                    fixture.gameSecondsOffsetAtPause = channelState(engine, fixture.existingSounds().getFirst()).secondsOffset();
                    fixture.soundManager().playDelayed(fixture.delayedSound(), 10);
                    require(engine.bastion$queuedSounds().containsKey(fixture.delayedSound()),
                        "a delayed game sound is queued before its frozen engine deadline");
                    fixture.musicDelayAtPause = ((MusicManagerAccessor) fixture.music()).bastion$nextSongDelay();
                });

                context.waitTicks(15);
                context.runOnClient(client -> {
                    SoundEngineAccessor engine = engine(client);
                    require(engine.bastion$tickCount() == fixture.tickCountAtPause,
                        "debugger pause freezes SoundEngine tickCount and delayed-sound deadlines");
                    require(fixture.tickableSound().ticks() == fixture.tickableTicksAtPause,
                        "debugger pause freezes tickable sound updates");
                    require(close(channelState(engine, fixture.existingSounds().getFirst()).secondsOffset(),
                        fixture.gameSecondsOffsetAtPause),
                        "debugger pause freezes the real OpenAL static-channel AL_SEC_OFFSET");
                    require(engine.bastion$queuedSounds().containsKey(fixture.delayedSound()),
                        "a delayed game sound does not begin while the debugger is paused");
                    require(((MusicManagerAccessor) fixture.music()).bastion$nextSongDelay() == fixture.musicDelayAtPause,
                        "debugger pause freezes MusicManager scheduling");

                    // Screen closing calls the vanilla sound resume path; it must not release
                    // debugger-paused OpenAL channels.
                    client.setScreenAndShow(new Screen(net.minecraft.network.chat.Component.empty()) { });
                    client.setScreenAndShow(null);
                });
                context.waitTicks(2);
                context.runOnClient(client -> {
                    require(allPaused(engine(client), fixture.existingSounds()),
                        "screen close cannot bypass debugger audio pause");
                    SoundEngine.PlayResult uiResult = fixture.soundManager().play(fixture.uiDuringPause());
                    require(uiResult == SoundEngine.PlayResult.NOT_STARTED,
                        "new UI feedback is dropped during debugger pause instead of accumulating");
                    require(!engine(client).bastion$instanceToChannel().containsKey(fixture.uiDuringPause()),
                        "dropped UI feedback creates no channel to burst after resume");
                    fixture.state().applyResume();
                });

                context.waitTicks(12);
                context.runOnClient(client -> {
                    SoundEngineAccessor engine = engine(client);
                    require(allPlaying(engine, fixture.existingSounds()),
                        "debugger resume restarts the paused OpenAL channels");
                    require(engine.bastion$instanceToChannel().containsKey(fixture.delayedSound()),
                        "resume lets the preserved delayed game sound reach its deadline");
                    require(fixture.tickableSound().ticks() > fixture.tickableTicksAtPause,
                        "resume restarts tickable sound updates");
                });
            } finally {
                context.runOnClient(client -> cleanup(client, fixture));
            }
        }
    }

    private static Fixture prepare(Minecraft client) {
        ClientDebuggerState state = require(BastionClientMod.state(), "client debugger state is initialized");
        LocalPlayer player = require(client.player, "local player is available");
        state.reset();
        SoundInstance game = looping(SoundSource.PLAYERS);
        SoundInstance music = looping(SoundSource.MUSIC);
        SoundInstance ui = looping(SoundSource.UI);
        SoundInstance streaming = SimpleSoundInstance.forMusic(SoundEvents.MUSIC_MENU.value());
        SoundInstance delayed = looping(SoundSource.PLAYERS);
        TickingSound tickable = new TickingSound();
        SoundInstance uiDuringPause = SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F);
        var soundManager = client.getSoundManager();
        require(soundManager.play(game) != SoundEngine.PlayResult.NOT_STARTED, "game fixture sound starts");
        require(soundManager.play(music) != SoundEngine.PlayResult.NOT_STARTED, "music fixture sound starts");
        require(soundManager.play(ui) != SoundEngine.PlayResult.NOT_STARTED, "UI fixture sound starts");
        require(soundManager.play(streaming) != SoundEngine.PlayResult.NOT_STARTED, "streaming music fixture starts");
        soundManager.queueTickingSound(tickable);
        MusicManager musicManager = client.getMusicManager();
        return new Fixture(state, player, soundManager, musicManager, List.of(game, music, ui, streaming, tickable), delayed, uiDuringPause, tickable,
            streaming,
            0);
    }

    private static SoundInstance looping(SoundSource source) {
        return new SimpleSoundInstance(SoundEvents.NOTE_BLOCK_HARP.value().location(), source, 0.25F, 1.0F,
            RandomSource.create(), true, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
    }

    private static SoundEngineAccessor engine(Minecraft client) {
        return (SoundEngineAccessor) ((works.nuty.bastion.mixin.client.SoundManagerAccessor) client.getSoundManager())
            .bastion$soundEngine();
    }

    private static boolean allPlaying(SoundEngineAccessor engine, List<SoundInstance> sounds) {
        return sounds.stream().allMatch(sound -> channelState(engine, sound).state() == AL10.AL_PLAYING);
    }

    private static boolean allPaused(SoundEngineAccessor engine, List<SoundInstance> sounds) {
        return sounds.stream().allMatch(sound -> channelState(engine, sound).state() == AL10.AL_PAUSED);
    }

    private static ChannelState channelState(SoundEngineAccessor engine, SoundInstance sound) {
        ChannelAccess.ChannelHandle handle = require(engine.bastion$instanceToChannel().get(sound), "sound has a channel");
        CompletableFuture<ChannelState> result = new CompletableFuture<>();
        handle.execute(channel -> result.complete(observeOnSoundThread(channel)));
        return result.orTimeout(2, java.util.concurrent.TimeUnit.SECONDS).join();
    }

    private static ChannelState observeOnSoundThread(Channel channel) {
        ChannelAccessor accessor = (ChannelAccessor) channel;
        return new ChannelState(accessor.bastion$getState(),
            AL10.alGetSourcef(accessor.bastion$source(), AL11.AL_SEC_OFFSET), accessor.bastion$stream() != null);
    }

    private static PauseSnapshot pauseFixture(LocalPlayer player) {
        BlockLocation block = new BlockLocation(player.getBlockX(), player.getBlockY(), player.getBlockZ(),
            player.level().dimension().identifier().toString());
        return new PauseSnapshot(new SourceLocation.Block(block), CommandSnippet.plain("say sound fixture"), 0,
            List.of(), List.of(), PauseReason.BREAKPOINT);
    }

    private static void cleanup(Minecraft client, Fixture fixture) {
        fixture.state().reset();
        for (SoundInstance sound : fixture.existingSounds()) fixture.soundManager().stop(sound);
        fixture.soundManager().stop(fixture.delayedSound());
        fixture.soundManager().stop(fixture.uiDuringPause());
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) < 0.001F;
    }

    private static <T> T require(T value, String description) {
        if (value == null) throw new AssertionError(description);
        return value;
    }

    private record ChannelState(int state, float secondsOffset, boolean streaming) { }

    private static final class Fixture {
        private final ClientDebuggerState state;
        private final LocalPlayer player;
        private final net.minecraft.client.sounds.SoundManager soundManager;
        private final MusicManager music;
        private final List<SoundInstance> existingSounds;
        private final SoundInstance delayedSound;
        private final SoundInstance uiDuringPause;
        private final TickingSound tickableSound;
        private final SoundInstance streamingSound;
        private int tickCountAtPause;
        private int musicDelayAtPause;
        private int tickableTicksAtPause;
        private float gameSecondsOffsetAtPause;

        private Fixture(ClientDebuggerState state, LocalPlayer player, net.minecraft.client.sounds.SoundManager soundManager,
                        MusicManager music, List<SoundInstance> existingSounds, SoundInstance delayedSound,
                        SoundInstance uiDuringPause, TickingSound tickableSound, SoundInstance streamingSound, int tickCountAtPause) {
            this.state = state;
            this.player = player;
            this.soundManager = soundManager;
            this.music = music;
            this.existingSounds = existingSounds;
            this.delayedSound = delayedSound;
            this.uiDuringPause = uiDuringPause;
            this.tickableSound = tickableSound;
            this.streamingSound = streamingSound;
            this.tickCountAtPause = tickCountAtPause;
        }

        private ClientDebuggerState state() { return state; }
        private LocalPlayer player() { return player; }
        private net.minecraft.client.sounds.SoundManager soundManager() { return soundManager; }
        private MusicManager music() { return music; }
        private List<SoundInstance> existingSounds() { return existingSounds; }
        private SoundInstance delayedSound() { return delayedSound; }
        private SoundInstance uiDuringPause() { return uiDuringPause; }
        private TickingSound tickableSound() { return tickableSound; }
        private SoundInstance streamingSound() { return streamingSound; }
    }

    private static final class TickingSound extends AbstractSoundInstance implements TickableSoundInstance {
        private int ticks;

        private TickingSound() {
            super(SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.PLAYERS, RandomSource.create());
            this.volume = 0.25F;
            this.pitch = 1.0F;
            this.looping = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.relative = true;
        }

        @Override public boolean isStopped() { return false; }
        @Override public void tick() { ticks++; }
        private int ticks() { return ticks; }
    }
}
