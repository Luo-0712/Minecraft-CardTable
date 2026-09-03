package com.example.cardtable.menu;

import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.item.DeckItem;
import com.example.cardtable.table.TableGroupService;
import com.example.cardtable.table.TableGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Group-aware menu for one merged card table. {@link #broadcastChanges()}
 * re-resolves the group every tick (a BFS over at most
 * {@link TableGraph#MAX_TABLES_PER_GROUP} blocks) and refreshes the synced
 * data slots from the master block; when the table is dismantled the menu
 * invalidates itself and closes.
 *
 * <p>Besides the state slots it owns the deck slot (slot 0, backed by
 * {@link DeckSlotContainer}) and the player's inventory slots, whose screen
 * coordinates are laid out by the fullscreen client screen.</p>
 */
public class CardTableMenu extends AbstractContainerMenu
{
    private static final int MAX_DISTANCE_SQUARED = 64;

    /** Slot 0 is the deck slot; the player inventory follows (27 main + 9 hotbar). */
    public static final int DECK_SLOT_INDEX = 0;
    public static final int INVENTORY_START = 1;
    public static final int INVENTORY_END = 37;

    private final BlockPos tablePosition;
    private final Level level;
    private final Player viewer;
    private final DataSlot seatedCount = DataSlot.standalone();
    private final DataSlot seatCount = DataSlot.standalone();
    private final DataSlot version = DataSlot.standalone();
    private final DataSlot viewerIsSeated = DataSlot.standalone();
    private final DeckSlotContainer deckContainer;
    private final Slot deckSlot;
    private final List<Slot> inventorySlots = new ArrayList<>();

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

        this.deckContainer = new DeckSlotContainer(this.level, tablePosition,
                () -> this.viewer instanceof ServerPlayer serverPlayer ? serverPlayer : null,
                this::stillValid);
        // Slot.x/y are mutable via the project's access transformer, so the
        // client screen lays these out at runtime.
        this.deckSlot = new Slot(this.deckContainer, DECK_SLOT_INDEX, 8, 8)
        {
            @Override
            public boolean mayPlace(ItemStack stack)
            {
                return CardTableMenu.this.isParticipant(CardTableMenu.this.viewer)
                        && stack.getItem() instanceof DeckItem
                        && DeckItem.deckId(stack).isPresent()
                        && this.getItem().isEmpty();
            }

            @Override
            public boolean mayPickup(Player player)
            {
                return CardTableMenu.this.isParticipant(player);
            }
        };
        this.addSlot(this.deckSlot);
        for (int row = 0; row < 3; row++)
        {
            for (int column = 0; column < 9; column++)
            {
                Slot slot = new Slot(inventory, 9 + row * 9 + column, 0, 0);
                this.inventorySlots.add(slot);
                this.addSlot(slot);
            }
        }
        for (int column = 0; column < 9; column++)
        {
            Slot slot = new Slot(inventory, column, 0, 0);
            this.inventorySlots.add(slot);
            this.addSlot(slot);
        }
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

    // Server-side hook for every container close path (E/ESC, switching to
    // another container, logout cleanup): releases the viewer's seat so the
    // table view is a session ("open to sit, close to leave"). Clicking one's
    // own seat icon already released the seat beforehand; leave() is idempotent
    // and turns this into a no-op then.
    @Override
    public void removed(Player player)
    {
        if (this.level instanceof ServerLevel serverLevel)
        {
            TableGroupService.leave(serverLevel, this.tablePosition, player);
        }
        super.removed(player);
    }

    // Deck-to-inventory moves go through a mouse pickup instead, which is the
    // only path that triggers the deck container's reclaim logic; shift moves
    // are therefore only supported into the deck slot.
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex)
    {
        Slot slot = this.slots.get(slotIndex);
        if (slotIndex == DECK_SLOT_INDEX || !slot.hasItem())
        {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        if (!(stack.getItem() instanceof DeckItem) || DeckItem.deckId(stack).isEmpty())
        {
            return ItemStack.EMPTY;
        }
        if (!this.moveItemStackTo(stack, DECK_SLOT_INDEX, INVENTORY_START, false))
        {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty())
        {
            slot.set(ItemStack.EMPTY);
        }
        else
        {
            slot.setChanged();
        }
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

    /** The deck slot, for client-side layout and rendering. */
    public Slot getDeckSlot()
    {
        return this.deckSlot;
    }

    /** The player inventory slots, for client-side layout. */
    public List<Slot> getInventorySlots()
    {
        return this.inventorySlots;
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
