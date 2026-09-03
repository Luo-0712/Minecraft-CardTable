package com.example.cardtable.state;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CardTableStateTest
{
    @Test
    void allowsEmptyTableAndCapsParticipantsAtSix()
    {
        CardTableState state = CardTableState.create();
        assertEquals(0, state.getParticipantCount());

        List<UUID> players = new ArrayList<>();
        for (int index = 0; index < CardTableState.DEFAULT_MAX_PARTICIPANTS; index++)
        {
            UUID player = UUID.randomUUID();
            players.add(player);
            assertEquals(CardTableState.JoinResult.JOINED, state.join(player));
        }

        assertEquals(CardTableState.JoinResult.TABLE_FULL, state.join(UUID.randomUUID()));
        assertEquals(CardTableState.JoinResult.ALREADY_JOINED, state.join(players.get(0)));
        assertEquals(6, state.getParticipantCount());
        assertTrue(state.leave(players.get(0)));
        assertEquals(5, state.getParticipantCount());
        assertFalse(state.leave(players.get(0)));
        assertEquals(CardTableState.JoinResult.JOINED, state.join(UUID.randomUUID()));
    }

    @Test
    void preservesStateThroughNbt()
    {
        UUID player = UUID.randomUUID();
        CardTableState state = CardTableState.create();
        state.join(player);

        CompoundTag tag = state.save();
        CardTableState restored = CardTableState.load(tag);

        assertEquals(state.getTableId(), restored.getTableId());
        assertEquals(state.getParticipantIds(), restored.getParticipantIds());
        assertEquals(state.getMaxParticipants(), restored.getMaxParticipants());
        assertEquals(state.getVersion(), restored.getVersion());
    }

    @Test
    void clampsPersistedParticipantLimit()
    {
        CompoundTag tag = new CompoundTag();
        tag.putInt("MaxParticipants", 99);
        CardTableState restored = CardTableState.load(tag);
        assertEquals(CardTableState.DEFAULT_MAX_PARTICIPANTS, restored.getMaxParticipants());
    }

    @Test
    void missingIdentityCreatesFreshTable()
    {
        CardTableState restored = CardTableState.load(new CompoundTag());
        assertNotNull(restored.getTableId());
        assertEquals(0, restored.getParticipantCount());
        assertEquals(CardTableState.DEFAULT_MAX_PARTICIPANTS, restored.getMaxParticipants());
    }
}
