package com.example.cardtable.client.screen;

/**
 * Pure view transform between the shared table space and one client's
 * screen. The table state is stored once per group in playfield-normalized
 * (0..1) coordinates, so every player agrees on where the cards are relative
 * to each other; the per-view rotation is what makes each player see "their
 * own side" at the bottom of the screen, like sitting at a real table.
 *
 * <p>Two spaces meet here:</p>
 * <ul>
 *   <li><b>Table pixel space</b> — the unrotated playfield rectangle. Zone
 *       rects and card positions are computed here first; the result is
 *       identical for every client, which is what keeps rendering, hit
 *       testing and drop coordinates consistent.</li>
 *   <li><b>Screen space</b> — the table pixel space rotated by
 *       {@code quarters × 90°} clockwise and mapped onto the same on-screen
 *       playfield rect.</li>
 * </ul>
 *
 * <p>The rotation is derived from the viewer's own seat cell: after the
 * transform the seat lands in the screen's bottom half (the side "in front
 * of" the player). Spectators and unseated players get quarter 0. Every
 * method is a pure function, so the mapping is unit-testable without
 * Minecraft.</p>
 */
public final class TableView
{
    private final int playLeft;
    private final int playTop;
    private final int playWidth;
    private final int playHeight;
    private final int quarters;

    public TableView(int playLeft, int playTop, int playWidth, int playHeight, int quarters)
    {
        this.playLeft = playLeft;
        this.playTop = playTop;
        this.playWidth = Math.max(1, playWidth);
        this.playHeight = Math.max(1, playHeight);
        this.quarters = Math.floorMod(quarters, 4);
    }

    /**
     * Picks the rotation quarter that lands the own-seat point
     * {@code (ownNormX, ownNormY)} (normalized table coordinates) in the
     * screen's bottom half. Ties resolve to the smallest quarter; the table
     * centre (spectators, one-block tables) yields 0.
     */
    public static int quartersFor(double ownNormX, double ownNormY)
    {
        double dx = ownNormX - 0.5D;
        double dy = ownNormY - 0.5D;
        // Downward screen component of the own-seat direction for r = 0..3:
        // the content turns clockwise by r × 90°, so r=0 keeps (dx, dy),
        // r=1 maps it to (-dy, dx), r=2 to (-dx, -dy) and r=3 to (dy, -dx).
        double[] downward = {dy, dx, -dy, -dx};
        int best = 0;
        for (int quarter = 1; quarter < 4; quarter++)
        {
            if (downward[quarter] > downward[best])
            {
                best = quarter;
            }
        }
        return best;
    }

    /**
     * The seat ring's bottom-most slot for a table of {@code seatCount}
     * seats: the ring's first seat starts straight up, so the slot straight
     * down is half a turn later, {@code seatCount / 2}. An even count lands
     * exactly on the bottom centre; an odd one has two slots straddling it
     * and picks the right-hand of the pair (both are equally low).
     */
    public static int seatRingBottomSlot(int seatCount)
    {
        return Math.max(0, seatCount / 2);
    }

    /**
     * The slot one seat occupies once the ring is turned so the viewer's own
     * seat ({@code ownIndex}) lands on {@code bottomSlot}. Relative order
     * around the ring is preserved; {@code ownIndex < 0} (a spectator) leaves
     * the ring unshifted.
     *
     * <p>Same idea as the content rotation, expressed for the ring: the ring
     * carries no geometry of its own, so the viewer's own seat is identified
     * by index rather than by position.</p>
     */
    public static int seatRingSlot(int index, int ownIndex, int seatCount, int bottomSlot)
    {
        if (ownIndex < 0 || seatCount <= 0)
        {
            return index;
        }
        return Math.floorMod(index + bottomSlot - ownIndex, seatCount);
    }

    /** The rotation quarter: how many times the table content turns 90°. */
    public int quarters()
    {
        return this.quarters;
    }

    /**
     * A card's own quarter-turn rotation (0/90/180/270 in table space)
     * composed with the view rotation, normalized to 0..359.
     */
    public int displayRotationDeg(int cardRotationDeg)
    {
        return Math.floorMod(cardRotationDeg + this.quarters * 90, 360);
    }

    /**
     * Maps a point of table pixel space to screen space. Fractions are kept;
     * callers round once so rendering and hit-testing agree.
     */
    public double[] tableToScreen(double px, double py)
    {
        double tx = (px - this.playLeft) / this.playWidth;
        double ty = (py - this.playTop) / this.playHeight;
        return new double[] {this.screenX(tx, ty), this.screenY(tx, ty)};
    }

    /**
     * Maps a screen point back to table pixel space (unclamped) — the exact
     * inverse of {@link #tableToScreen}; drop coordinates go through here.
     */
    public double[] screenToTable(double sx, double sy)
    {
        double nx = (sx - this.playLeft) / this.playWidth;
        double ny = (sy - this.playTop) / this.playHeight;
        return switch (this.quarters)
        {
            case 1 -> new double[] {this.playLeft + ny * this.playWidth,
                    this.playTop + (1.0D - nx) * this.playHeight};
            case 2 -> new double[] {this.playLeft + (1.0D - nx) * this.playWidth,
                    this.playTop + (1.0D - ny) * this.playHeight};
            case 3 -> new double[] {this.playLeft + (1.0D - ny) * this.playWidth,
                    this.playTop + nx * this.playHeight};
            default -> new double[] {this.playLeft + nx * this.playWidth,
                    this.playTop + ny * this.playHeight};
        };
    }

    /**
     * Maps a table pixel rect to its axis-aligned bounding rect on screen.
     * This is the mapping for <b>zone boundaries</b>: the rect is defined in
     * normalized table coordinates, so it stretches with the playfield's
     * aspect (exactly like the un-rotated view). At quarter 0 the mapping is
     * the identity, so the result is bit-exact with the input.
     *
     * @return {@code {x, y, width, height}} in screen pixels (rounded)
     */
    public int[] transformRect(int x, int y, int width, int height)
    {
        double[] a = this.tableToScreen(x, y);
        double[] b = this.tableToScreen(x + (double) width, y);
        double[] c = this.tableToScreen(x, y + (double) height);
        double[] d = this.tableToScreen(x + (double) width, y + (double) height);
        double minX = Math.min(Math.min(a[0], b[0]), Math.min(c[0], d[0]));
        double maxX = Math.max(Math.max(a[0], b[0]), Math.max(c[0], d[0]));
        double minY = Math.min(Math.min(a[1], b[1]), Math.min(c[1], d[1]));
        double maxY = Math.max(Math.max(a[1], b[1]), Math.max(c[1], d[1]));
        int left = (int) Math.round(minX);
        int top = (int) Math.round(minY);
        int right = (int) Math.round(maxX);
        int bottom = (int) Math.round(maxY);
        return new int[] {left, top, Math.max(1, right - left), Math.max(1, bottom - top)};
    }

    /**
     * Maps a card anchored in table pixel space to its on-screen bounding
     * rect, assuming an unrotated card ({@code cardRotationDeg == 0}).
     * Equivalent to {@link #transformCard(int, int, int, int, int)}.
     */
    public int[] transformCard(int x, int y, int cardWidth, int cardHeight)
    {
        return this.transformCard(x, y, cardWidth, cardHeight, 0);
    }

    /**
     * Maps a card anchored in table pixel space to its on-screen bounding
     * rect. The card's centre point goes through the view transform, but the
     * card itself keeps its pixel size — width/height swapped whenever the
     * composed display rotation is an odd quarter, since the card then lies
     * sideways on screen. Cards are fixed-size sprites, so unlike
     * {@link #transformRect} they must never stretch with the playfield's
     * aspect ratio.
     *
     * @param cardRotationDeg the card's own table-space quarter-turn (0/90/180/270)
     * @return {@code {x, y, width, height}} in screen pixels (rounded)
     */
    public int[] transformCard(int x, int y, int cardWidth, int cardHeight, int cardRotationDeg)
    {
        double[] centre = this.tableToScreen(x + cardWidth / 2.0D, y + cardHeight / 2.0D);
        boolean sideways = this.displayRotationDeg(cardRotationDeg) % 180 != 0;
        int width = sideways ? cardHeight : cardWidth;
        int height = sideways ? cardWidth : cardHeight;
        return new int[] {(int) Math.round(centre[0] - width / 2.0D),
                (int) Math.round(centre[1] - height / 2.0D), width, height};
    }

    private double screenX(double tx, double ty)
    {
        return switch (this.quarters)
        {
            case 1 -> this.playLeft + (1.0D - ty) * this.playWidth;
            case 2 -> this.playLeft + (1.0D - tx) * this.playWidth;
            case 3 -> this.playLeft + ty * this.playWidth;
            default -> this.playLeft + tx * this.playWidth;
        };
    }

    private double screenY(double tx, double ty)
    {
        return switch (this.quarters)
        {
            case 1 -> this.playTop + tx * this.playHeight;
            case 2 -> this.playTop + (1.0D - ty) * this.playHeight;
            case 3 -> this.playTop + (1.0D - tx) * this.playHeight;
            default -> this.playTop + ty * this.playHeight;
        };
    }
}
