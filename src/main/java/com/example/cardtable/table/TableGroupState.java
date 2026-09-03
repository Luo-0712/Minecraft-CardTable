package com.example.cardtable.table;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * Group-level state shared by every block of one merged card table. The
 * master block's copy is authoritative; every other section keeps a synced
 * copy so clients can read a consistent group view from any block.
 *
 * <p>Shared card containers (draw pile, discard pile) will attach here in the
 * card object phase; membership lives in the per-block
 * {@link TableSectionState}.</p>
 */
public final class TableGroupState
{
    private static final String TABLE_ID_TAG = "TableId";
    private static final String VERSION_TAG = "Version";

    private final UUID tableId;
    private long version;

    // Package-private: the group identity is minted by create(), restored by
    // load(), or carried over from a legacy save by TableSectionState.
    TableGroupState(UUID tableId, long version)
    {
        this.tableId = tableId;
        this.version = Math.max(0L, version);
    }

    /** Defensive copy used to propagate the master's state to other sections. */
    public TableGroupState(TableGroupState source)
    {
        this(source.tableId, source.version);
    }

    public static TableGroupState create()
    {
        return new TableGroupState(UUID.randomUUID(), 0L);
    }

    public static TableGroupState load(CompoundTag tag)
    {
        UUID tableId = tag.hasUUID(TABLE_ID_TAG) ? tag.getUUID(TABLE_ID_TAG) : UUID.randomUUID();
        long version = tag.contains(VERSION_TAG) ? tag.getLong(VERSION_TAG) : 0L;
        return new TableGroupState(tableId, version);
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
}
