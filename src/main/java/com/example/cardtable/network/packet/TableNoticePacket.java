package com.example.cardtable.network.packet;

import com.example.cardtable.client.ClientTableNotices;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server-to-client toast for a shared table event the seated players should
 * notice without leaving the board — currently a successful shuffle. Payload
 * is plain display text pieces so the client never needs layout registry
 * access to render the line.
 *
 * <p>The event is keyed by the group's {@code TableId}, read from the
 * master's group state — the same synced identity every client reads its
 * own card data through, never a block position the receiving side would
 * have to match against its own menu anchor.</p>
 *
 * @param tableId    the table group the event belongs to; clients only
 *                   surface it while their screen still points at this group
 * @param actorName  display name of the player who triggered the event
 * @param zoneLabel  optional pile label from the layout; blank falls back
 *                   to the generic notice wording
 */
public record TableNoticePacket(UUID tableId, String actorName, String zoneLabel)
{
    public TableNoticePacket
    {
        actorName = actorName == null ? "" : actorName;
        zoneLabel = zoneLabel == null ? "" : zoneLabel;
    }

    public static void encode(TableNoticePacket packet, FriendlyByteBuf buffer)
    {
        buffer.writeUUID(packet.tableId());
        buffer.writeUtf(packet.actorName());
        buffer.writeUtf(packet.zoneLabel());
    }

    public static TableNoticePacket decode(FriendlyByteBuf buffer)
    {
        return new TableNoticePacket(buffer.readUUID(), buffer.readUtf(), buffer.readUtf());
    }

    public static void handle(TableNoticePacket packet, Supplier<NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientTableNotices.showShuffle(packet)));
        context.setPacketHandled(true);
    }
}
