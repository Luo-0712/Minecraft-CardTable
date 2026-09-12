package com.example.cardtable.network.packet;

import com.example.cardtable.client.ClientTableNotices;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server-to-client toast for a shared table event the seated players should
 * notice without leaving the board — currently a successful shuffle. Payload
 * is plain display text pieces so the client never needs layout registry
 * access to render the line.
 *
 * @param tablePosition the table group the event belongs to; clients only
 *                      surface it while their table screen still points here
 * @param actorName     display name of the player who triggered the event
 * @param zoneLabel     optional pile label from the layout; blank falls back
 *                      to the generic notice wording
 */
public record TableNoticePacket(BlockPos tablePosition, String actorName, String zoneLabel)
{
    public TableNoticePacket
    {
        actorName = actorName == null ? "" : actorName;
        zoneLabel = zoneLabel == null ? "" : zoneLabel;
    }

    public static void encode(TableNoticePacket packet, FriendlyByteBuf buffer)
    {
        buffer.writeBlockPos(packet.tablePosition());
        buffer.writeUtf(packet.actorName());
        buffer.writeUtf(packet.zoneLabel());
    }

    public static TableNoticePacket decode(FriendlyByteBuf buffer)
    {
        return new TableNoticePacket(buffer.readBlockPos(), buffer.readUtf(), buffer.readUtf());
    }

    public static void handle(TableNoticePacket packet, Supplier<NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientTableNotices.showShuffle(packet)));
        context.setPacketHandled(true);
    }
}
