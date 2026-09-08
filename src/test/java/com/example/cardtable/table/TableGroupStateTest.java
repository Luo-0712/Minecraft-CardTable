package com.example.cardtable.table;

import com.example.cardtable.card.CardInstance;
import com.example.cardtable.card.ZoneState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableGroupStateTest
{
    private static final ResourceLocation SET_ID = new ResourceLocation("cardtable", "standard");
    private static final ResourceLocation LAYOUT_ID = new ResourceLocation("cardtable", "my_tcg");
    private static final ResourceLocation ZONE_ID = new ResourceLocation("cardtable", "my_tcg/battlefield");

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

    // Layout binding + generic zones: NBT round trip.
    @Test
    void layoutBindingAndSharedZonesRoundTrip()
    {
        TableGroupState state = TableGroupState.create();
        state.setLayoutBinding(SET_ID, LAYOUT_ID);
        ZoneState battlefield = ZoneState.placed();
        CardInstance ace = new CardInstance(new ResourceLocation("cardtable", "ace"));
        battlefield.addPlaced(ace, 0.3F, 0.4F);
        state.getSharedZones().put(ZONE_ID, battlefield);
        ZoneState deck = ZoneState.stack();
        deck.addToStackTop(new CardInstance(new ResourceLocation("cardtable", "king")));
        state.getSharedZones().put(new ResourceLocation("cardtable", "my_tcg/prize"), deck);
        state.bumpVersion();

        TableGroupState loaded = TableGroupState.load(state.save());
        assertEquals(SET_ID, loaded.getActiveSetId());
        assertEquals(LAYOUT_ID, loaded.getActiveLayoutId());
        assertEquals(2, loaded.getSharedZones().size());
        assertEquals(0.3F, loaded.getSharedZones().get(ZONE_ID).placedCards().get(0).x());
        assertEquals(0.4F, loaded.getSharedZones().get(ZONE_ID).placedCards().get(0).y());
        assertEquals(1, loaded.getSharedZones().get(new ResourceLocation("cardtable", "my_tcg/prize")).stackCards().size());
    }

    // Classic tables (no layout markers) stay null after a round trip.
    @Test
    void classicTableKeepsNullLayoutBinding()
    {
        TableGroupState state = TableGroupState.create();
        TableGroupState loaded = TableGroupState.load(state.save());
        assertNull(loaded.getActiveSetId());
        assertNull(loaded.getActiveLayoutId());
        assertTrue(loaded.getSharedZones().isEmpty());
    }

    // Old-save migration: deck without layout markers derives the active set.
    @Test
    void oldSaveWithDeckDerivesActiveSet()
    {
        TableGroupState state = TableGroupState.create();
        // Simulate the deck item's saved tag (setDeckStack would need the item
        // registry, so write the serialized shape directly).
        CompoundTag deckTag = new CompoundTag();
        deckTag.putString("id", "cardtable:deck");
        deckTag.putInt("Count", 1);
        CompoundTag itemTag = new CompoundTag();
        itemTag.putString("DeckId", SET_ID.toString());
        deckTag.put("tag", itemTag);

        TableGroupState loaded = TableGroupState.load(state.save());
        assertNull(loaded.getActiveSetId()); // sanity: the save above has no deck

        // Now inject the deck tag into the saved compound and reload.
        CompoundTag saved = state.save();
        saved.put("DeckItem", deckTag);
        TableGroupState migrated = TableGroupState.load(saved);
        assertEquals(SET_ID, migrated.getActiveSetId());
        // Layout stays unresolved here; the registry lookup happens at the
        // block entity layer, defaulting to the classic table.
        assertNull(migrated.getActiveLayoutId());
    }

    // Deep copy: generic zones must be duplicated, not aliased.
    @Test
    void copyDeepCopiesSharedZones()
    {
        TableGroupState source = TableGroupState.create();
        source.setLayoutBinding(SET_ID, LAYOUT_ID);
        ZoneState battlefield = ZoneState.placed();
        CardInstance ace = new CardInstance(new ResourceLocation("cardtable", "ace"));
        battlefield.addPlaced(ace, 0.3F, 0.4F);
        source.getSharedZones().put(ZONE_ID, battlefield);

        TableGroupState copy = new TableGroupState(source);
        source.getSharedZones().get(ZONE_ID).remove(ace.instanceId());
        assertSame(ZoneState.Storage.PLACED, copy.getSharedZones().get(ZONE_ID).storage());
        assertEquals(1, copy.getSharedZones().get(ZONE_ID).size());
        assertEquals(0, source.getSharedZones().get(ZONE_ID).size());

        copy.resetLayoutState();
        assertTrue(copy.getSharedZones().isEmpty());
        assertNull(copy.getActiveSetId());
        // Resetting the copy must not touch the source binding.
        assertEquals(LAYOUT_ID, source.getActiveLayoutId());
    }
}
