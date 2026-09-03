package com.example.cardtable.network.packet;

import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.menu.CardTableMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
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

            BlockEntity blockEntity = player.level().getBlockEntity(packet.tablePosition);
            if (!(blockEntity instanceof CardTableBlockEntity cardTableBlockEntity))
            {
                return;
            }

            if (packet.joining)
            {
                cardTableBlockEntity.join(player);
            }
            else
            {
                cardTableBlockEntity.leave(player);
            }
        });
        context.setPacketHandled(true);
    }
}
