package com.example.cardtable.menu;

import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.table.TableGroupService;
import com.example.cardtable.table.TableGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Group-aware menu for one merged card table. {@link #broadcastChanges()}
 * re-resolves the group every tick (a BFS over at most
 * {@link TableGraph#MAX_TABLES_PER_GROUP} blocks) and refreshes the synced
 * data slots from the master block; when the table is dismantled the menu
 * invalidates itself and closes.
 */
public class CardTableMenu extends AbstractContainerMenu
{
    private static final int MAX_DISTANCE_SQUARED = 64;

    private final BlockPos tablePosition;
    private final Level level;
    private final Player viewer;
    private final DataSlot seatedCount = DataSlot.standalone();
    private final DataSlot seatCount = DataSlot.standalone();
    private final DataSlot version = DataSlot.standalone();
    private final DataSlot viewerIsSeated = DataSlot.standalone();

    /** Cached master position; refreshed by broadcastChanges/stillValid. */
    @Nullable
    private TableGroupService.GroupView cachedGroup;

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
        if (!(this.level.getBlockEntity(tablePosition) instanceof CardTableBlockEntity))
        {
            throw new IllegalStateException("Card table menu opened without a card table block entity");
        }

        this.addDataSlot(this.seatedCount);
        this.addDataSlot(this.seatCount);
        this.addDataSlot(this.version);
        this.addDataSlot(this.viewerIsSeated);
        this.cachedGroup = TableGroupService.resolve(this.level, tablePosition);
    }

    @Override
    public void broadcastChanges()
    {
        this.cachedGroup = TableGroupService.resolve(this.level, this.tablePosition);
        if (this.cachedGroup != null)
        {
            BlockEntity master = this.level.getBlockEntity(this.cachedGroup.masterPos());
            if (master instanceof CardTableBlockEntity tableEntity)
            {
                this.version.set((int) tableEntity.getGroupState().getVersion());
            }

            int seated = 0;
            for (BlockPos pos : this.cachedGroup.positions())
            {
                BlockEntity section = this.level.getBlockEntity(pos);
                if (section instanceof CardTableBlockEntity tableEntity
                        && tableEntity.getSectionState().getOccupantId() != null)
                {
                    seated++;
                }
            }
            this.seatedCount.set(seated);
            this.seatCount.set(this.cachedGroup.positions().size());
            this.viewerIsSeated.set(TableGroupService.isSeated(this.level, this.tablePosition, this.viewer) ? 1 : 0);
        }
        else
        {
            this.seatedCount.set(0);
            this.seatCount.set(0);
            this.viewerIsSeated.set(0);
        }
        super.broadcastChanges();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot)
    {
        return ItemStack.EMPTY;
    }

    // The menu stays open while the clicked table still has a block entity and
    // the player stays within range of any table block of the group; a
    // dismantled group closes the menu.
    @Override
    public boolean stillValid(Player player)
    {
        if (this.level.getBlockEntity(this.tablePosition) == null)
        {
            return false;
        }
        this.cachedGroup = TableGroupService.resolve(this.level, this.tablePosition);
        if (this.cachedGroup == null)
        {
            return false;
        }
        for (BlockPos pos : this.cachedGroup.positions())
        {
            double dx = player.getX() - (pos.getX() + 0.5D);
            double dy = player.getY() - (pos.getY() + 0.5D);
            double dz = player.getZ() - (pos.getZ() + 0.5D);
            if (dx * dx + dy * dy + dz * dz <= MAX_DISTANCE_SQUARED)
            {
                return true;
            }
        }
        return false;
    }

    public BlockPos getTablePosition()
    {
        return this.tablePosition;
    }

    public int getSeatedCount()
    {
        return this.seatedCount.get();
    }

    public int getSeatCount()
    {
        return this.seatCount.get();
    }

    public long getVersion()
    {
        return Integer.toUnsignedLong(this.version.get());
    }

    public boolean isParticipant(Player player)
    {
        return this.viewerIsSeated.get() != 0;
    }

    /** Group identity read from any live section of the cached group. */
    @Nullable
    public UUID getTableId()
    {
        if (this.cachedGroup == null)
        {
            return null;
        }
        for (BlockPos pos : this.cachedGroup.positions())
        {
            BlockEntity section = this.level.getBlockEntity(pos);
            if (section instanceof CardTableBlockEntity tableEntity)
            {
                return tableEntity.getGroupState().getTableId();
            }
        }
        return null;
    }
}
