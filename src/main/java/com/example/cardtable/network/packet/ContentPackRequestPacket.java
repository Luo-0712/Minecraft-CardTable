package com.example.cardtable.network.packet;

import com.example.cardtable.content.ContentPack;
import com.example.cardtable.content.ContentPackHandshake;
import com.example.cardtable.content.ContentPackLoader;
import com.example.cardtable.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Server-to-client prompt sent right after login: reply with the local
 * content pack list so the server can verify content consistency.
 */
public record ContentPackRequestPacket()
{
    public static void encode(ContentPackRequestPacket packet, FriendlyByteBuf buffer)
    {
    }

    public static ContentPackRequestPacket decode(FriendlyByteBuf buffer)
    {
        return new ContentPackRequestPacket();
    }

    public static void handle(ContentPackRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // Only meaningful on the client; a server receiving this has a broken peer.
            if (context.getDirection().getReceptionSide().isServer())
            {
                return;
            }
            List<ContentPackHandshake.PackEntry> entries = ContentPackLoader.loadedPacks().stream()
                    .map(ContentPackRequestPacket::entryOf)
                    .toList();
            NetworkHandler.sendToServer(new ContentPackListPacket(entries));
        });
        context.setPacketHandled(true);
    }

    private static ContentPackHandshake.PackEntry entryOf(ContentPack pack)
    {
        return new ContentPackHandshake.PackEntry(pack.id().toString(), pack.version(), pack.contentHash());
    }
}
