package com.example.cardtable.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the pure view transform that keeps every client's view of the shared
 * table consistent: the rotation is derived from the own seat's direction,
 * the table/screen mapping is an exact inverse pair, quarter-turned rects
 * swap their dimensions, and quarter 0 is the identity.
 */
class TableViewTest
{
    // A non-square playfield, so axis-swap bugs cannot hide behind symmetry.
    private static final int LEFT = 100;
    private static final int TOP = 50;
    private static final int WIDTH = 800;
    private static final int HEIGHT = 400;

    // Rotation derivation ------------------------------------------------------

    @Test
    void seatAboveRotatesTheTableHalfATurn()
    {
        // Own seat near the top edge: rotating the content by 180° puts it at
        // the bottom, "in front of" the player.
        assertEquals(2, TableView.quartersFor(0.5D, 0.1D));
    }

    @Test
    void seatBelowKeepsTheTableUpright()
    {
        assertEquals(0, TableView.quartersFor(0.5D, 0.9D));
    }

    @Test
    void seatOnTheLeftTurnsTheTableCounterClockwise()
    {
        // Left seat: the quarter that moves the seat downward is 3 (270° CW).
        assertEquals(3, TableView.quartersFor(0.1D, 0.5D));
        // Right seat: the mirrored case is 1 (90° CW).
        assertEquals(1, TableView.quartersFor(0.9D, 0.5D));
    }

    @Test
    void centreAndCornersResolveStably()
    {
        // The exact centre (spectator) stays un-rotated.
        assertEquals(0, TableView.quartersFor(0.5D, 0.5D));
        // Diagonal seats pick the dominant axis's rotation.
        assertEquals(2, TableView.quartersFor(0.2D, 0.2D));
        assertEquals(0, TableView.quartersFor(0.8D, 0.8D));
    }

    // Table ⇄ screen mapping ---------------------------------------------------

    @Test
    void quarterZeroIsTheIdentity()
    {
        TableView view = new TableView(LEFT, TOP, WIDTH, HEIGHT, 0);
        double[] screen = view.tableToScreen(LEFT + 123, TOP + 57);
        assertEquals(LEFT + 123, screen[0], 1e-9);
        assertEquals(TOP + 57, screen[1], 1e-9);

        int[] rect = view.transformRect(LEFT + 10, TOP + 20, 34, 48);
        assertEquals(LEFT + 10, rect[0]);
        assertEquals(TOP + 20, rect[1]);
        assertEquals(34, rect[2]);
        assertEquals(48, rect[3]);
    }

    @Test
    void screenToTableInvertsTableToScreenForEveryQuarter()
    {
        for (int quarters = 0; quarters < 4; quarters++)
        {
            TableView view = new TableView(LEFT, TOP, WIDTH, HEIGHT, quarters);
            double px = LEFT + 123.5D;
            double py = TOP + 234.75D;
            double[] screen = view.tableToScreen(px, py);
            double[] back = view.screenToTable(screen[0], screen[1]);
            assertEquals(px, back[0], 1e-6, "table x must survive the round trip at q" + quarters);
            assertEquals(py, back[1], 1e-6, "table y must survive the round trip at q" + quarters);
        }
    }

    @Test
    void everyQuarterMapsThePlayfieldOntoItself()
    {
        for (int quarters = 0; quarters < 4; quarters++)
        {
            TableView view = new TableView(LEFT, TOP, WIDTH, HEIGHT, quarters);
            double[] topLeft = view.tableToScreen(LEFT, TOP);
            double[] bottomRight = view.tableToScreen(LEFT + WIDTH, TOP + HEIGHT);
            // Corner positions may swap, but they must stay on the playfield
            // bounds — the table is never scaled or shifted by the rotation.
            assertEquals(LEFT, Math.min(topLeft[0], bottomRight[0]), 1e-9);
            assertEquals(LEFT + WIDTH, Math.max(topLeft[0], bottomRight[0]), 1e-9);
            assertEquals(TOP, Math.min(topLeft[1], bottomRight[1]), 1e-9);
            assertEquals(TOP + HEIGHT, Math.max(topLeft[1], bottomRight[1]), 1e-9);
        }
    }

    @Test
    void oddQuartersSwapTheCardDimensionsExactly()
    {
        // Cards are fixed-size sprites: odd quarters swap their pixel width
        // and height around the mapped centre, never stretch them.
        TableView quarter = new TableView(LEFT, TOP, WIDTH, HEIGHT, 1);
        int[] swapped = quarter.transformCard(LEFT + 10, TOP + 20, 34, 48);
        assertEquals(48, swapped[2], "a quarter-turned card must swap width and height");
        assertEquals(34, swapped[3]);
        double[] centre = quarter.tableToScreen(LEFT + 10 + 17.0D, TOP + 20 + 24.0D);
        assertEquals((int) Math.round(centre[0] - 24.0D), swapped[0], "the AABB must centre on the mapped centre");
        assertEquals((int) Math.round(centre[1] - 17.0D), swapped[1], "the AABB must centre on the mapped centre");

        TableView half = new TableView(LEFT, TOP, WIDTH, HEIGHT, 2);
        int[] kept = half.transformCard(LEFT + 10, TOP + 20, 34, 48);
        assertEquals(34, kept[2], "a half-turned card keeps its dimensions");
        assertEquals(48, kept[3]);
    }

    @Test
    void zoneRectsFollowTheRotatedTableQuadrants()
    {
        // The top-left quadrant of the table lands in the top-right quadrant
        // of the screen after a quarter turn (content turns clockwise).
        TableView quarter = new TableView(LEFT, TOP, WIDTH, HEIGHT, 1);
        int[] bounds = quarter.transformRect(LEFT, TOP, WIDTH / 2, HEIGHT / 2);
        assertEquals(LEFT + WIDTH / 2, bounds[0]);
        assertEquals(TOP, bounds[1]);
        assertEquals(WIDTH / 2, bounds[2]);
        assertEquals(HEIGHT / 2, bounds[3]);
    }

    @Test
    void rotatedCardPositionsStayConsistentBetweenViewers()
    {
        // The core relative-position guarantee: a fixed table point maps to
        // screen points that are exact rotations of each other, never to
        // different table locations. Round-tripping any viewer's screen point
        // back must land on the same table coordinates.
        double tableX = LEFT + 0.3D * WIDTH;
        double tableY = TOP + 0.7D * HEIGHT;
        for (int quarters = 0; quarters < 4; quarters++)
        {
            TableView view = new TableView(LEFT, TOP, WIDTH, HEIGHT, quarters);
            double[] screen = view.tableToScreen(tableX, tableY);
            double[] back = view.screenToTable(screen[0], screen[1]);
            assertEquals(tableX, back[0], 1e-6);
            assertEquals(tableY, back[1], 1e-6);
        }
    }

    @Test
    void displayRotationComposesCardAndViewRotation()
    {
        TableView view = new TableView(LEFT, TOP, WIDTH, HEIGHT, 3);
        assertEquals(270, view.displayRotationDeg(0));
        assertEquals(0, view.displayRotationDeg(90));
        assertEquals(90, view.displayRotationDeg(180));
        assertEquals(180, view.displayRotationDeg(270));
    }

    @Test
    void degeneratePlayfieldStillTransforms()
    {
        // A clamped-to-nothing playfield (tiny window) must not divide by zero.
        TableView view = new TableView(0, 0, 1, 1, 2);
        double[] screen = view.tableToScreen(0, 0);
        double[] back = view.screenToTable(screen[0], screen[1]);
        assertTrue(Double.isFinite(back[0]) && Double.isFinite(back[1]));
    }
}
