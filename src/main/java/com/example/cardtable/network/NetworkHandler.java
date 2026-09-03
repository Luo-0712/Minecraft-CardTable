package com.example.cardtable.network;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.network.packet.CardTableMembershipPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class NetworkHandler
{
    private static final String PROTOCOL_VERSION = "1";

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
    }
}
