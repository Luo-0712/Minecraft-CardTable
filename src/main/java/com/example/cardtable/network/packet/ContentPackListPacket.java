package com.example.cardtable.network.packet;

import com.example.cardtable.content.ContentPackHandshake;
import com.example.cardtable.content.ContentPackLoader;
import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client-to-server reply to {@link ContentPackRequestPacket}: the local
 * content pack list. The server disconnects players whose packs are missing
 * or whose {@code (version, contentHash)} does not match its own.
 */
public record ContentPackListPacket(List<ContentPackHandshake.PackEntry> entries)
{
    private static final Logger LOGGER = LogUtils.getLogger();

    public ContentPackListPacket
    {
        entries = List.copyOf(entries);
    }

    public static void encode(ContentPackListPacket packet, FriendlyByteBuf buffer)
    {
        buffer.writeVarInt(packet.entries.size());
        for (ContentPackHandshake.PackEntry entry : packet.entries)
        {
            buffer.writeUtf(entry.packId(), 256);
            buffer.writeUtf(entry.version(), 64);
            buffer.writeUtf(entry.contentHash(), 64);
        }
    }

    public static ContentPackListPacket decode(FriendlyByteBuf buffer)
    {
        int count = buffer.readVarInt();
        List<ContentPackHandshake.PackEntry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++)
        {
            entries.add(new ContentPackHandshake.PackEntry(
                    buffer.readUtf(256), buffer.readUtf(64), buffer.readUtf(64)));
        }
        return new ContentPackListPacket(entries);
    }

    public static void handle(ContentPackListPacket packet, Supplier<NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null)
            {
                return;
            }
            ContentPackHandshake.CompareResult result =
                    ContentPackHandshake.compare(ContentPackLoader.loadedPacks(), packet.entries());
            if (result.passed())
            {
                LOGGER.debug("Content handshake passed for {}", player.getName().getString());
                return;
            }
            LOGGER.warn("Content handshake failed for {}: {}", player.getName().getString(),
                    result.failure().getString());
            player.connection.disconnect(result.failure());
        });
        context.setPacketHandled(true);
    }
}
