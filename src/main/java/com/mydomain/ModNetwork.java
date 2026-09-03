package com.mydomain;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.UUID;
import java.util.function.Supplier;

public class ModNetwork {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(DamageToArmorMod.MOD_ID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void init() {
        CHANNEL.messageBuilder(SetHitboxModePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SetHitboxModePacket::encode)
                .decoder(SetHitboxModePacket::decode)
                .consumerMainThread(SetHitboxModePacket::handle)
                .add();

        CHANNEL.messageBuilder(SyncHitboxModePacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncHitboxModePacket::encode)
                .decoder(SyncHitboxModePacket::decode)
                .consumerMainThread(SyncHitboxModePacket::handle)
                .add();
    }
}

record SetHitboxModePacket(int mode) {
    static SetHitboxModePacket decode(FriendlyByteBuf buffer) {
        return new SetHitboxModePacket(buffer.readInt());
    }

    static void encode(SetHitboxModePacket packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.mode);
    }

    static void handle(SetHitboxModePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        ServerPlayer sender = contextSupplier.get().getSender();
        if (sender != null) {
            PlayerSizeHandler.setServerMode(sender, packet.mode);
        }
    }
}

record SyncHitboxModePacket(UUID playerId, int mode) {
    static SyncHitboxModePacket decode(FriendlyByteBuf buffer) {
        return new SyncHitboxModePacket(buffer.readUUID(), buffer.readInt());
    }

    static void encode(SyncHitboxModePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.playerId);
        buffer.writeInt(packet.mode);
    }

    static void handle(SyncHitboxModePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        PlayerSizeHandler.setClientMode(packet.playerId, packet.mode);
        ClientPlayerRefresher.refresh(packet.playerId);
    }
}
