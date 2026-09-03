package com.example.cardtable.network;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.network.packet.CardActionPacket;
import com.example.cardtable.network.packet.CardTableMembershipPacket;
import com.example.cardtable.network.packet.ContentPackListPacket;
import com.example.cardtable.network.packet.ContentPackRequestPacket;
import com.example.cardtable.network.packet.HandSyncPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class NetworkHandler
{
    // "2": content pack handshake (request/list) added in the card content phase.
    private static final String PROTOCOL_VERSION = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CardTableMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private static int nextPacketId;
    private static boolean registered;

    private NetworkHandler()
    {
    }

    public static void register()
    {
        if (registered)
        {
            return;
        }
        registered = true;
        CHANNEL.registerMessage(nextPacketId++, CardTableMembershipPacket.class,
                CardTableMembershipPacket::encode,
                CardTableMembershipPacket::decode,
                CardTableMembershipPacket::handle);
        CHANNEL.registerMessage(nextPacketId++, ContentPackRequestPacket.class,
                ContentPackRequestPacket::encode,
                ContentPackRequestPacket::decode,
                ContentPackRequestPacket::handle);
        CHANNEL.registerMessage(nextPacketId++, ContentPackListPacket.class,
                ContentPackListPacket::encode,
                ContentPackListPacket::decode,
                ContentPackListPacket::handle);
        CHANNEL.registerMessage(nextPacketId++, CardActionPacket.class,
                CardActionPacket::encode,
                CardActionPacket::decode,
                CardActionPacket::handle);
        CHANNEL.registerMessage(nextPacketId++, HandSyncPacket.class,
                HandSyncPacket::encode,
                HandSyncPacket::decode,
                HandSyncPacket::handle);
    }

    public static void sendToServer(Object packet)
    {
        CHANNEL.sendToServer(packet);
    }
}
