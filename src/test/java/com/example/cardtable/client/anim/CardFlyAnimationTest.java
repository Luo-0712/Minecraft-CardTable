package com.example.cardtable.client.anim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the flight feel: straight segment (no arc) and a non-linear
 * ease-out that brakes into the target rather than sliding at constant speed.
 */
class CardFlyAnimationTest
{
    private static CardFlyAnimation flight(long start, long duration)
    {
        return CardFlyAnimation.anonymous(true,
                0, 0, 34, 48,
                100, 40, 34, 48,
                0, 0,
                start, duration);
    }

    @Test
    void midFlightStaysOnStraightSegment()
    {
        CardFlyAnimation anim = flight(0L, 400L);
        int[] mid = anim.rectAt(200L);
        // Collinear with from→to up to independent integer rounding; a parabolic
        // lift would push Y well above the chord.
        int[] from = {0, 0};
        int[] to = {100, 40};
        int[] now = {mid[0], mid[1]};
        int cross = (to[0] - from[0]) * (now[1] - from[1])
                - (to[1] - from[1]) * (now[0] - from[0]);
        assertTrue(Math.abs(cross) <= 50,
                "card must travel along the straight from→to segment, cross=" + cross);
        float chordY = mid[0] * (to[1] / (float) to[0]);
        assertTrue(mid[1] <= Math.round(chordY) + 1,
                "no parabolic lift above the straight segment");
    }

    @Test
    void lateFramesCoverLessDistanceThanEarlyOnes()
    {
        CardFlyAnimation anim = flight(0L, 400L);
        float early = anim.easedProgress(100L) - anim.easedProgress(0L);
        float late = anim.easedProgress(400L) - anim.easedProgress(300L);
        assertTrue(late < early, "approach must decelerate (non-linear ease-out)");
        // Deceleration is not linear: the last quarter covers far less than a
        // constant-speed quarter would (0.25 of the path).
        assertTrue(late < 0.10F, "final quarter should cover well under a linear 25%");
        assertEquals(1.0F, anim.easedProgress(400L), 1e-5F);
        assertEquals(0.0F, anim.easedProgress(0L), 1e-5F);
    }

    @Test
    void startIsFasterThanConstantSpeedWouldBe()
    {
        CardFlyAnimation anim = flight(0L, 400L);
        // Quartic ease-out: at t=0.25, progress is 1-(0.75)^4 ≈ 0.684 > 0.25.
        assertTrue(anim.easedProgress(100L) > 0.25F,
                "ease-out must front-load distance so the tail can brake softly");
    }
}
