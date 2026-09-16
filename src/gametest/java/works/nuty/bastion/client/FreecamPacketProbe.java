package works.nuty.bastion.client;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Test-only observation of outbound gameplay packets; never cancels or changes a packet. */
public final class FreecamPacketProbe {
    private static volatile boolean recording;
    private static final ConcurrentLinkedQueue<String> packets = new ConcurrentLinkedQueue<>();

    public static void start() { packets.clear(); recording = true; }
    public static void stop() { recording = false; }
    public static List<String> packets() { return List.copyOf(packets); }

    public static void observe(Packet<?> packet) {
        if (recording && (packet instanceof ServerboundMovePlayerPacket || packet instanceof ServerboundMoveVehiclePacket
                || packet instanceof ServerboundPlayerInputPacket || packet instanceof ServerboundPlayerActionPacket
                || packet instanceof ServerboundPlayerCommandPacket || packet instanceof ServerboundPunchPacket
                || packet instanceof ServerboundUseItemPacket || packet instanceof ServerboundUseItemOnPacket
                || packet instanceof ServerboundInteractPacket || packet instanceof ServerboundSetCarriedItemPacket
                || packet instanceof ServerboundContainerClickPacket || packet instanceof ServerboundSetCreativeModeSlotPacket)) {
            packets.add(packet.getClass().getSimpleName());
        }
    }
}
