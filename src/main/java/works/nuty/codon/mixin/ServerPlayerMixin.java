package works.nuty.codon.mixin;

import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import works.nuty.codon.adapter.PausedStateSynchronizer;

@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin implements PausedStateSynchronizer {
    @Shadow private float lastSentHealth;
    @Shadow private int lastSentFood;
    @Shadow private boolean lastFoodSaturationZero;
    @Shadow private int lastSentExp;
    @Shadow private boolean postEffectsDirty;

    @Override
    public void codon$syncPausedState() {
        ServerPlayer player = (ServerPlayer) (Object) this;
        // broadcastChanges also fires inventory listeners (including advancement reward functions).
        // A remote snapshot leaves those gameplay callbacks for the next ordinary tick.
        player.inventoryMenu.sendAllDataToRemote();
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.sendAllDataToRemote();
        player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
        var food = player.getFoodData();
        player.connection.send(new ClientboundSetHealthPacket(player.getHealth(), food.getFoodLevel(), food.getSaturationLevel()));
        lastSentHealth = player.getHealth();
        lastSentFood = food.getFoodLevel();
        lastFoodSaturationZero = food.getSaturationLevel() == 0;
        player.connection.send(new ClientboundSetExperiencePacket(player.experienceProgress, player.totalExperience, player.experienceLevel));
        lastSentExp = player.totalExperience;
        player.getAdvancements().flushDirty(player, true);
        if (postEffectsDirty) player.sendPostEffects();
        // Preserve the current weather strength, including its frozen transition. Never run the
        // weather cycle or synthesize future ticks to make rain reach its eventual strength.
        var level = player.level();
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1)));
        player.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1)));
    }
}
