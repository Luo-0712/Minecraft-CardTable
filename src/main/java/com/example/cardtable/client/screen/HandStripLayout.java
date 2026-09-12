package com.example.cardtable.client.screen;

/**
 * Pure layout math for the own-hand strip: adaptive pitch, content width,
 * scroll clamp and visible-index bounds. Extracted from the screen so the
 * overflow rules are unit-testable without Minecraft.
 *
 * <p>Three regimes, decided purely from card count and viewport width:</p>
 * <ol>
 *   <li><b>Comfortable</b> — full pitch fits; the fan looks like a small hand.</li>
 *   <li><b>Compressed</b> — pitch shrinks toward {@link #MIN_PITCH} until the
 *       whole fan still fits; every card stays at least a sliver visible.</li>
 *   <li><b>Scrolled</b> — even {@link #MIN_PITCH} cannot fit; the strip becomes
 *       a fixed viewport over a longer content row, panned by
 *       {@code scrollOffset}.</li>
 * </ol>
 */
public final class HandStripLayout
{
    /** Card sprite width in GUI pixels (matches the screen's hand card size). */
    public static final int CARD_WIDTH = 30;
    /** Card sprite height in GUI pixels. */
    public static final int CARD_HEIGHT = 42;
    /** Comfortable step between neighbouring cards: two thirds of the card is left showing. */
    public static final int FULL_PITCH = 20;
    /** Tightest step that still leaves a readable colour/pattern sliver. */
    public static final int MIN_PITCH = 8;
    /** Horizontal scroll step for one wheel notch, in content pixels. */
    public static final int SCROLL_STEP = MIN_PITCH * 2;
    /** Side padding of the visible viewport relative to the window. */
    public static final int VIEWPORT_PAD_X = 4;

    private HandStripLayout()
    {
    }

    /**
     * Pitch (left-edge step) between neighbouring cards once the fan is
     * fitted to {@code availableWidth}. Never below {@link #MIN_PITCH} and
     * never above {@link #FULL_PITCH}.
     *
     * <p>Solves {@code CARD_WIDTH + (n-1) * pitch ≤ availableWidth} so the
     * first card's full width is counted: a plain {@code width / (n-1)}
     * under-counts and leaves the last card hanging off the edge.</p>
     *
     * @param cardCount       number of cards currently in the hand (≥ 0)
     * @param availableWidth  pixel width the strip may occupy
     */
    public static int pitch(int cardCount, int availableWidth)
    {
        if (cardCount <= 1)
        {
            return FULL_PITCH;
        }
        int fitted = (availableWidth - CARD_WIDTH) / (cardCount - 1);
        return Math.max(MIN_PITCH, Math.min(FULL_PITCH, fitted));
    }

    /**
     * On-screen content width of a fan of {@code cardCount} cards at the
     * given pitch: the first card's full width plus every further step.
     */
    public static int contentWidth(int cardCount, int pitch)
    {
        if (cardCount <= 0)
        {
            return 0;
        }
        return CARD_WIDTH + (cardCount - 1) * pitch;
    }

    /**
     * Viewport width the strip actually paints into: the window minus side
     * padding, never negative.
     */
    public static int viewportWidth(int screenWidth)
    {
        return Math.max(0, screenWidth - VIEWPORT_PAD_X * 2);
    }

    /**
     * Whether the content row is longer than the viewport and therefore needs
     * panning. A single card or an exact fit is never scrolled.
     */
    public static boolean isOverflow(int contentWidth, int viewportWidth)
    {
        return contentWidth > viewportWidth;
    }

    /** Maximum legal scroll offset: exactly enough to bring the last card's right edge into view. */
    public static int maxScroll(int contentWidth, int viewportWidth)
    {
        return Math.max(0, contentWidth - viewportWidth);
    }

    /**
     * Clamps a scroll offset into {@code [0, maxScroll]}. Non-finite or
     * negative inputs collapse to 0.
     */
    public static int clampScroll(double scrollOffset, int contentWidth, int viewportWidth)
    {
        if (!Double.isFinite(scrollOffset))
        {
            return 0;
        }
        int max = maxScroll(contentWidth, viewportWidth);
        int value = (int) Math.round(scrollOffset);
        return Math.max(0, Math.min(max, value));
    }

    /**
     * Applies one wheel notch: {@code direction} is typically ±1 from
     * {@code mouseScrolled}. Positive scrolls toward later cards.
     */
    public static int applyScroll(int currentOffset, double direction, int contentWidth, int viewportWidth)
    {
        double step = direction * SCROLL_STEP;
        return clampScroll(currentOffset + step, contentWidth, viewportWidth);
    }

    /**
     * Inclusive-exclusive index range of cards whose painted rect intersects
     * the viewport. Cards fully left of the offset or fully right of
     * {@code offset + viewport} are skipped by hit-test and hover.
     *
     * @return {@code {firstIndex, endIndexExclusive}}; empty hand yields
     *         {@code {0, 0}}.
     */
    public static int[] visibleRange(int cardCount, int pitch, int scrollOffset, int viewportWidth)
    {
        if (cardCount <= 0)
        {
            return new int[] {0, 0};
        }
        int content = contentWidth(cardCount, pitch);
        int offset = clampScroll(scrollOffset, content, viewportWidth);
        // Card i occupies content-x [i*pitch, i*pitch + CARD_WIDTH).
        // Visible when that interval meets [offset, offset + viewportWidth).
        int first = 0;
        if (pitch > 0)
        {
            first = Math.max(0, (offset - CARD_WIDTH) / pitch + 1);
        }
        int lastExclusive = cardCount;
        if (pitch > 0)
        {
            // Last i with i*pitch < offset + viewportWidth
            int lastInclusive = (offset + viewportWidth - 1) / pitch;
            lastExclusive = Math.min(cardCount, lastInclusive + 1);
        }
        if (first >= lastExclusive)
        {
            // Degenerate tiny viewport: still show at least one card under the
            // scroll origin so the strip never appears empty while cards exist.
            first = Math.min(cardCount - 1, Math.max(0, offset / Math.max(1, pitch)));
            lastExclusive = first + 1;
        }
        return new int[] {first, lastExclusive};
    }

    /**
     * Screen-space x of a card's left edge inside the viewport.
     *
     * @param index       card index in the hand
     * @param pitch       layout pitch from {@link #pitch}
     * @param scrollOffset current scroll (already clamped by the caller)
     * @param viewportLeft screen x of the viewport's left edge
     */
    public static int cardScreenX(int index, int pitch, int scrollOffset, int viewportLeft)
    {
        return viewportLeft + index * pitch - scrollOffset;
    }

    /**
     * Index of the topmost card under a viewport-local mouse x, scanning
     * right-to-left so overlapping cards pick what the player sees on top.
     * Returns -1 when the point is outside every card.
     *
     * <p>{@code scrollOffset} is used as-is: the render and input paths
     * already clamp it against the live content/viewport pair.</p>
     *
     * @param mouseXViewport x relative to the viewport's left edge
     */
    public static int indexAt(int cardCount, int pitch, int scrollOffset, int mouseXViewport, int mouseY,
                              int stripTop)
    {
        if (cardCount <= 0
                || mouseY < stripTop || mouseY >= stripTop + CARD_HEIGHT)
        {
            return -1;
        }
        int offset = Math.max(0, scrollOffset);
        for (int index = cardCount - 1; index >= 0; index--)
        {
            int left = index * pitch - offset;
            if (mouseXViewport >= left && mouseXViewport < left + CARD_WIDTH)
            {
                return index;
            }
        }
        return -1;
    }
}
