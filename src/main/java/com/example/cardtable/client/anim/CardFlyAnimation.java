package com.example.cardtable.client.anim;

import com.example.cardtable.card.CardInstance;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One in-flight card on the table screen: interpolates a card's screen AABB
 * from a source to a destination so draw/discard reads as a card moving
 * between a player and its target rather than a hard teleport.
 *
 * <p>Geometry is captured in screen pixels at spawn time (after the view
 * rotation is applied), so a flight is independent of the table-space
 * mapping that produced it. A short parabolic lift on Y keeps overlapping
 * cards legible while the mover is in the air.</p>
 */
public final class CardFlyAnimation
{
    /** Stable id for flights whose card identity the client does not know. */
    private static final UUID ANONYMOUS = new UUID(0L, 0L);

    @Nullable
    private final CardInstance card;
    private final UUID instanceId;
    private final boolean faceUp;
    private final int fromX;
    private final int fromY;
    private final int fromWidth;
    private final int fromHeight;
    private final int toX;
    private final int toY;
    private final int toWidth;
    private final int toHeight;
    private final int fromRotation;
    private final int toRotation;
    private final long startMillis;
    private final long durationMillis;

    public CardFlyAnimation(@Nullable CardInstance card, UUID instanceId,
                            int fromX, int fromY, int fromWidth, int fromHeight,
                            int toX, int toY, int toWidth, int toHeight,
                            int fromRotation, int toRotation, boolean faceUp,
                            long startMillis, long durationMillis)
    {
        this.card = card;
        this.instanceId = instanceId;
        this.faceUp = faceUp;
        this.fromX = fromX;
        this.fromY = fromY;
        this.fromWidth = Math.max(1, fromWidth);
        this.fromHeight = Math.max(1, fromHeight);
        this.toX = toX;
        this.toY = toY;
        this.toWidth = Math.max(1, toWidth);
        this.toHeight = Math.max(1, toHeight);
        this.fromRotation = fromRotation;
        this.toRotation = toRotation;
        this.startMillis = startMillis;
        this.durationMillis = Math.max(1L, durationMillis);
    }

    /**
     * Flight of an unidentified card (another player's draw). The renderer
     * shows a card back; {@link #instanceId} is a shared placeholder so
     * tracking never collides with a real card.
     */
    public static CardFlyAnimation anonymous(boolean faceUp,
                                             int fromX, int fromY, int fromWidth, int fromHeight,
                                             int toX, int toY, int toWidth, int toHeight,
                                             int fromRotation, int toRotation,
                                             long startMillis, long durationMillis)
    {
        return new CardFlyAnimation(null, ANONYMOUS,
                fromX, fromY, fromWidth, fromHeight,
                toX, toY, toWidth, toHeight,
                fromRotation, toRotation, faceUp,
                startMillis, durationMillis);
    }

    public UUID instanceId()
    {
        return this.instanceId;
    }

    @Nullable
    public CardInstance card()
    {
        return this.card;
    }

    public boolean faceUp()
    {
        return this.faceUp;
    }

    public boolean isDone(long nowMillis)
    {
        return nowMillis - this.startMillis >= this.durationMillis;
    }

    public float progress(long nowMillis)
    {
        float t = (nowMillis - this.startMillis) / (float) this.durationMillis;
        return Math.max(0.0F, Math.min(1.0F, t));
    }

    /** Smoothed 0..1 progress (ease-out cubic). */
    public float easedProgress(long nowMillis)
    {
        float t = this.progress(nowMillis);
        float inv = 1.0F - t;
        return 1.0F - inv * inv * inv;
    }

    /** {@code {x, y, width, height}} of the card's AABB at {@code nowMillis}. */
    public int[] rectAt(long nowMillis)
    {
        float t = this.easedProgress(nowMillis);
        int x = Math.round(this.fromX + (this.toX - this.fromX) * t);
        int y = Math.round(this.fromY + (this.toY - this.fromY) * t);
        int width = Math.max(1, Math.round(this.fromWidth + (this.toWidth - this.fromWidth) * t));
        int height = Math.max(1, Math.round(this.fromHeight + (this.toHeight - this.fromHeight) * t));
        int travel = (int) Math.hypot(this.toX - this.fromX, this.toY - this.fromY);
        float arc = Math.min(36.0F, Math.max(12.0F, travel * 0.12F));
        y -= Math.round(arc * 4.0F * t * (1.0F - t));
        return new int[] {x, y, width, height};
    }

    /**
     * Display rotation at {@code nowMillis}: shortest-path interpolation so a
     * 270°→0° turn does not spin the long way round.
     */
    public int rotationAt(long nowMillis)
    {
        float t = this.easedProgress(nowMillis);
        int delta = Math.floorMod(this.toRotation - this.fromRotation + 180, 360) - 180;
        return Math.floorMod(Math.round(this.fromRotation + delta * t), 360);
    }
}
