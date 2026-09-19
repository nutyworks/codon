package works.nuty.codon.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import works.nuty.codon.CodonMod;
import works.nuty.codon.adapter.DebuggerTaskQueue;
import works.nuty.codon.core.model.BlockLocation;
import works.nuty.codon.core.model.PauseReason;
import works.nuty.codon.core.model.SourceLocation;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * A real command-block chain changes several packet-backed world states.  Each assertion is made
 * at the next breakpoint, while the server is still parked, so a later server tick cannot hide a
 * missing synchronisation packet.
 */
@SuppressWarnings("UnstableApiUsage")
public final class DebuggerWorldSyncGameTest implements FabricClientGameTest {
    private static final int Y = 80;
    private static final BlockPos CHANGED_BLOCK = new BlockPos(20, Y, 0);
    private static final BlockPos SIGN_BLOCK = new BlockPos(22, Y, 0);
    private static final BlockPos LIGHT_PROBE = new BlockPos(25, Y, 0);
    private static final BlockPos OBSERVER_POS = new BlockPos(36, Y, 0);
    private static final BlockLocation[] CHAIN = {
        location(4), location(5), location(6), location(7), location(8),
        location(9), location(10), location(11), location(12), location(13),
        location(14), location(15), location(16), location(17), location(18)
    };

    private static final String[] COMMANDS = {
        "setblock 20 80 0 minecraft:gold_block",
        "summon minecraft:armor_stand 24 80 0 {Tags:[\"sync_target\"],NoGravity:1b}",
        "tp @e[tag=sync_target,limit=1] 28.0 81.0 0.0 45 0",
        "item replace entity @e[tag=sync_target,limit=1] armor.head with minecraft:diamond_helmet",
        "item replace entity @e[tag=sync_target,limit=1] armor.head with minecraft:air",
        "data merge entity @e[tag=sync_target,limit=1] {CustomName:'synced target'}",
        "attribute @e[tag=sync_target,limit=1] minecraft:scale base set 2",
        "data merge block 22 80 0 {front_text:{messages:[{text:'synced sign'},'','','']}}",
        "setblock 24 80 0 minecraft:glowstone",
        "item replace entity @a hotbar.0 with minecraft:diamond 3",
        "experience set @a 7 levels",
        "effect give @a minecraft:glowing 100 0 true",
        "damage @p 2 minecraft:generic",
        "kill @e[tag=sync_target,limit=1]",
        "time set 1000"
    };

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            var server = world.getServer().computeOnServer(s -> s);
            AtomicReference<UUID> observerId = new AtomicReference<>();
            setup(world, observerId);
            try {
                world.getServer().runCommand("setblock 3 80 0 minecraft:redstone_block");
                context.waitFor(client -> CodonClientMod.state().isPaused(), 200);
                require(locationOf(context).equals(CHAIN[0]), "the chain first pauses before the setblock command");

                // Arriving at block 5 proves block 4 ran, but the server remains parked before block 5.
                advanceTo(context, CHAIN[1]);
                waitForClient(context, client -> client.level.getBlockState(CHANGED_BLOCK).is(Blocks.GOLD_BLOCK),
                    "setblock is visible to the client at the following breakpoint");
                assertParkedAndStable(context, server, observerId.get());

                // Command 6 has teleported the summoned stand; command 7 has equipped it.
                advanceTo(context, CHAIN[3]);
                waitForClient(context, client -> {
                    var target = target(client).orElse(null);
                    return target != null && target.position().distanceToSqr(new Vec3(28, 81, 0)) < 0.01;
                }, "entity teleport is visible before the following command executes");
                assertParkedAndStable(context, server, observerId.get());

                advanceTo(context, CHAIN[4]);
                waitForClient(context, client -> {
                    var target = target(client).orElse(null);
                    return target != null && target.getItemBySlot(EquipmentSlot.HEAD).is(net.minecraft.world.item.Items.DIAMOND_HELMET);
                }, "entity equipment is visible before the following command executes");

                advanceTo(context, CHAIN[5]);
                waitForClient(context, client -> target(client).map(stand -> stand.getItemBySlot(EquipmentSlot.HEAD).isEmpty()).orElse(false),
                    "entity equipment removal is visible while parked");

                advanceTo(context, CHAIN[6]);
                waitForClient(context, client -> target(client).map(ArmorStand::getCustomName)
                    .map(net.minecraft.network.chat.Component::getString).filter("synced target"::equals).isPresent(),
                    "entity data merge custom name is visible while parked");

                advanceTo(context, CHAIN[7]);
                waitForClient(context, client -> target(client).map(stand -> stand.getAttributeValue(Attributes.SCALE) == 2.0).orElse(false),
                    "entity scale attribute update is visible while parked");

                advanceTo(context, CHAIN[8]);
                waitForClient(context, client -> client.level.getBlockEntity(SIGN_BLOCK) instanceof SignBlockEntity sign
                    && sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString().equals("synced sign"),
                    "sign block-entity text update is visible while parked");

                advanceTo(context, CHAIN[9]);
                waitForClient(context, client -> client.level.getBrightness(LightLayer.BLOCK, LIGHT_PROBE) > 0,
                    "new glowstone light is visible while parked");

                advanceTo(context, CHAIN[10]);
                waitForClient(context, client -> client.player.getInventory().getItem(0).is(net.minecraft.world.item.Items.DIAMOND)
                    && client.player.getInventory().getItem(0).getCount() == 3, "player inventory update is visible while parked");

                advanceTo(context, CHAIN[11]);
                waitForClient(context, client -> client.player.experienceLevel == 7, "player experience update is visible while parked");

                advanceTo(context, CHAIN[12]);
                waitForClient(context, client -> client.player.hasEffect(MobEffects.GLOWING), "player effect update is visible while parked");
                float healthBeforeDamage = context.computeOnClient(client -> client.player.getHealth());

                advanceTo(context, CHAIN[13]);
                waitForClient(context, client -> client.player.getHealth() == healthBeforeDamage - 2.0F,
                    "damage health update is visible while parked");

                advanceTo(context, CHAIN[14]);
                waitForClient(context, client -> target(client).isEmpty(), "kill removes the armor stand before the following command executes");

                advanceToCompletion(context);
                waitForClient(context, client -> overworldClockTicks(client) == 1000L,
                    "world time update is visible at the completion pause");
                assertParkedAndStable(context, server, observerId.get());
            } finally {
                AtomicBoolean cleaned = new AtomicBoolean();
                DebuggerTaskQueue.execute(server, () -> {
                    CodonMod.engine().clearBreakpoints();
                    CodonMod.engine().resetSession();
                    cleaned.set(true);
                });
                context.waitFor(client -> cleaned.get(), 200);
            }
        }
    }

    private static void setup(TestSingleplayerContext world, AtomicReference<UUID> observerId) {
        world.getServer().runOnServer(server -> {
            var player = server.getPlayerList().getPlayers().getFirst();
            server.getPlayerList().op(player.nameAndId(),
                Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER), Optional.empty());
            CodonMod.engine().clearBreakpoints();
        });
        world.getServer().runCommand("gamemode creative @a");
        world.getServer().runCommand("fill 3 79 -1 25 79 7 minecraft:stone");
        world.getServer().runCommand("tp @a 5.5 80.0 5.5");
        world.getServer().runCommand("setblock 22 80 0 minecraft:oak_sign");
        world.getServer().runCommand("summon minecraft:armor_stand 36 80 0 {NoGravity:1b,Tags:[\"sync_observer\"]}");
        for (int i = 0; i < COMMANDS.length; i++) {
            String block = i == 0 ? "minecraft:command_block[facing=east]" : "minecraft:chain_command_block[facing=east]";
            world.getServer().runCommand("setblock " + (4 + i) + " 80 0 " + block
                + "{Command:\"" + COMMANDS[i].replace("\"", "\\\"") + "\",auto:" + (i == 0 ? "0b" : "1b") + "}");
        }
        world.getServer().runOnServer(server -> {
            for (String command : COMMANDS) {
                var parsed = server.getCommands().getDispatcher().parse(command, server.createCommandSourceStack());
                require(!parsed.getReader().canRead(), "the fixture command parses completely: " + command);
            }
            var observer = server.overworld().getEntitiesOfClass(ArmorStand.class,
                new net.minecraft.world.phys.AABB(OBSERVER_POS).inflate(1)).stream().findFirst().orElseThrow();
            observerId.set(observer.getUUID());
            CodonMod.engine().toggleBlockBreakpoint(CHAIN[0]);
        });
        world.getServer().runCommand("gamemode survival @a");
    }

    private static void advanceTo(ClientGameTestContext context, BlockLocation expected) {
        for (int attempt = 0; attempt < 16; attempt++) {
            if (locationOf(context).equals(expected)) return;
            long previous = context.computeOnClient(client -> CodonClientMod.state().snapshot().pauseId());
            context.getInput().pressKey(InputConstants.KEY_F9);
            context.waitFor(client -> CodonClientMod.state().isPaused()
                && CodonClientMod.state().snapshot().pauseId() != previous, 200);
        }
        throw new AssertionError("did not reach breakpoint " + expected + "; stopped at " + locationOf(context));
    }

    private static void advanceToCompletion(ClientGameTestContext context) {
        for (int attempt = 0; attempt < 16; attempt++) {
            if (context.computeOnClient(client -> CodonClientMod.state().snapshot().reason() == PauseReason.EXECUTION_COMPLETE)) return;
            long previous = context.computeOnClient(client -> CodonClientMod.state().snapshot().pauseId());
            context.getInput().pressKey(InputConstants.KEY_F9);
            context.waitFor(client -> CodonClientMod.state().isPaused()
                && CodonClientMod.state().snapshot().pauseId() != previous, 200);
        }
        throw new AssertionError("the final command did not reach an execution-complete pause");
    }

    private static BlockLocation locationOf(ClientGameTestContext context) {
        SourceLocation location = context.computeOnClient(client -> CodonClientMod.state().snapshot().location());
        return ((SourceLocation.Block) location).block();
    }

    private static void assertParkedAndStable(ClientGameTestContext context, net.minecraft.server.MinecraftServer server, UUID observerId) {
        ServerTicks before = readTicks(context, server, observerId);
        context.waitTicks(5);
        ServerTicks after = readTicks(context, server, observerId);
        require(after.equals(before), "server game time and entity tick count remain frozen during a client-visible pause");
    }

    private static ServerTicks readTicks(ClientGameTestContext context, net.minecraft.server.MinecraftServer server, UUID observerId) {
        AtomicReference<ServerTicks> result = new AtomicReference<>();
        DebuggerTaskQueue.execute(server, () -> {
            var observer = server.overworld().getEntity(observerId);
            if (observer == null) throw new AssertionError("server observer entity is still present");
            result.set(new ServerTicks(server.overworld().getGameTime(), observer.tickCount));
        });
        context.waitFor(client -> result.get() != null, 200);
        return result.get();
    }

    private static void waitForClient(ClientGameTestContext context, Predicate<net.minecraft.client.Minecraft> condition,
                                      String description) {
        try {
            context.waitFor(condition::test, 200);
        } catch (AssertionError failure) {
            throw new AssertionError(description, failure);
        }
        require(context.computeOnClient(condition::test), description);
        CodonMod.LOGGER.info("Paused world sync: {}", description);
    }

    private static java.util.Optional<ArmorStand> target(net.minecraft.client.Minecraft client) {
        return client.level.getEntitiesOfClass(ArmorStand.class,
            new net.minecraft.world.phys.AABB(20, 75, -4, 32, 86, 4)).stream().findFirst();
    }

    private static long overworldClockTicks(net.minecraft.client.Minecraft client) {
        var clocks = client.level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK);
        return client.level.clockManager().getInstance(clocks.getOrThrow(WorldClocks.OVERWORLD)).totalTicks();
    }

    private static BlockLocation location(int x) {
        return new BlockLocation(x, Y, 0, "minecraft:overworld");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private record ServerTicks(long gameTime, int entityTicks) { }
}
