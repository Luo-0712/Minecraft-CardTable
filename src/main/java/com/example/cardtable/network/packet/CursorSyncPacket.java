package com.example.cardtable.network.packet;

import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.client.ClientCursorStore;
import com.example.cardtable.menu.CardTableMenu;
import com.example.cardtable.network.NetworkHandler;
import com.example.cardtable.table.TableGroupService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bidirectional low-frequency mouse-cursor sync for one table group.
 *
 * <p>The two directions carry different identity payloads, mirroring how
 * the card data is keyed:</p>
 * <ul>
 *   <li><b>Client → server report</b>: {@code reportPosition} is the menu's
 *       table block — the same addressing {@link CardActionPacket} uses,
 *       and the only way the server can tell which group sent the sample.
 *       {@code tableId} and {@code playerId} are null.</li>
 *   <li><b>Server → client broadcast</b>: carries the group's
 *       {@code TableId} instead of any block position. That is the card
 *       identity model: both ends read the group identity from their own
 *       synced group state (server: the master block entity, client: the
 *       same block entity's synced copy) and no sender-declared position
 *       is ever compared against a locally resolved one. {@code
 *       reportPosition} is null.</li>
 * </ul>
 *
 * <p>The server always takes the player identity from
 * {@code context.getSender()} and the group identity from the master's
 * group state, never from anything the client reported, so a peer cannot
 * forge either. Coordinates are playfield-normalized table space (0..1),
 * the same convention as card surface positions: every client applies its
 * own view rotation when rendering, so all cursors agree on one physical
 * table point. Invalid packets (wrong menu, not seated, broken range) are
 * dropped silently — no log spam from a misbehaving client.</p>
 *
 * @param reportPosition client→server: the menu's table block the server
 *                       resolves the group from; null on broadcasts
 * @param tableId        server→client: the group's TableId from the master's
 *                       synced group state; null on reports
 * @param playerId       null on the client→server report; the seated player's
 *                       UUID on the server→client broadcast
 * @param playerName     empty on the report; the server profile name on the
 *                       broadcast
 * @param normX          playfield-normalized x in 0..1 (clamped server-side)
 * @param normY          playfield-normalized y in 0..1 (clamped server-side)
 */
public record CursorSyncPacket(@Nullable BlockPos reportPosition, @Nullable UUID tableId,
                               @Nullable UUID playerId, String playerName,
                               float normX, float normY)
{
    public CursorSyncPacket
    {
        playerName = playerName == null ? "" : playerName;
    }

    /** Client→server report addressed by the menu's table block. */
    public static CursorSyncPacket report(BlockPos reportPosition, float normX, float normY)
    {
        return new CursorSyncPacket(reportPosition, null, null, "", normX, normY);
    }

    public static void encode(CursorSyncPacket packet, FriendlyByteBuf buffer)
    {
        buffer.writeBoolean(packet.playerId() != null);
        if (packet.playerId() == null)
        {
            buffer.writeBlockPos(packet.reportPosition());
        }
        else
        {
            buffer.writeUUID(packet.tableId());
            buffer.writeUUID(packet.playerId());
            buffer.writeUtf(packet.playerName());
        }
        buffer.writeFloat(packet.normX());
        buffer.writeFloat(packet.normY());
    }

    public static CursorSyncPacket decode(FriendlyByteBuf buffer)
    {
        if (!buffer.readBoolean())
        {
            BlockPos reportPosition = buffer.readBlockPos();
            return new CursorSyncPacket(reportPosition, null, null, "",
                    buffer.readFloat(), buffer.readFloat());
        }
        UUID tableId = buffer.readUUID();
        UUID playerId = buffer.readUUID();
        return new CursorSyncPacket(null, tableId, playerId, buffer.readUtf(),
                buffer.readFloat(), buffer.readFloat());
    }

    public static void handle(CursorSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null)
        {
            context.enqueueWork(() -> handleServer(packet, sender));
        }
        else
        {
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ClientCursorStore.update(packet)));
        }
        context.setPacketHandled(true);
    }

    /**
     * Validates the report against the sender's open menu and seat, clamps
     * the sample, fills in the authoritative identity, then relays it to
     * every online seated player of the same group (including the sender —
     * clients skip their own UUID when rendering). The broadcast is keyed
     * by the group's TableId, read from the master's group state — the
     * same synced copy every client reads its own card data through.
     */
    private static void handleServer(CursorSyncPacket packet, ServerPlayer sender)
    {
        AbstractContainerMenu menu = sender.containerMenu;
        if (!(menu instanceof CardTableMenu cardTableMenu)
                || !cardTableMenu.getTablePosition().equals(packet.reportPosition())
                || !cardTableMenu.stillValid(sender))
        {
            return;
        }

        if (!(sender.level() instanceof ServerLevel level))
        {
            return;
        }
        TableGroupService.GroupView group = TableGroupService.resolve(level, packet.reportPosition());
        if (group == null || !TableGroupService.isSeated(level, group, sender))
        {
            return;
        }
        if (!(level.getBlockEntity(group.masterPos()) instanceof CardTableBlockEntity master))
        {
            return;
        }

        CursorSyncPacket broadcast = new CursorSyncPacket(null,
                master.getGroupState().getTableId(),
                sender.getUUID(),
                sender.getGameProfile().getName(),
                clamp01(packet.normX()),
                clamp01(packet.normY()));
        for (ServerPlayer seated : TableGroupService.seatedPlayers(level, group))
        {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> seated), broadcast);
        }
    }

    private static float clamp01(float value)
    {
        if (Float.isNaN(value))
        {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
