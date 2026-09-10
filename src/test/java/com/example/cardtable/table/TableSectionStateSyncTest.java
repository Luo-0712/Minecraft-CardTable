package com.example.cardtable.table;

import com.example.cardtable.card.CardInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the core anti-leak contract of the world-sync path: the section-state
 * tag handed to clients ({@code TableSectionState#stripHandForSync} over the
 * saved tag) must never carry a hand card's definition id, only a count.
 * Surface cards live in the group state now, so they never appear in the
 * section tag at all — and never need stripping.
 */
class TableSectionStateSyncTest
{
    private TableSectionState stateWithHand()
    {
        TableSectionState state = new TableSectionState();
        state.setOccupant(UUID.randomUUID());
        state.addHandCard(new CardInstance(new ResourceLocation("cardtable", "standard/king_of_hearts")));
        state.addHandCard(new CardInstance(new ResourceLocation("cardtable", "standard/queen_of_clubs")));
        return state;
    }

    @Test
    void savedTagKeepsTheHandButSyncTagDoesNot()
    {
        TableSectionState state = stateWithHand();
        CompoundTag saved = state.save();
        assertTrue(saved.getList("Hand", Tag.TAG_COMPOUND).size() > 0,
                "the world save must keep the hand");

        CompoundTag syncTag = TableSectionState.stripHandForSync(saved);
        assertFalse(syncTag.contains("Hand"), "hand contents must never reach the update tag");
    }

    @Test
    void syncTagStripsHandContentsAndKeepsCount()
    {
        TableSectionState state = stateWithHand();
        CompoundTag syncTag = TableSectionState.stripHandForSync(state.save());

        assertFalse(syncTag.contains("Hand"), "hand contents must never reach the update tag");
        assertEquals(2, syncTag.getInt("HandCount"));
        // Seat identity survives the strip so the client keeps a consistent view.
        assertTrue(syncTag.hasUUID("Occupant"));
    }

    @Test
    void sectionTagCarriesNoSurfaceAnyMore()
    {
        TableSectionState state = stateWithHand();
        CompoundTag syncTag = TableSectionState.stripHandForSync(state.save());
        assertFalse(syncTag.contains("Surface"),
                "the surface is group-level state and must not travel in the section tag");
    }

    @Test
    void stripDoesNotMutateTheSavedTag()
    {
        TableSectionState state = stateWithHand();
        CompoundTag saved = state.save();
        TableSectionState.stripHandForSync(saved);
        assertTrue(saved.contains("Hand"),
                "the original save tag must remain intact for persistence");
    }

    @Test
    void strippedTagReloadsWithoutHand()
    {
        TableSectionState state = stateWithHand();
        TableSectionState loaded = TableSectionState.load(TableSectionState.stripHandForSync(state.save()));
        assertEquals(0, loaded.getHandCount());
        assertEquals(state.getOccupantId(), loaded.getOccupantId());
    }
}
