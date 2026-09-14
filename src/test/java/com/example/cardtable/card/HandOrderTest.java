package com.example.cardtable.card;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the post-removal insert-index semantics of a manual hand sort.
 */
class HandOrderTest
{
    private static CardInstance card(String path)
    {
        return new CardInstance(new ResourceLocation("cardtable", path));
    }

    private static List<CardInstance> handOf(String... paths)
    {
        List<CardInstance> hand = new ArrayList<>();
        for (String path : paths)
        {
            hand.add(card(path));
        }
        return hand;
    }

    private static List<String> paths(List<CardInstance> hand)
    {
        return hand.stream().map(c -> c.definitionId().getPath()).toList();
    }

    @Test
    void movingCardForwardShiftsItAfterTheSkippedCards()
    {
        List<CardInstance> hand = handOf("a", "b", "c", "d", "e");
        UUID b = hand.get(1).instanceId();
        // Drag B to the gap before E: reduced list [A,C,D,E], insert at 3.
        assertTrue(HandOrder.reorder(hand, b, 3));
        assertEquals(List.of("a", "c", "d", "b", "e"), paths(hand));
    }

    @Test
    void movingCardBackwardInsertsAtTheGap()
    {
        List<CardInstance> hand = handOf("a", "b", "c", "d");
        UUID d = hand.get(3).instanceId();
        assertTrue(HandOrder.reorder(hand, d, 0));
        assertEquals(List.of("d", "a", "b", "c"), paths(hand));
    }

    @Test
    void sameSlotIsANoOp()
    {
        List<CardInstance> hand = handOf("a", "b", "c");
        UUID b = hand.get(1).instanceId();
        assertFalse(HandOrder.reorder(hand, b, 1));
        assertEquals(List.of("a", "b", "c"), paths(hand));
    }

    @Test
    void outOfRangeIndexClamps()
    {
        List<CardInstance> hand = handOf("a", "b", "c");
        UUID a = hand.get(0).instanceId();
        assertTrue(HandOrder.reorder(hand, a, 99));
        assertEquals(List.of("b", "c", "a"), paths(hand));
    }

    @Test
    void missingCardLeavesHandUntouched()
    {
        List<CardInstance> hand = handOf("a", "b");
        assertFalse(HandOrder.reorder(hand, UUID.randomUUID(), 0));
        assertEquals(List.of("a", "b"), paths(hand));
    }

    @Test
    void indexOfFindsTheCard()
    {
        List<CardInstance> hand = handOf("a", "b", "c");
        assertEquals(2, HandOrder.indexOf(hand, hand.get(2).instanceId()));
        assertEquals(-1, HandOrder.indexOf(hand, UUID.randomUUID()));
    }
}
