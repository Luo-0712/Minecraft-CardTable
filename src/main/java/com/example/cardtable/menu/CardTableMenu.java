package com.example.cardtable.menu;

import com.example.cardtable.block.entity.CardTableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;

public class CardTableMenu extends AbstractContainerMenu
{
    private static final int MAX_DISTANCE_SQUARED = 64;

    private final BlockPos tablePosition;
    private final Level level;
    private final CardTableBlockEntity tableEntity;
    private final Player viewer;
    private final DataSlot participantCount = DataSlot.standalone();
    private final DataSlot maxParticipants = DataSlot.standalone();
    private final DataSlot version = DataSlot.standalone();
    private final DataSlot viewerIsParticipant = DataSlot.standalone();

    public CardTableMenu(int containerId, Inventory inventory, FriendlyByteBuf data)
    {
        this(containerId, inventory, data.readBlockPos());
    }

    public CardTableMenu(int containerId, Inventory inventory, BlockPos tablePosition)
    {
        super(ModMenus.CARD_TABLE.get(), containerId);
        this.tablePosition = tablePosition;
        this.level = inventory.player.level();
        this.viewer = inventory.player;
        BlockEntity blockEntity = this.level.getBlockEntity(tablePosition);
        if (!(blockEntity instanceof CardTableBlockEntity cardTableBlockEntity))
        {
            throw new IllegalStateException("Card table menu opened without a card table block entity");
        }

        this.tableEntity = cardTableBlockEntity;
        this.addDataSlot(this.participantCount);
        this.addDataSlot(this.maxParticipants);
        this.addDataSlot(this.version);
        this.addDataSlot(this.viewerIsParticipant);
    }

    @Override
    public void broadcastChanges()
    {
        this.participantCount.set(this.tableEntity.getTableState().getParticipantCount());
        this.maxParticipants.set(this.tableEntity.getTableState().getMaxParticipants());
        this.version.set((int) this.tableEntity.getTableState().getVersion());
        this.viewerIsParticipant.set(this.tableEntity.isParticipant(this.viewer) ? 1 : 0);
        super.broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot)
    {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player)
    {
        return this.level.getBlockEntity(this.tablePosition) == this.tableEntity
                && player.distanceToSqr(this.tablePosition.getX() + 0.5D, this.tablePosition.getY() + 0.5D, this.tablePosition.getZ() + 0.5D) <= MAX_DISTANCE_SQUARED;
    }

    public BlockPos getTablePosition()
    {
        return this.tablePosition;
    }

    public int getParticipantCount()
    {
        return this.participantCount.get();
    }

    public int getMaxParticipants()
    {
        return this.maxParticipants.get();
    }

    public long getVersion()
    {
        return Integer.toUnsignedLong(this.version.get());
    }

    public boolean isParticipant(Player player)
    {
        return this.viewerIsParticipant.get() != 0;
    }

    public UUID getTableId()
    {
        return this.tableEntity.getTableState().getTableId();
    }
}
