package com.example.cardtable.client.anim;

import com.example.cardtable.card.CardInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks remote draw/play animation attribution, the own-hand face rules and
 * the last-entered-table highlight: priming, remote hand-count flights, PLACED
 * landings, and the changes that must not animate at all.
 */
class CardAnimationTrackerTest
{
    private static final BlockPos SEAT = new BlockPos(0, 64, 0);
    private static final BlockPos SEAT_B = new BlockPos(1, 64, 0);
    private static final int[] DRAW = {10, 10, 34, 48};
    private static final int[] SEAT_PLATE = {100, 200, 26, 26};
    private static final int[] SEAT_HAND = {140, 200, 12, 17};
    private static final int[] TABLE_CARD = {80, 90, 34, 48};

    /** A table placement of a card whose identity the view cannot tell. */
    private static CardAnimationTracker.Placement placed()
    {
        return new CardAnimationTracker.Placement(CardAnimationTracker.Kind.PLACED,
                TABLE_CARD[0], TABLE_CARD[1], TABLE_CARD[2], TABLE_CARD[3], 0, null, null);
    }

    /** The same table rect, but the view knows which card lies there. */
    private static CardAnimationTracker.Placement placedAs(CardInstance card)
    {
        return new CardAnimationTracker.Placement(CardAnimationTracker.Kind.PLACED,
                TABLE_CARD[0], TABLE_CARD[1], TABLE_CARD[2], TABLE_CARD[3], 0, null, card);
    }

    /** One own-hand slot at {@code x}; the strip's fan position for this frame. */
    private static CardAnimationTracker.Placement hand(CardInstance card, int x)
    {
        return new CardAnimationTracker.Placement(CardAnimationTracker.Kind.HAND,
                x, 400, 30, 42, 0, SEAT, card);
    }

    private static CardInstance card()
    {
        return new CardInstance(new ResourceLocation("cardtable:test_card"));
    }

    private static Map<UUID, CardAnimationTracker.Placement> cards(UUID id,
                                                                   CardAnimationTracker.Placement placement)
    {
        Map<UUID, CardAnimationTracker.Placement> map = new HashMap<>();
        map.put(id, placement);
        return map;
    }

    private static Map<BlockPos, int[]> seatPlates()
    {
        Map<BlockPos, int[]> map = new HashMap<>();
        map.put(SEAT, SEAT_PLATE);
        return map;
    }

    private static Map<BlockPos, int[]> seatHands()
    {
        Map<BlockPos, int[]> map = new HashMap<>();
        map.put(SEAT, SEAT_HAND);
        return map;
    }

    @Test
    void firstObservePrimesWithoutFlights()
    {
        CardAnimationTracker tracker = new CardAnimationTracker();
        UUID card = UUID.randomUUID();
        tracker.observe(cards(card, placed()), Map.of(), seatPlates(), seatHands(), DRAW, 0L);
        assertTrue(tracker.flights().isEmpty(), "join must not animate the existing table");
        assertNull(tracker.lastTableCardId());
    }

    @Test
    void remoteDrawSpawnsFaceDownFlightToSeatHand()
    {
        CardAnimationTracker tracker = new CardAnimationTracker();
        tracker.observe(Map.of(), Map.of(SEAT, 0), seatPlates(), seatHands(), DRAW, 0L);

        tracker.observe(Map.of(), Map.of(SEAT, 1), seatPlates(), seatHands(), DRAW, 16L);
        assertEquals(1, tracker.flights().size());
        CardFlyAnimation flight = tracker.flights().get(0);
        assertFalse(flight.faceUp(), "a remote draw must not reveal the face");
        int[] rect = flight.rectAt(16L);
        assertEquals(DRAW[0], rect[0]);
        assertEquals(DRAW[1], rect[1]);
    }

    @Test
    void remotePlayFromSeatHighlightsAndFlies()
    {
        CardAnimationTracker tracker = new CardAnimationTracker();
        tracker.observe(Map.of(), Map.of(SEAT, 1), seatPlates(), seatHands(), DRAW, 0L);

        UUID card = UUID.randomUUID();
        tracker.observe(cards(card, placed()), Map.of(SEAT, 0), seatPlates(), seatHands(), DRAW, 16L);
        assertEquals(1, tracker.flights().size());
        assertTrue(tracker.flights().get(0).faceUp(), "a public table discard shows its face");
        assertEquals(card, tracker.lastTableCardId());
    }

    @Test
    void tableToTableRepositionDoesNotRetargetHighlight()
    {
        CardAnimationTracker tracker = new CardAnimationTracker();
        UUID first = UUID.randomUUID();
        tracker.observe(Map.of(), Map.of(), seatPlates(), seatHands(), DRAW, 0L);
        tracker.observe(cards(first, placed()), Map.of(), seatPlates(), seatHands(), DRAW, 16L);
        assertEquals(first, tracker.lastTableCardId());

        UUID second = UUID.randomUUID();
        Map<UUID, CardAnimationTracker.Placement> both = new HashMap<>();
        both.put(first, placed());
        both.put(second, placed());
        tracker.observe(both, Map.of(), seatPlates(), seatHands(), DRAW, 32L);
        assertEquals(second, tracker.lastTableCardId());
    }

    @Test
    void localPlaySeedsBaselineSoLandingDoesNotRespawn()
    {
        CardAnimationTracker tracker = new CardAnimationTracker();
        UUID card = UUID.randomUUID();
        tracker.observe(Map.of(), Map.of(), seatPlates(), seatHands(), DRAW, 0L);

        tracker.spawnLocalPlay(null, card, CardAnimationTracker.Kind.PLACED,
                200, 300, 34, 48,
                TABLE_CARD[0], TABLE_CARD[1], TABLE_CARD[2], TABLE_CARD[3],
                0, 0, true, 16L);
        assertTrue(tracker.isFlying(card));
        assertEquals(card, tracker.lastTableCardId());

        // Server landing while the local flight is still in the air: the card
        // is not in the placement map (it is flying), so observe must not
        // treat the eventual reappearance as a brand-new appear.
        tracker.observe(Map.of(), Map.of(), seatPlates(), seatHands(), DRAW, 50L);
        assertEquals(1, tracker.flights().size());

        // Flight finished; the card is now on the table at the same rect.
        tracker.tick(16L + 400L);
        tracker.observe(cards(card, placed()), Map.of(), seatPlates(), seatHands(), DRAW, 450L);
        assertEquals(0, tracker.flights().size(), "landing must not spawn a second flight");
        assertEquals(card, tracker.lastTableCardId());
    }

    @Test
    void clearResetsHighlightAndPriming()
    {
        CardAnimationTracker tracker = new CardAnimationTracker();
        UUID card = UUID.randomUUID();
        tracker.observe(Map.of(), Map.of(), seatPlates(), seatHands(), DRAW, 0L);
        tracker.observe(cards(card, placed()), Map.of(), seatPlates(), seatHands(), DRAW, 16L);
        assertNotNull(tracker.lastTableCardId());

        tracker.clear();
        assertNull(tracker.lastTableCardId());
        tracker.observe(Map.of(), Map.of(), seatPlates(), seatHands(), DRAW, 32L);
        assertTrue(tracker.flights().isEmpty());
    }

    @Test
    void ownHandReflowDoesNotFly()
    {
        CardAnimationTracker tracker = new CardAnimationTracker();
        CardInstance card = card();
        tracker.observe(cards(card.instanceId(), hand(card, 8)),
                Map.of(), seatPlates(), seatHands(), DRAW, 0L);

        // A draw widens the fan: the same hand card slides to a new slot. That
        // is the strip's own layout, and animating it would cover the face the
        // owner is entitled to read with a card back.
        tracker.observe(cards(card.instanceId(), hand(card, 22)),
                Map.of(), seatPlates(), seatHands(), DRAW, 16L);
        assertTrue(tracker.flights().isEmpty(), "in-strip reflow is layout, not a move");
    }

    @Test
    void heldCardReturnIsNotADraw()
    {
        CardAnimationTracker tracker = new CardAnimationTracker();
        CardInstance card = card();
        tracker.observe(cards(card.instanceId(), hand(card, 8)),
                Map.of(), seatPlates(), seatHands(), DRAW, 0L);

        // Picked up: the drag preview owns the card, so the frame data drops it.
        tracker.holdCard(card.instanceId());
        tracker.observe(Map.of(), Map.of(), seatPlates(), seatHands(), DRAW, 16L);
        assertTrue(tracker.flights().isEmpty());

        // Let go in a new slot (a manual sort): the return must compare against
        // the pre-drag slot, not read as the card drawing itself from the stock.
        tracker.holdCard(null);
        tracker.observe(cards(card.instanceId(), hand(card, 30)),
                Map.of(), seatPlates(), seatHands(), DRAW, 32L);
        assertTrue(tracker.flights().isEmpty(), "a manual sort must not fly the card in from the stock");
    }

    @Test
    void ownDrawFliesTheRealCardFaceUp()
    {
        CardAnimationTracker tracker = new CardAnimationTracker();
        tracker.observe(Map.of(), Map.of(), seatPlates(), seatHands(), DRAW, 0L);

        CardInstance card = card();
        tracker.observe(cards(card.instanceId(), hand(card, 24)),
                Map.of(), seatPlates(), seatHands(), DRAW, 16L);
        assertEquals(1, tracker.flights().size());
        CardFlyAnimation flight = tracker.flights().get(0);
        assertSame(card, flight.card(), "the drawn card flies as itself, not as a stand-in");
        assertEquals(card.instanceId(), flight.instanceId());
        assertTrue(flight.faceUp(), "the owner always reads their own hand");
    }

    @Test
    void ownDrawLandingDoesNotFlyAgain()
    {
        CardAnimationTracker tracker = new CardAnimationTracker();
        tracker.observe(Map.of(), Map.of(), seatPlates(), seatHands(), DRAW, 0L);

        CardInstance card = card();
        tracker.observe(cards(card.instanceId(), hand(card, 24)),
                Map.of(), seatPlates(), seatHands(), DRAW, 16L);
        assertEquals(1, tracker.flights().size());

        // While it flies the strip hands the card over, so the frame data has
        // no placement for it; the baseline must still remember where it lands.
        tracker.observe(Map.of(), Map.of(), seatPlates(), seatHands(), DRAW, 120L);
        tracker.tick(16L + 400L);
        tracker.observe(cards(card.instanceId(), hand(card, 24)),
                Map.of(), seatPlates(), seatHands(), DRAW, 500L);
        assertTrue(tracker.flights().isEmpty(), "one draw is one flight");
    }

    @Test
    void faceDownPlayKeepsItsBackInFlight()
    {
        CardAnimationTracker tracker = new CardAnimationTracker();
        tracker.observe(Map.of(), Map.of(SEAT, 1), seatPlates(), seatHands(), DRAW, 0L);

        CardInstance card = card(); // fresh instances are face down
        tracker.observe(cards(card.instanceId(), placedAs(card)),
                Map.of(SEAT, 0), seatPlates(), seatHands(), DRAW, 16L);
        assertEquals(1, tracker.flights().size());
        CardFlyAnimation flight = tracker.flights().get(0);
        assertSame(card, flight.card(), "the landing card is known, so it flies as itself");
        assertFalse(flight.faceUp(), "a card played face down must not turn over on the way");
    }
}
