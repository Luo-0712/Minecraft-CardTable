package com.example.cardtable.table;

import com.example.cardtable.card.ZoneState;
import com.example.cardtable.item.DeckItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Group-level state shared by every block of one merged card table. The
 * master block's copy is authoritative; every other section keeps a synced
 * copy so clients can read a consistent group view from any block.
 *
 * <p>Besides identity and version it owns the shared card containers: the
 * deck item itself (so the table "remembers" which deck is in play) and the
 * generic SHARED zone instances declared by the active layout — piles are
 * just the layout's STACK zones per the kind == STACK convention. Membership
 * lives in the per-block {@link TableSectionState}.</p>
 */
public final class TableGroupState
{
    private static final String TABLE_ID_TAG = "TableId";
    private static final String VERSION_TAG = "Version";
    private static final String DECK_TAG = "DeckItem";
    private static final String ACTIVE_SET_TAG = "ActiveSet";
    private static final String ACTIVE_LAYOUT_TAG = "ActiveLayout";
    private static final String ZONES_TAG = "Zones";

    private final UUID tableId;
    private long version;

    // Held as serialized NBT instead of an ItemStack field: an ItemStack
    // field initializer would drag the item registry into every class-load
    // (including plain unit tests), while save/load only need this tag.
    @Nullable
    private CompoundTag deckStackTag;

    // Layout-driven state: which set/layout this table runs and the generic
    // SHARED zone instances it declared. Null = no deck bound to this table.
    @Nullable
    private ResourceLocation activeSetId;
    @Nullable
    private ResourceLocation activeLayoutId;
    private final Map<ResourceLocation, ZoneState> sharedZones = new LinkedHashMap<>();

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
        this.deckStackTag = source.deckStackTag == null ? null : source.deckStackTag.copy();
        this.activeSetId = source.activeSetId;
        this.activeLayoutId = source.activeLayoutId;
        source.sharedZones.forEach((id, zone) -> this.sharedZones.put(id, new ZoneState(zone)));
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
        // Legacy saves carrying the pre-refactor DrawPile/DiscardPile tags are
        // read no further: those built-in piles no longer exist, so their
        // contents are dropped and the rest of the table migrates intact.
        state.deckStackTag = tag.contains(DECK_TAG, Tag.TAG_COMPOUND) ? tag.getCompound(DECK_TAG).copy() : null;
        if (tag.contains(ACTIVE_SET_TAG, Tag.TAG_STRING))
        {
            state.activeSetId = new ResourceLocation(tag.getString(ACTIVE_SET_TAG));
        }
        if (tag.contains(ACTIVE_LAYOUT_TAG, Tag.TAG_STRING))
        {
            state.activeLayoutId = new ResourceLocation(tag.getString(ACTIVE_LAYOUT_TAG));
        }
        if (tag.contains(ZONES_TAG, Tag.TAG_COMPOUND))
        {
            CompoundTag zones = tag.getCompound(ZONES_TAG);
            for (String key : zones.getAllKeys())
            {
                ZoneState zone = ZoneState.load(zones.getCompound(key));
                if (zone != null)
                {
                    state.sharedZones.put(new ResourceLocation(key), zone);
                }
            }
        }
        // Old-save migration: a table holding a deck but no layout markers was
        // saved before layouts existed. Derive the active set from the deck
        // item; the layout id is resolved by the caller (registry lookup) in
        // CardTableBlockEntity, so here it stays null (classic default).
        if (state.activeSetId == null && state.deckStackTag != null)
        {
            state.activeSetId = deriveSetId(state.deckStackTag);
        }
        return state;
    }

    @Nullable
    private static ResourceLocation deriveSetId(CompoundTag deckStackTag)
    {
        // Read the serialized stack's tag compound directly: going through
        // ItemStack.of would drag the item registry into the migration path
        // (and into plain unit tests), while the nested entries are all we
        // need. Shape: {id, Count, tag:{DeckId}}.
        if (!deckStackTag.contains("tag", Tag.TAG_COMPOUND))
        {
            return null;
        }
        CompoundTag stackTag = deckStackTag.getCompound("tag");
        if (stackTag.contains(DeckItem.DECK_ID_TAG, Tag.TAG_STRING))
        {
            try
            {
                return new ResourceLocation(stackTag.getString(DeckItem.DECK_ID_TAG));
            }
            catch (RuntimeException exception)
            {
                return null; // corrupt id: stay classic
            }
        }
        return null;
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
        if (this.deckStackTag != null)
        {
            tag.put(DECK_TAG, this.deckStackTag.copy());
        }
        if (this.activeSetId != null)
        {
            tag.putString(ACTIVE_SET_TAG, this.activeSetId.toString());
        }
        if (this.activeLayoutId != null)
        {
            tag.putString(ACTIVE_LAYOUT_TAG, this.activeLayoutId.toString());
        }
        if (!this.sharedZones.isEmpty())
        {
            CompoundTag zones = new CompoundTag();
            this.sharedZones.forEach((id, zone) -> zones.put(id.toString(), zone.save()));
            tag.put(ZONES_TAG, zones);
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

    /** The deck item currently inserted into the table's deck slot, if any. */
    public ItemStack getDeckStack()
    {
        return this.deckStackTag == null ? ItemStack.EMPTY : ItemStack.of(this.deckStackTag);
    }

    public void setDeckStack(ItemStack deckStack)
    {
        this.deckStackTag = deckStack == null || deckStack.isEmpty() ? null : deckStack.save(new CompoundTag());
    }

    // Layout-driven state ------------------------------------------------------

    /** The set bound by the inserted deck; {@code null} = classic table. */
    @Nullable
    public ResourceLocation getActiveSetId()
    {
        return this.activeSetId;
    }

    /** The resolved layout this table runs; {@code null} = classic default. */
    @Nullable
    public ResourceLocation getActiveLayoutId()
    {
        return this.activeLayoutId;
    }

    public void setLayoutBinding(@Nullable ResourceLocation activeSetId, @Nullable ResourceLocation activeLayoutId)
    {
        this.activeSetId = activeSetId;
        this.activeLayoutId = activeLayoutId;
    }

    /** Generic SHARED zone instances declared by the active layout. */
    public Map<ResourceLocation, ZoneState> getSharedZones()
    {
        return this.sharedZones;
    }

    /** Clears the generic shared zones and the layout binding (deck removal). */
    public void resetLayoutState()
    {
        this.sharedZones.clear();
        this.activeSetId = null;
        this.activeLayoutId = null;
    }
}
