package com.example.cardtable.network;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.network.packet.CardActionPacket;
import com.example.cardtable.network.packet.CardTableMembershipPacket;
import com.example.cardtable.network.packet.ContentPackListPacket;
import com.example.cardtable.network.packet.ContentPackRequestPacket;
import com.example.cardtable.network.packet.CursorSyncPacket;
import com.example.cardtable.network.packet.HandSyncPacket;
import com.example.cardtable.network.packet.TableNoticePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class NetworkHandler
{
    // "2": content pack handshake (request/list) added in the card content phase.
    // "3": open zone ids in the action packet.
    // "4": Perform replaces Draw/Shuffle — the action table is pack-declared.
    // "5": player areas removed — ZoneRef carries no seat position; the
    //      blank surface is one group-level zone and the hand target is
    //      resolved to the sender's own seat server-side.
    // "6": Move carries playRotation so a card leaving hand lands upright
    //      on the actor's rotated view.
    // "7": TableNoticePacket — shared table toasts (shuffle).
    // "8": CursorSyncPacket — seated-player mouse cursors on the table view.
    private static final String PROTOCOL_VERSION = "8";

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
        CHANNEL.registerMessage(nextPacketId++, TableNoticePacket.class,
                TableNoticePacket::encode,
                TableNoticePacket::decode,
                TableNoticePacket::handle);
        CHANNEL.registerMessage(nextPacketId++, CursorSyncPacket.class,
                CursorSyncPacket::encode,
                CursorSyncPacket::decode,
                CursorSyncPacket::handle);
    }

    public static void sendToServer(Object packet)
    {
        CHANNEL.sendToServer(packet);
    }
}
