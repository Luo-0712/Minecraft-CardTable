package com.example.cardtable.table;

import com.example.cardtable.card.CardInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableSectionStateTest
{
    @Test
    void emptyStateRoundTrips()
    {
        TableSectionState state = new TableSectionState();
        TableSectionState loaded = TableSectionState.load(state.save());
        assertNull(loaded.getOccupantId());
        assertEquals(0L, loaded.getVersion());
    }

    @Test
    void occupantRoundTrips()
    {
        UUID occupantId = UUID.randomUUID();
        TableSectionState state = new TableSectionState();
        state.setOccupant(occupantId);
        TableSectionState loaded = TableSectionState.load(state.save());
        assertEquals(occupantId, loaded.getOccupantId());
        assertEquals(1L, loaded.getVersion());
    }

    @Test
    void clearingOccupantBumpsVersion()
    {
        TableSectionState state = new TableSectionState();
        state.setOccupant(UUID.randomUUID());
        long versionAfterJoin = state.getVersion();
        state.setOccupant(null);
        assertEquals(versionAfterJoin + 1L, state.getVersion());
        assertNull(state.getOccupantId());
    }

    // Pre-merge migration: a standalone table's six-participant state seats
    // only its earliest participant.
    @Test
    void legacyStateSeatsFirstParticipantOnly()
    {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        CompoundTag legacy = new CompoundTag();
        legacy.putUUID("TableId", UUID.randomUUID());
        ListTag participants = new ListTag();
        participants.add(participantTag(first));
        participants.add(participantTag(second));
        legacy.put("Participants", participants);
        legacy.putLong("Version", 7L);

        TableSectionState migrated = TableSectionState.loadLegacy(legacy);
        assertEquals(first, migrated.getOccupantId());
    }

    @Test
    void legacyStateWithoutParticipantsSeatsNobody()
    {
        CompoundTag legacy = new CompoundTag();
        legacy.putUUID("TableId", UUID.randomUUID());
        assertNull(TableSectionState.loadLegacy(legacy).getOccupantId());
    }

    @Test
    void reorderHandMovesWithinTheOccupantsHand()
    {
        TableSectionState state = new TableSectionState();
        CardInstance a = new CardInstance(new ResourceLocation("cardtable", "standard/a"));
        CardInstance b = new CardInstance(new ResourceLocation("cardtable", "standard/b"));
        CardInstance c = new CardInstance(new ResourceLocation("cardtable", "standard/c"));
        state.addHandCard(a);
        state.addHandCard(b);
        state.addHandCard(c);

        assertTrue(state.reorderHand(c.instanceId(), 0));
        assertEquals(a.instanceId(), state.getHand().get(1).instanceId());
        assertEquals(b.instanceId(), state.getHand().get(2).instanceId());
        assertEquals(c.instanceId(), state.getHand().get(0).instanceId());

        assertFalse(state.reorderHand(c.instanceId(), 0), "same slot must not claim a change");
        assertEquals(3, state.getHand().size());
    }

    @Test
    void reorderHandRejectsForeignCard()
    {
        TableSectionState state = new TableSectionState();
        CardInstance a = new CardInstance(new ResourceLocation("cardtable", "standard/a"));
        state.addHandCard(a);
        assertFalse(state.reorderHand(UUID.randomUUID(), 0));
        assertEquals(a.instanceId(), state.getHand().get(0).instanceId());
    }

    private static CompoundTag participantTag(UUID playerId)
    {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PlayerId", playerId);
        return tag;
    }
}
