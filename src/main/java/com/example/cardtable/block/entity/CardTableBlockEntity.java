package com.example.cardtable.block.entity;

import com.example.cardtable.block.custom.CardTableBlock;
import com.example.cardtable.table.TableGroupState;
import com.example.cardtable.table.TableSectionState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class CardTableBlockEntity extends net.minecraft.world.level.block.entity.BlockEntity
{
    private static final String GROUP_STATE_TAG = "GroupState";
    private static final String SECTION_STATE_TAG = "SectionState";
    /** Pre-merge saves carried the whole table state under this tag. */
    private static final String LEGACY_STATE_TAG = "TableState";

    private TableGroupState groupState = TableGroupState.create();
    private TableSectionState sectionState = new TableSectionState();

    public CardTableBlockEntity(BlockPos position, BlockState blockState)
    {
        super(ModBlockEntities.CARD_TABLE.get(), position, blockState);
    }

    public TableGroupState getGroupState()
    {
        return this.groupState;
    }

    public TableSectionState getSectionState()
    {
        return this.sectionState;
    }

    public void setGroupState(TableGroupState groupState)
    {
        this.groupState = groupState;
    }

    /** Whether the player occupies any seat in the group; see {@code TableGroupService}. */
    public boolean isOccupant(Player player)
    {
        return player.getUUID().equals(this.sectionState.getOccupantId());
    }

    public void markStateChanged()
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
        tag.put(GROUP_STATE_TAG, this.groupState.save());
        tag.put(SECTION_STATE_TAG, this.sectionState.save());
    }

    @Override
    public void load(CompoundTag tag)
    {
        super.load(tag);
        if (tag.contains(LEGACY_STATE_TAG, CompoundTag.TAG_COMPOUND))
        {
            // Pre-merge table: carry the group identity over and seat the
            // earliest recorded participant; later ones are released.
            CompoundTag legacy = tag.getCompound(LEGACY_STATE_TAG);
            this.groupState = TableGroupState.loadLegacy(legacy);
            this.sectionState = TableSectionState.loadLegacy(legacy);
        }
        else
        {
            this.groupState = tag.contains(GROUP_STATE_TAG, CompoundTag.TAG_COMPOUND)
                    ? TableGroupState.load(tag.getCompound(GROUP_STATE_TAG))
                    : TableGroupState.create();
            this.sectionState = tag.contains(SECTION_STATE_TAG, CompoundTag.TAG_COMPOUND)
                    ? TableSectionState.load(tag.getCompound(SECTION_STATE_TAG))
                    : new TableSectionState();
        }
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
