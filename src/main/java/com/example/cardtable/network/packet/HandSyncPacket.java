package com.example.cardtable.network.packet;

import com.example.cardtable.card.CardInstance;
import com.example.cardtable.client.ClientHandStore;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Directed server-to-client sync of one section's hidden hand, sent only to
 * that section's occupant. This is the sole channel carrying hand contents —
 * the block entity update tag deliberately strips them (only a count is
 * broadcast there).
 */
public record HandSyncPacket(BlockPos sectionPosition, List<CardInstance> cards)
{
    public HandSyncPacket
    {
        cards = List.copyOf(cards);
    }

    public static void encode(HandSyncPacket packet, FriendlyByteBuf buffer)
    {
        buffer.writeBlockPos(packet.sectionPosition());
        CompoundTag carrier = new CompoundTag();
        carrier.put("Hand", CardInstance.saveAll(packet.cards()));
        buffer.writeNbt(carrier);
    }

    public static HandSyncPacket decode(FriendlyByteBuf buffer)
    {
        BlockPos sectionPosition = buffer.readBlockPos();
        CompoundTag carrier = buffer.readNbt();
        ListTag hand = carrier == null ? new ListTag() : carrier.getList("Hand", ListTag.TAG_COMPOUND);
        return new HandSyncPacket(sectionPosition, CardInstance.loadAll(hand));
    }

    public static void handle(HandSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientHandStore.update(packet.sectionPosition(), packet.cards())));
        context.setPacketHandled(true);
    }
}
