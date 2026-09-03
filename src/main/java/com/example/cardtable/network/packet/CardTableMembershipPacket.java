package com.example.cardtable.network.packet;

import com.example.cardtable.menu.CardTableMenu;
import com.example.cardtable.table.TableGroupService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record CardTableMembershipPacket(boolean joining, BlockPos tablePosition)
{
    public static void encode(CardTableMembershipPacket packet, FriendlyByteBuf buffer)
    {
        buffer.writeBoolean(packet.joining);
        buffer.writeBlockPos(packet.tablePosition);
    }

    public static CardTableMembershipPacket decode(FriendlyByteBuf buffer)
    {
        return new CardTableMembershipPacket(buffer.readBoolean(), buffer.readBlockPos());
    }

    public static void handle(CardTableMembershipPacket packet, Supplier<NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null)
            {
                return;
            }

            AbstractContainerMenu menu = player.containerMenu;
            if (!(menu instanceof CardTableMenu cardTableMenu)
                    || !cardTableMenu.getTablePosition().equals(packet.tablePosition)
                    || !cardTableMenu.stillValid(player))
            {
                return;
            }

            if (packet.joining)
            {
                TableGroupService.join(player.level(), packet.tablePosition, player);
            }
            else
            {
                TableGroupService.leave(player.level(), packet.tablePosition, player);
            }
        });
        context.setPacketHandled(true);
    }
}
