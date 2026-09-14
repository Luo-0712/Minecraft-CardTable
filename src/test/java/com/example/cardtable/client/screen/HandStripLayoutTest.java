package com.example.cardtable.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the hand-strip overflow rules: pitch compresses before scroll kicks
 * in, scroll clamps to the last card, and off-viewport cards stay out of the
 * hit-test range.
 */
class HandStripLayoutTest
{
    // Pitch ---------------------------------------------------------------

    @Test
    void tinyHandKeepsFullPitch()
    {
        assertEquals(HandStripLayout.FULL_PITCH, HandStripLayout.pitch(1, 800));
        assertEquals(HandStripLayout.FULL_PITCH, HandStripLayout.pitch(6, 800));
    }

    @Test
    void mediumHandCompressesButStaysAboveMinPitch()
    {
        // 40 cards at full pitch need 30+39*20=810; a 800-wide viewport forces
        // a tighter pitch, but never below MIN_PITCH.
        int pitch = HandStripLayout.pitch(40, 800);
        assertTrue(pitch < HandStripLayout.FULL_PITCH, "must compress when full pitch does not fit");
        assertTrue(pitch >= HandStripLayout.MIN_PITCH, "must never go below the minimum sliver");
        assertFalse(HandStripLayout.isOverflow(
                HandStripLayout.contentWidth(40, pitch), 800),
                "a compressible hand must not enter scroll mode");
    }

    @Test
    void hugeHandFallsBackToMinPitch()
    {
        assertEquals(HandStripLayout.MIN_PITCH, HandStripLayout.pitch(200, 800));
        assertTrue(HandStripLayout.isOverflow(
                HandStripLayout.contentWidth(200, HandStripLayout.MIN_PITCH), 800));
    }

    // Content width / overflow ---------------------------------------------

    @Test
    void contentWidthIsFirstCardPlusSteps()
    {
        assertEquals(0, HandStripLayout.contentWidth(0, HandStripLayout.FULL_PITCH));
        assertEquals(HandStripLayout.CARD_WIDTH, HandStripLayout.contentWidth(1, HandStripLayout.FULL_PITCH));
        assertEquals(30 + 9 * 20, HandStripLayout.contentWidth(10, HandStripLayout.FULL_PITCH));
    }

    @Test
    void exactFitIsNotOverflow()
    {
        int viewport = HandStripLayout.contentWidth(10, HandStripLayout.FULL_PITCH);
        assertFalse(HandStripLayout.isOverflow(viewport, viewport));
        assertTrue(HandStripLayout.isOverflow(viewport + 1, viewport));
    }

    // Scroll clamp ----------------------------------------------------------

    @Test
    void scrollClampsToZeroAndMax()
    {
        int content = 500;
        int viewport = 200;
        assertEquals(0, HandStripLayout.clampScroll(-50, content, viewport));
        assertEquals(300, HandStripLayout.clampScroll(300, content, viewport));
        assertEquals(300, HandStripLayout.clampScroll(999, content, viewport));
        assertEquals(0, HandStripLayout.clampScroll(Double.NaN, content, viewport));
    }

    @Test
    void noOverflowMeansNoScroll()
    {
        int content = 100;
        int viewport = 200;
        assertEquals(0, HandStripLayout.maxScroll(content, viewport));
        assertEquals(0, HandStripLayout.applyScroll(0, 1, content, viewport));
    }

    @Test
    void wheelNotchMovesByScrollStep()
    {
        int content = 500;
        int viewport = 200;
        assertEquals(HandStripLayout.SCROLL_STEP, HandStripLayout.applyScroll(0, 1, content, viewport));
        assertEquals(0, HandStripLayout.applyScroll(0, -1, content, viewport));
        assertEquals(300, HandStripLayout.applyScroll(290, 1, content, viewport));
    }

    // Visible range ----------------------------------------------------------

    @Test
    void smallHandIsFullyVisible()
    {
        int[] range = HandStripLayout.visibleRange(5, HandStripLayout.FULL_PITCH, 0, 800);
        assertEquals(0, range[0]);
        assertEquals(5, range[1]);
    }

    @Test
    void scrollHidesCardsOnBothSides()
    {
        int pitch = HandStripLayout.MIN_PITCH;
        int count = 100;
        int viewport = 200;
        int content = HandStripLayout.contentWidth(count, pitch);
        int offset = HandStripLayout.clampScroll(content / 2, content, viewport);
        int[] range = HandStripLayout.visibleRange(count, pitch, offset, viewport);
        assertTrue(range[0] > 0, "left cards must drop out of the hit range");
        assertTrue(range[1] < count, "right cards must drop out of the hit range");
        assertTrue(range[1] > range[0], "at least one card stays visible");
    }

    @Test
    void emptyHandHasEmptyRange()
    {
        int[] range = HandStripLayout.visibleRange(0, HandStripLayout.FULL_PITCH, 0, 800);
        assertEquals(0, range[0]);
        assertEquals(0, range[1]);
    }

    @Test
    void clampedScrollStillCoversLastCard()
    {
        // At max scroll the last card must sit fully inside the viewport.
        int pitch = HandStripLayout.MIN_PITCH;
        int count = 80;
        int viewport = 300;
        int content = HandStripLayout.contentWidth(count, pitch);
        int offset = HandStripLayout.maxScroll(content, viewport);
        int[] range = HandStripLayout.visibleRange(count, pitch, offset, viewport);
        assertEquals(count - 1, range[1] - 1, "last card must stay in range");
        int lastX = HandStripLayout.cardScreenX(count - 1, pitch, offset, 0);
        assertTrue(lastX + HandStripLayout.CARD_WIDTH <= viewport + 1,
                "last card's right edge must land inside the viewport");
    }

    // Hit test ----------------------------------------------------------------

    @Test
    void hitTestPicksTopmostOverlappingCard()
    {
        // Full pitch leaves 10px of each card under the next one; a point in
        // the overlap must resolve to the right-hand (topmost) card.
        int pitch = HandStripLayout.FULL_PITCH;
        int overlapX = 10; // inside card 0's uncovered strip? no: 0 covers [0,30), 1 starts at 20
        // x=25 is inside card 1's left sliver and card 0's body; topmost is 1.
        assertEquals(1, HandStripLayout.indexAt(5, pitch, 0, 25, 10, 0));
        assertEquals(0, HandStripLayout.indexAt(5, pitch, 0, 5, 10, 0));
        assertEquals(-1, HandStripLayout.indexAt(5, pitch, 0, 200, 10, 0));
        assertEquals(-1, HandStripLayout.indexAt(5, pitch, 0, 5, 100, 0));
    }

    @Test
    void hitTestAccountsForScrollOffset()
    {
        int pitch = HandStripLayout.MIN_PITCH;
        int offset = 40;
        // Card 5's content left is 5*8=40; at offset 40 it paints at viewport x=0.
        assertEquals(5, HandStripLayout.indexAt(20, pitch, offset, 2, 10, 0));
        // A sliver like -5 still sits on the partially-visible card 4
        // (content left 32 → screen left -8). Far left is empty.
        assertEquals(4, HandStripLayout.indexAt(20, pitch, offset, -5, 10, 0));
        assertEquals(-1, HandStripLayout.indexAt(20, pitch, offset, -50, 10, 0));
    }

    // Insert gap (manual sort) ------------------------------------------------

    @Test
    void emptyHandInsertsAtZero()
    {
        assertEquals(0, HandStripLayout.insertIndexAt(0, HandStripLayout.FULL_PITCH, 0, 0, 100));
    }

    @Test
    void leftHalfOfSlotInsertsBeforeThatCard()
    {
        int pitch = HandStripLayout.FULL_PITCH;
        int origin = 0;
        // Card 0 left=0 mid=10; card 1 left=20 mid=30; card 2 left=40 mid=50.
        assertEquals(0, HandStripLayout.insertIndexAt(3, pitch, origin, 0, 5));
        assertEquals(1, HandStripLayout.insertIndexAt(3, pitch, origin, 0, 15));
        assertEquals(2, HandStripLayout.insertIndexAt(3, pitch, origin, 0, 35));
        assertEquals(3, HandStripLayout.insertIndexAt(3, pitch, origin, 0, 80));
    }

    @Test
    void insertGapAccountsForScrollAndOrigin()
    {
        int pitch = HandStripLayout.MIN_PITCH;
        int origin = 4;
        int scroll = 20;
        // Card 1's left = 4 + 8 - 20 = -8, mid = -4; card 2 left=0 mid=4.
        assertEquals(2, HandStripLayout.insertIndexAt(5, pitch, origin, scroll, 0));
        assertEquals(5, HandStripLayout.insertIndexAt(5, pitch, origin, scroll, 40));
    }

    @Test
    void insertGapXPointsAtTheSlotEdge()
    {
        int pitch = HandStripLayout.FULL_PITCH;
        // Before card 1: its left edge.
        assertEquals(20, HandStripLayout.insertGapX(3, pitch, 0, 0, 1));
        // After the last card: last left + full card width.
        assertEquals(40 + HandStripLayout.CARD_WIDTH, HandStripLayout.insertGapX(3, pitch, 0, 0, 3));
        // Empty hand still yields a sane origin.
        assertEquals(HandStripLayout.CARD_WIDTH, HandStripLayout.insertGapX(0, pitch, 0, 0, 0));
    }
}
