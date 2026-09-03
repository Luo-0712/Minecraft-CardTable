package com.example.cardtable.table;

import com.example.cardtable.card.CardInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Group-level state shared by every block of one merged card table. The
 * master block's copy is authoritative; every other section keeps a synced
 * copy so clients can read a consistent group view from any block.
 *
 * <p>Besides identity and version it owns the shared card containers: the
 * draw pile filled by the inserted deck item, the discard pile and the deck
 * item itself (so the table "remembers" which deck is in play). Membership
 * lives in the per-block {@link TableSectionState}.</p>
 */
public final class TableGroupState
{
    private static final String TABLE_ID_TAG = "TableId";
    private static final String VERSION_TAG = "Version";
    private static final String DRAW_PILE_TAG = "DrawPile";
    private static final String DISCARD_PILE_TAG = "DiscardPile";
    private static final String DECK_TAG = "DeckItem";

    private final UUID tableId;
    private long version;

    /** Bottom-to-top order: the last entry is the top of the pile. */
    private final List<CardInstance> drawPile = new ArrayList<>();
    private final List<CardInstance> discardPile = new ArrayList<>();
    // Held as serialized NBT instead of an ItemStack field: an ItemStack
    // field initializer would drag the item registry into every class-load
    // (including plain unit tests), while save/load only need this tag.
    @Nullable
    private CompoundTag deckStackTag;

    // Package-private: the group identity is minted by create(), restored by
    // load(), or carried over from a legacy save by TableSectionState.
    TableGroupState(UUID tableId, long version)
    {
        this.tableId = tableId;
        this.version = Math.max(0L, version);
    }

    /** Deep copy used to propagate the master's state to other sections. */
    public TableGroupState(TableGroupState source)
    {
        this(source.tableId, source.version);
        this.drawPile.addAll(source.drawPile);
        this.discardPile.addAll(source.discardPile);
        this.deckStackTag = source.deckStackTag == null ? null : source.deckStackTag.copy();
    }

    public static TableGroupState create()
    {
        return new TableGroupState(UUID.randomUUID(), 0L);
    }

    public static TableGroupState load(CompoundTag tag)
    {
        UUID tableId = tag.hasUUID(TABLE_ID_TAG) ? tag.getUUID(TABLE_ID_TAG) : UUID.randomUUID();
        long version = tag.contains(VERSION_TAG) ? tag.getLong(VERSION_TAG) : 0L;
        TableGroupState state = new TableGroupState(tableId, version);
        state.drawPile.addAll(CardInstance.loadAll(tag.getList(DRAW_PILE_TAG, Tag.TAG_COMPOUND)));
        state.discardPile.addAll(CardInstance.loadAll(tag.getList(DISCARD_PILE_TAG, Tag.TAG_COMPOUND)));
        state.deckStackTag = tag.contains(DECK_TAG, Tag.TAG_COMPOUND) ? tag.getCompound(DECK_TAG).copy() : null;
        return state;
    }

    /**
     * Restores group identity from a pre-merge save, where the standalone
     * table's state carried {@code TableId} and {@code Version} directly.
     */
    public static TableGroupState loadLegacy(CompoundTag oldTableState)
    {
        UUID tableId = oldTableState.hasUUID(TABLE_ID_TAG) ? oldTableState.getUUID(TABLE_ID_TAG) : UUID.randomUUID();
        long version = oldTableState.contains(VERSION_TAG) ? oldTableState.getLong(VERSION_TAG) : 0L;
        return new TableGroupState(tableId, version);
    }

    public CompoundTag save()
    {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TABLE_ID_TAG, this.tableId);
        tag.putLong(VERSION_TAG, this.version);
        tag.put(DRAW_PILE_TAG, CardInstance.saveAll(this.drawPile));
        tag.put(DISCARD_PILE_TAG, CardInstance.saveAll(this.discardPile));
        if (this.deckStackTag != null)
        {
            tag.put(DECK_TAG, this.deckStackTag.copy());
        }
        return tag;
    }

    public void bumpVersion()
    {
        this.version++;
    }

    public UUID getTableId()
    {
        return this.tableId;
    }

    public long getVersion()
    {
        return this.version;
    }

    // Shared card containers -------------------------------------------------

    /** Bottom-to-top order: the last entry is the top of the pile. */
    public List<CardInstance> getDrawPile()
    {
        return this.drawPile;
    }

    /** Bottom-to-top order: the last entry is the top of the pile. */
    public List<CardInstance> getDiscardPile()
    {
        return this.discardPile;
    }

    /** The deck item currently inserted into the table's deck slot, if any. */
    public ItemStack getDeckStack()
    {
        return this.deckStackTag == null ? ItemStack.EMPTY : ItemStack.of(this.deckStackTag);
    }

    public void setDeckStack(ItemStack deckStack)
    {
        this.deckStackTag = deckStack == null || deckStack.isEmpty() ? null : deckStack.save(new CompoundTag());
    }

    /** Bottom-to-top order: the added card becomes the top of the pile. */
    public void addToDrawPileTop(CardInstance card)
    {
        this.drawPile.add(card);
    }

    @Nullable
    public CardInstance takeFromDrawPileTop()
    {
        return this.drawPile.isEmpty() ? null : this.drawPile.remove(this.drawPile.size() - 1);
    }

    /** Bottom-to-top order: the added card becomes the top of the pile. */
    public void addToDiscardPileTop(CardInstance card)
    {
        this.discardPile.add(card);
    }
}
