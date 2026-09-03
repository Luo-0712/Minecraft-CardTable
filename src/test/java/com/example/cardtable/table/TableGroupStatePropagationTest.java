package com.example.cardtable.table;

import com.example.cardtable.card.CardInstance;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the contract {@code TableGroupService#syncGroup} relies on: a section's
 * cached copy must carry the master's shared piles (not just its identity), the
 * copy must not alias the master's lists, and staleness must be detectable so
 * an up-to-date copy is not rewritten on every sync.
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

    private static TableGroupState masterWithPiles()
    {
        TableGroupState master = TableGroupState.create();
        master.addToDrawPileTop(new CardInstance(ACE));
        master.addToDiscardPileTop(new CardInstance(KING));
        return master;
    }

    @Test
    void copyCarriesBothPiles()
    {
        TableGroupState master = masterWithPiles();
        CardInstance drawTop = master.getDrawPile().get(0);
        CardInstance discardTop = master.getDiscardPile().get(0);

        TableGroupState copy = new TableGroupState(master);

        assertEquals(1, copy.getDrawPile().size(),
                "the draw pile must reach the section copies");
        assertEquals(1, copy.getDiscardPile().size(),
                "the discard pile must reach the section copies");
        assertEquals(drawTop.instanceId(), copy.getDrawPile().get(0).instanceId());
        assertEquals(discardTop.instanceId(), copy.getDiscardPile().get(0).instanceId());
    }

    @Test
    void copiedPilesAreIndependent()
    {
        TableGroupState master = masterWithPiles();
        TableGroupState copy = new TableGroupState(master);

        copy.addToDrawPileTop(new CardInstance(ACE));
        copy.addToDiscardPileTop(new CardInstance(KING));

        assertEquals(1, master.getDrawPile().size(),
                "a copy must not alias the master's draw pile");
        assertEquals(1, master.getDiscardPile().size(),
                "a copy must not alias the master's discard pile");
    }

    @Test
    void pilesKeepBottomToTopOrder()
    {
        TableGroupState state = TableGroupState.create();
        CardInstance first = new CardInstance(ACE);
        CardInstance second = new CardInstance(KING);
        state.addToDrawPileTop(first);
        state.addToDrawPileTop(second);

        assertEquals(second.instanceId(), state.takeFromDrawPileTop().instanceId(),
                "the last card added is the top of the pile");
        assertEquals(first.instanceId(), state.takeFromDrawPileTop().instanceId());
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
