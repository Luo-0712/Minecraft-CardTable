package com.example.cardtable.block.entity;

import com.example.cardtable.state.CardTableState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class CardTableBlockEntity extends net.minecraft.world.level.block.entity.BlockEntity
{
    private static final String STATE_TAG = "TableState";

    private CardTableState tableState = CardTableState.create();

    public CardTableBlockEntity(BlockPos position, BlockState blockState)
    {
        super(ModBlockEntities.CARD_TABLE.get(), position, blockState);
    }

    public CardTableState getTableState()
    {
        return this.tableState;
    }

    public CardTableState.JoinResult join(Player player)
    {
        CardTableState.JoinResult result = this.tableState.join(player.getUUID());
        if (result == CardTableState.JoinResult.JOINED)
        {
            this.markStateChanged();
        }
        return result;
    }

    public boolean leave(Player player)
    {
        if (!this.tableState.leave(player.getUUID()))
        {
            return false;
        }

        this.markStateChanged();
        return true;
    }

    public boolean isParticipant(Player player)
    {
        return this.tableState.isParticipant(player.getUUID());
    }

    private void markStateChanged()
    {
        this.setChanged();
        if (this.level != null)
        {
            BlockState blockState = this.getBlockState();
            this.level.sendBlockUpdated(this.worldPosition, blockState, blockState, 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag)
    {
        super.saveAdditional(tag);
        tag.put(STATE_TAG, this.tableState.save());
    }

    @Override
    public void load(CompoundTag tag)
    {
        super.load(tag);
        this.tableState = tag.contains(STATE_TAG, CompoundTag.TAG_COMPOUND)
                ? CardTableState.load(tag.getCompound(STATE_TAG))
                : CardTableState.create();
    }

    @Override
    public CompoundTag getUpdateTag()
    {
        return this.saveWithoutMetadata();
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket()
    {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet)
    {
        this.load(packet.getTag());
    }
}
