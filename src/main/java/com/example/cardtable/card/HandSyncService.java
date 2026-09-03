package com.example.cardtable.card;

import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.network.NetworkHandler;
import com.example.cardtable.network.packet.HandSyncPacket;
import com.example.cardtable.table.TableGroupService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Pushes section hands to their occupants. The packet is addressed to the
 * player who sits at the section and nobody else, keeping hidden information
 * out of the broadcast path.
 */
public final class HandSyncService
{
    private HandSyncService()
    {
    }

    /** Pushes the hand of every occupied section of the group to its owner. */
    public static void pushAll(Level level, TableGroupService.GroupView group)
    {
        if (level.isClientSide)
        {
            return;
        }
        MinecraftServer server = level.getServer();
        for (var pos : group.positions())
        {
            if (!(level.getBlockEntity(pos) instanceof CardTableBlockEntity section))
            {
                continue;
            }
            UUID occupantId = section.getSectionState().getOccupantId();
            if (occupantId == null)
            {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(occupantId);
            if (player == null)
            {
                continue;
            }
            NetworkHandler.CHANNEL.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new HandSyncPacket(pos, section.getSectionState().takeHandSnapshot()));
        }
    }

    /** Convenience overload resolving the group first. */
    public static void pushAll(Level level, net.minecraft.core.BlockPos tablePosition)
    {
        if (level.isClientSide)
        {
            return;
        }
        TableGroupService.GroupView group = TableGroupService.resolve(level, tablePosition);
        if (group != null)
        {
            pushAll(level, group);
        }
    }
}
