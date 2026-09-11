package com.example.cardtable.client.anim;

import com.example.cardtable.card.CardInstance;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side diff of card locations between frames. When a card's on-screen
 * placement changes — a draw into a hand, a discard onto the table, a zone-to-
 * zone move — the tracker spawns a {@link CardFlyAnimation} between the old
 * and new geometry so the motion reads as "player seat ↔ target".
 *
 * <p>Placements are screen-space AABBs captured after the view rotation, so
 * the animation automatically follows whichever seat the viewer sits at.
 * Remote seats are attributed through the public hand-count sync: a seat whose
 * count rose is the draw's destination, a seat whose count fell is the
 * discard's origin.</p>
 */
public final class CardAnimationTracker
{
    /** Placement kind drives which seat/stack anchors an appear-move may use. */
    public enum Kind
    {
        /** Own hand strip. */
        HAND,
        /** A STACK pile (all cards share the pile AABB). */
        STACK,
        /** FREE/GRID placed card or the blank surface. */
        PLACED
    }

    /** One card's resolved screen placement for the current frame. */
    public record Placement(Kind kind, int x, int y, int width, int height, int rotationDeg,
                            @Nullable BlockPos section)
    {
    }

    private static final long DRAW_DURATION_MS = 320L;
    private static final long PLAY_DURATION_MS = 360L;
    /** Two placements closer than this are the same spot (no flight). */
    private static final int MOVE_EPSILON_PX = 2;

    private final List<CardFlyAnimation> flights = new ArrayList<>();
    private Map<UUID, Placement> previousCards = Map.of();
    private Map<BlockPos, Integer> previousHandCounts = Map.of();

    public List<CardFlyAnimation> flights()
    {
        return this.flights;
    }

    public boolean isFlying(UUID instanceId)
    {
        for (CardFlyAnimation flight : this.flights)
        {
            if (flight.instanceId().equals(instanceId))
            {
                return true;
            }
        }
        return false;
    }

    public void clear()
    {
        this.flights.clear();
        this.previousCards = Map.of();
        this.previousHandCounts = Map.of();
    }

    /** Drops finished flights; call once per frame before observe/render. */
    public void tick(long nowMillis)
    {
        Iterator<CardFlyAnimation> iterator = this.flights.iterator();
        while (iterator.hasNext())
        {
            if (iterator.next().isDone(nowMillis))
            {
                iterator.remove();
            }
        }
    }

    /**
     * Starts an immediate flight without waiting for the next sync — used when
     * the local player releases a hand card, so the card leaves the strip
     * toward the drop target the moment the mouse button comes up.
     *
     * @param card identified card, or {@code null} for a face-down ghost
     */
    public void spawnLocalPlay(@Nullable CardInstance card, UUID instanceId,
                               int fromX, int fromY, int fromWidth, int fromHeight,
                               int toX, int toY, int toWidth, int toHeight,
                               int fromRotation, int toRotation, boolean faceUp, long nowMillis)
    {
        if (this.isFlying(instanceId))
        {
            return;
        }
        this.flights.add(new CardFlyAnimation(card, instanceId,
                fromX, fromY, fromWidth, fromHeight,
                toX, toY, toWidth, toHeight,
                fromRotation, toRotation, faceUp, nowMillis, PLAY_DURATION_MS));
    }

    /**
     * Compares this frame's placements against the previous one and spawns
     * flights for every card that moved, appeared in a hand (draw), or appeared
     * on the table (discard from a seat whose hand count fell).
     *
     * @param current            every visible card's placement this frame
     * @param handCounts         public hand count per section (client-synced)
     * @param seatPlates         screen AABB of each seat plate, by section pos
     * @param preferredDrawStack pile AABB to use as the draw origin when a
     *                           card appears in a hand with no prior position
     */
    public void observe(Map<UUID, Placement> current, Map<BlockPos, Integer> handCounts,
                        Map<BlockPos, int[]> seatPlates,
                        @Nullable int[] preferredDrawStack, long nowMillis)
    {
        Map<UUID, Placement> previous = this.previousCards;
        Map<BlockPos, Integer> countUp = new HashMap<>();
        Map<BlockPos, Integer> countDown = new HashMap<>();
        diffHandCounts(previousHandCounts, handCounts, countUp, countDown);
        @Nullable BlockPos playOrigin = seatWithLargestDrop(countDown, seatPlates);

        for (Map.Entry<UUID, Placement> entry : current.entrySet())
        {
            UUID id = entry.getKey();
            Placement now = entry.getValue();
            if (this.isFlying(id))
            {
                continue; // already animating; prev is refreshed below
            }
            Placement was = previous.get(id);
            if (was == null)
            {
                this.spawnAppear(now, seatPlates, preferredDrawStack, playOrigin, nowMillis);
            }
            else if (moved(was, now))
            {
                this.spawnMove(was, now, nowMillis);
            }
        }

        this.previousCards = Map.copyOf(current);
        this.previousHandCounts = Map.copyOf(handCounts);
    }

    private void spawnAppear(Placement now, Map<BlockPos, int[]> seatPlates,
                             @Nullable int[] preferredDrawStack,
                             @Nullable BlockPos playOrigin, long nowMillis)
    {
        if (now.kind() == Kind.HAND)
        {
            if (preferredDrawStack == null)
            {
                return;
            }
            // A draw is public only as "something left the pile"; the face
            // stays hidden until the card lands in the owner's strip.
            this.flights.add(CardFlyAnimation.anonymous(false,
                    preferredDrawStack[0], preferredDrawStack[1],
                    preferredDrawStack[2], preferredDrawStack[3],
                    now.x(), now.y(), now.width(), now.height(),
                    0, now.rotationDeg(),
                    nowMillis, DRAW_DURATION_MS));
            return;
        }
        if (now.kind() != Kind.PLACED || playOrigin == null)
        {
            return;
        }
        int[] origin = seatPlates.get(playOrigin);
        if (origin == null)
        {
            return;
        }
        // A discard is public: show the face the table already settled on.
        this.flights.add(CardFlyAnimation.anonymous(true,
                origin[0], origin[1], origin[2], origin[3],
                now.x(), now.y(), now.width(), now.height(),
                0, now.rotationDeg(),
                nowMillis, PLAY_DURATION_MS));
    }

    private void spawnMove(Placement was, Placement now, long nowMillis)
    {
        // In-hand ↔ table is a draw/play even when both ends are known.
        boolean draw = was.kind() != Kind.HAND && now.kind() == Kind.HAND;
        long duration = draw ? DRAW_DURATION_MS : PLAY_DURATION_MS;
        // Hand cards are always face-readable to their owner; leaving the hand
        // is exactly when applyPlayOrientation settles the public face.
        boolean faceUp = now.kind() != Kind.HAND || was.kind() == Kind.HAND;
        this.flights.add(CardFlyAnimation.anonymous(faceUp,
                was.x(), was.y(), was.width(), was.height(),
                now.x(), now.y(), now.width(), now.height(),
                was.rotationDeg(), now.rotationDeg(),
                nowMillis, duration));
    }

    private static boolean moved(Placement was, Placement now)
    {
        if (was.kind() != now.kind())
        {
            return true;
        }
        return Math.abs(was.x() - now.x()) > MOVE_EPSILON_PX
                || Math.abs(was.y() - now.y()) > MOVE_EPSILON_PX
                || Math.abs(was.width() - now.width()) > MOVE_EPSILON_PX
                || Math.abs(was.height() - now.height()) > MOVE_EPSILON_PX;
    }

    private static void diffHandCounts(Map<BlockPos, Integer> previous, Map<BlockPos, Integer> current,
                                       Map<BlockPos, Integer> up, Map<BlockPos, Integer> down)
    {
        for (Map.Entry<BlockPos, Integer> entry : current.entrySet())
        {
            int was = previous.getOrDefault(entry.getKey(), entry.getValue());
            int now = entry.getValue();
            if (now > was)
            {
                up.put(entry.getKey(), now - was);
            }
            else if (now < was)
            {
                down.put(entry.getKey(), was - now);
            }
        }
    }

    @Nullable
    private static BlockPos seatWithLargestDrop(Map<BlockPos, Integer> down, Map<BlockPos, int[]> seatPlates)
    {
        Map.Entry<BlockPos, Integer> best = null;
        for (Map.Entry<BlockPos, Integer> entry : down.entrySet())
        {
            if (seatPlates.containsKey(entry.getKey())
                    && (best == null || entry.getValue() > best.getValue()))
            {
                best = entry;
            }
        }
        return best == null ? null : best.getKey();
    }
}
