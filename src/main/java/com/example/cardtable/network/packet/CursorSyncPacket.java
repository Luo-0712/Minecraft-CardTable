package com.example.cardtable.network.packet;

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
 * <p>Client → server: {@code playerId} is null — the server always uses
 * {@code context.getSender()} and never trusts a self-reported identity or
 * name. Server → client: the packet carries the sender's UUID and the name
 * taken from the server GameProfile, so a peer cannot forge either.</p>
 *
 * <p>Coordinates are playfield-normalized table space (0..1), the same
 * convention as card surface positions: every client applies its own view
 * rotation when rendering, so all cursors agree on one physical table
 * point. Invalid packets (wrong menu, not seated, broken range) are dropped
 * silently — no log spam from a misbehaving client.</p>
 *
 * @param tablePosition the table group this sample belongs to
 * @param playerId      null on the client→server report; the seated player's
 *                      UUID on the server→client broadcast
 * @param playerName    empty on the report; the server profile name on the
 *                      broadcast
 * @param normX         playfield-normalized x in 0..1 (clamped server-side)
 * @param normY         playfield-normalized y in 0..1 (clamped server-side)
 */
public record CursorSyncPacket(BlockPos tablePosition, @Nullable UUID playerId,
                               String playerName, float normX, float normY)
{
    public CursorSyncPacket
    {
        playerName = playerName == null ? "" : playerName;
    }

    public static void encode(CursorSyncPacket packet, FriendlyByteBuf buffer)
    {
        buffer.writeBlockPos(packet.tablePosition());
        buffer.writeBoolean(packet.playerId() != null);
        if (packet.playerId() != null)
        {
            buffer.writeUUID(packet.playerId());
            buffer.writeUtf(packet.playerName());
        }
        buffer.writeFloat(packet.normX());
        buffer.writeFloat(packet.normY());
    }

    public static CursorSyncPacket decode(FriendlyByteBuf buffer)
    {
        BlockPos tablePosition = buffer.readBlockPos();
        UUID playerId = null;
        String playerName = "";
        if (buffer.readBoolean())
        {
            playerId = buffer.readUUID();
            playerName = buffer.readUtf();
        }
        return new CursorSyncPacket(tablePosition, playerId, playerName,
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
     * clients skip their own UUID when rendering).
     */
    private static void handleServer(CursorSyncPacket packet, ServerPlayer sender)
    {
        AbstractContainerMenu menu = sender.containerMenu;
        if (!(menu instanceof CardTableMenu cardTableMenu)
                || !cardTableMenu.getTablePosition().equals(packet.tablePosition())
                || !cardTableMenu.stillValid(sender))
        {
            return;
        }

        if (!(sender.level() instanceof ServerLevel level))
        {
            return;
        }
        if (!TableGroupService.isSeated(level, packet.tablePosition(), sender))
        {
            return;
        }

        CursorSyncPacket broadcast = new CursorSyncPacket(packet.tablePosition(),
                sender.getUUID(),
                sender.getGameProfile().getName(),
                clamp01(packet.normX()),
                clamp01(packet.normY()));
        for (ServerPlayer seated : TableGroupService.seatedPlayers(level, packet.tablePosition()))
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
