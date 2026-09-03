package com.example.cardtable.table;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TableGroupStateTest
{
    @Test
    void createMintsUniqueTableIds()
    {
        assertNotEquals(TableGroupState.create().getTableId(), TableGroupState.create().getTableId());
    }

    @Test
    void roundTrips()
    {
        TableGroupState state = TableGroupState.create();
        state.bumpVersion();
        state.bumpVersion();

        TableGroupState loaded = TableGroupState.load(state.save());
        assertEquals(state.getTableId(), loaded.getTableId());
        assertEquals(2L, loaded.getVersion());
    }

    @Test
    void loadWithoutTableIdMintsNewOne()
    {
        CompoundTag empty = new CompoundTag();
        TableGroupState loaded = TableGroupState.load(empty);
        assertEquals(0L, loaded.getVersion());
        // A fresh identity is minted rather than leaving the group anchor-less.
        assertNotEquals(TableGroupState.create().getTableId(), loaded.getTableId());
    }

    // Pre-merge migration: the standalone table's identity and version carry over.
    @Test
    void legacyMigrationKeepsTableId()
    {
        UUID tableId = UUID.randomUUID();
        CompoundTag legacy = new CompoundTag();
        legacy.putUUID("TableId", tableId);
        legacy.putLong("Version", 42L);

        TableGroupState migrated = TableGroupState.loadLegacy(legacy);
        assertEquals(tableId, migrated.getTableId());
        assertEquals(42L, migrated.getVersion());
    }

    @Test
    void copyMatchesSource()
    {
        TableGroupState source = TableGroupState.create();
        source.bumpVersion();
        TableGroupState copy = new TableGroupState(source);
        assertEquals(source.getTableId(), copy.getTableId());
        assertEquals(source.getVersion(), copy.getVersion());
    }
}
