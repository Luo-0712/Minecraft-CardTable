package com.example.cardtable.table;

import com.example.cardtable.card.CardInstance;
import com.example.cardtable.card.ZoneState;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the contract {@code TableGroupService#syncGroup} relies on: a section's
 * cached copy must carry the master's shared zones — piles included, since the
 * kind == STACK convention makes them plain generic containers — the copy must
 * not alias the master's containers, and staleness must be detectable so an
 * up-to-date copy is not rewritten on every sync.
 *
 * <p>Before this, {@code syncGroup} only marked blocks changed and never pushed
 * the master's state onto sections, so non-master sections kept — and saved to
 * disk — the piles they happened to load with, contradicting the "every other
 * section keeps a synced copy" promise in {@link TableGroupState}.</p>
 */
class TableGroupStatePropagationTest
{
    private static final ResourceLocation ACE =
            new ResourceLocation("cardtable", "standard/ace_of_spades");
    private static final ResourceLocation KING =
            new ResourceLocation("cardtable", "standard/king_of_hearts");
    private static final ResourceLocation DECK_ID =
            new ResourceLocation("cardtable", "standard/deck");
    private static final ResourceLocation DISCARD_ID =
            new ResourceLocation("cardtable", "standard/discard");

    /** Two shared piles under pack-declared ids, per the kind == STACK convention. */
    private static TableGroupState masterWithPiles()
    {
        TableGroupState master = TableGroupState.create();
        ZoneState deck = ZoneState.stack();
        deck.addToStackTop(new CardInstance(ACE));
        master.getSharedZones().put(DECK_ID, deck);
        ZoneState discard = ZoneState.stack();
        discard.addToStackTop(new CardInstance(KING));
        master.getSharedZones().put(DISCARD_ID, discard);
        return master;
    }

    @Test
    void copyCarriesBothPiles()
    {
        TableGroupState master = masterWithPiles();
        CardInstance deckTop = master.getSharedZones().get(DECK_ID).stackCards().get(0);
        CardInstance discardTop = master.getSharedZones().get(DISCARD_ID).stackCards().get(0);

        TableGroupState copy = new TableGroupState(master);

        assertEquals(1, copy.getSharedZones().get(DECK_ID).size(),
                "the deck pile must reach the section copies");
        assertEquals(1, copy.getSharedZones().get(DISCARD_ID).size(),
                "the discard pile must reach the section copies");
        assertEquals(deckTop.instanceId(),
                copy.getSharedZones().get(DECK_ID).stackCards().get(0).instanceId());
        assertEquals(discardTop.instanceId(),
                copy.getSharedZones().get(DISCARD_ID).stackCards().get(0).instanceId());
    }

    @Test
    void copiedPilesAreIndependent()
    {
        TableGroupState master = masterWithPiles();
        TableGroupState copy = new TableGroupState(master);

        copy.getSharedZones().get(DECK_ID).addToStackTop(new CardInstance(ACE));
        copy.getSharedZones().get(DISCARD_ID).addToStackTop(new CardInstance(KING));

        assertEquals(1, master.getSharedZones().get(DECK_ID).size(),
                "a copy must not alias the master's deck pile");
        assertEquals(1, master.getSharedZones().get(DISCARD_ID).size(),
                "a copy must not alias the master's discard pile");
    }

    @Test
    void pilesKeepBottomToTopOrder()
    {
        ZoneState pile = ZoneState.stack();
        CardInstance first = new CardInstance(ACE);
        CardInstance second = new CardInstance(KING);
        pile.addToStackTop(first);
        pile.addToStackTop(second);

        assertEquals(second.instanceId(), pile.takeFromStackTop().instanceId(),
                "the last card added is the top of the pile");
        assertEquals(first.instanceId(), pile.takeFromStackTop().instanceId());
    }

    @Test
    void staleVersionIsDetected()
    {
        TableGroupState master = masterWithPiles();
        master.bumpVersion();
        TableGroupState section = new TableGroupState(master);
        master.bumpVersion();

        assertTrue(TableGroupService.groupStatesDiffer(master, section),
                "a version bump is what marks the cached copy stale");
    }

    @Test
    void mergedSectionIsDetectedByTableId()
    {
        TableGroupState master = masterWithPiles();
        TableGroupState freshlyMerged = TableGroupState.create();

        assertTrue(TableGroupService.groupStatesDiffer(master, freshlyMerged),
                "a section that just merged in still carries its own table id");
    }

    @Test
    void upToDateCopyIsNotStale()
    {
        TableGroupState master = masterWithPiles();
        TableGroupState section = new TableGroupState(master);

        assertFalse(TableGroupService.groupStatesDiffer(master, section),
                "an up-to-date copy must not be rewritten on every sync");
    }
}
