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
 * discard's origin. The most recent card that entered a free/grid table zone
 * is remembered as {@link #lastTableCardId()} so the screen can frame it.</p>
 *
 * <p>A placement carries the {@link CardInstance} the screen drew whenever the
 * view knows it, so a flight can render the card's own face or back instead of
 * a stand-in — and, because the flight then carries the card's real identity,
 * the strip hands the card over to the flight for its duration. Only cards the
 * view genuinely cannot identify (another player's hand) fly anonymously.</p>
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

    /**
     * One card's resolved screen placement for the current frame. {@code card}
     * is the instance the screen painted at that rect, or {@code null} when the
     * view genuinely does not know it (another player's hidden card). Knowing
     * it is what lets a flight show the card's own face or back and, for a face
     * the owner may read, let the strip hide the card while it flies.
     */
    public record Placement(Kind kind, int x, int y, int width, int height, int rotationDeg,
                            @Nullable BlockPos section, @Nullable CardInstance card)
    {
    }

    private static final long DRAW_DURATION_MS = 320L;
    private static final long PLAY_DURATION_MS = 360L;
    /** Two placements closer than this are the same spot (no flight). */
    private static final int MOVE_EPSILON_PX = 2;
    /** Stagger between consecutive remote-draw flights in one multi-card draw. */
    private static final long MULTI_DRAW_STAGGER_MS = 70L;
    private static final int MULTI_DRAW_MAX = 4;

    private final List<CardFlyAnimation> flights = new ArrayList<>();
    private Map<UUID, Placement> previousCards = Map.of();
    private Map<BlockPos, Integer> previousHandCounts = Map.of();
    /** Destinations of in-flight local plays, kept in the baseline until they land. */
    private final Map<UUID, Placement> pendingLocal = new HashMap<>();
    /** Instance the viewer currently holds; its return is a reflow, never a draw. */
    @Nullable
    private UUID heldCardId;
    /** False until the first observe seeds baselines; avoids a join-time storm. */
    private boolean primed;
    /** Instance of the newest card that landed in a PLACED zone, if any. */
    @Nullable
    private UUID lastTableCardId;

    public List<CardFlyAnimation> flights()
    {
        return this.flights;
    }

    /** Newest card that entered the table surface / a free or grid zone. */
    @Nullable
    public UUID lastTableCardId()
    {
        return this.lastTableCardId;
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
        this.pendingLocal.clear();
        this.heldCardId = null;
        this.primed = false;
        this.lastTableCardId = null;
    }

    /**
     * Marks the card the viewer is currently holding, or {@code null} once the
     * mouse is empty. A held card is filtered out of the frame data on purpose
     * — the drag preview owns its presence — so the tracker must neither read
     * its disappearance as a removal nor its return as a fresh draw: it keeps
     * the card's last known slot until the mouse lets go, so the return
     * compares against that slot. Dropping it back where it came from then
     * animates nothing, and re-slotting it inside the own strip is a reflow,
     * which does not fly either.
     */
    public void holdCard(@Nullable UUID instanceId)
    {
        this.heldCardId = instanceId;
    }

    /**
     * Re-seeds the baseline without cancelling in-flight animations. Used when
     * the window or playfield resizes: every card's pixel rect would otherwise
     * "move" and spawn a meaningless fly storm.
     */
    public void reprime(Map<UUID, Placement> current, Map<BlockPos, Integer> handCounts)
    {
        this.previousCards = Map.copyOf(current);
        this.previousHandCounts = Map.copyOf(handCounts);
        this.primed = true;
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
     * toward the drop target the moment the mouse button comes up. The
     * destination is seeded into the previous map so the server's landing
     * sync does not spawn a second flight.
     *
     * @param card identified card, or {@code null} for a face-down ghost
     * @param kind placement kind the destination represents
     */
    public void spawnLocalPlay(@Nullable CardInstance card, UUID instanceId, Kind kind,
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
        Placement dest = new Placement(kind, toX, toY, toWidth, toHeight, toRotation, null, card);
        this.pendingLocal.put(instanceId, dest);
        Map<UUID, Placement> next = new HashMap<>(this.previousCards);
        next.put(instanceId, dest);
        this.previousCards = Map.copyOf(next);
        if (kind == Kind.PLACED)
        {
            this.lastTableCardId = instanceId;
        }
    }

    /**
     * Compares this frame's placements against the previous one and spawns
     * flights for every card that moved, appeared in a hand (draw), or appeared
     * on the table (discard from a seat whose hand count fell). Also covers
     * remote draws: a public hand count that rose with no new own-hand card
     * still flies a card back from the stock pile to that seat's hand fan.
     *
     * @param current            every visible card's placement this frame
     * @param handCounts         public hand count per section (client-synced)
     * @param seatPlates         screen AABB of each seat plate, by section pos
     * @param seatHands          screen AABB of each remote seat's hand fan
     * @param preferredDrawStack pile AABB to use as the draw origin when a
     *                           card appears in a hand with no prior position
     */
    public void observe(Map<UUID, Placement> current, Map<BlockPos, Integer> handCounts,
                        Map<BlockPos, int[]> seatPlates, Map<BlockPos, int[]> seatHands,
                        @Nullable int[] preferredDrawStack, long nowMillis)
    {
        if (!this.primed)
        {
            this.reprime(current, handCounts);
            return;
        }

        Map<UUID, Placement> previous = this.previousCards;
        Map<BlockPos, Integer> countUp = new HashMap<>();
        Map<BlockPos, Integer> countDown = new HashMap<>();
        diffHandCounts(previousHandCounts, handCounts, countUp, countDown);
        @Nullable BlockPos playOrigin = seatWithLargestDrop(countDown, seatPlates);

        spawnRemoteDraws(countUp, seatHands, preferredDrawStack, nowMillis);

        for (Map.Entry<UUID, Placement> entry : current.entrySet())
        {
            UUID id = entry.getKey();
            Placement now = entry.getValue();
            if (this.isFlying(id) || id.equals(this.heldCardId))
            {
                continue; // already animating or held by the viewer; prev is refreshed below
            }
            Placement was = previous.get(id);
            if (was == null)
            {
                this.spawnAppear(id, now, seatPlates, preferredDrawStack, playOrigin, nowMillis);
            }
            else if (moved(was, now))
            {
                this.spawnMove(id, was, now, nowMillis);
            }
        }

        Map<UUID, Placement> baseline = new HashMap<>(current);
        if (this.heldCardId != null)
        {
            // The viewer holds this card, so it is absent from the frame data
            // by design; carry its last known slot over, or letting go would
            // read as the card appearing out of nowhere.
            Placement held = previous.get(this.heldCardId);
            if (held != null)
            {
                baseline.put(this.heldCardId, held);
            }
        }
        for (Iterator<Map.Entry<UUID, Placement>> it = this.pendingLocal.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<UUID, Placement> pending = it.next();
            if (this.isFlying(pending.getKey()))
            {
                baseline.put(pending.getKey(), pending.getValue());
            }
            else
            {
                it.remove(); // landed; current owns the card from now on
            }
        }

        this.previousCards = Map.copyOf(baseline);
        this.previousHandCounts = Map.copyOf(handCounts);
    }

    /**
     * Remote (or batch) draws: for every seat whose public count rose, fly
     * face-down cards from the stock pile into that seat's hand fan. Staggered
     * so a multi-card draw reads as a short sequence, not a burst.
     */
    private void spawnRemoteDraws(Map<BlockPos, Integer> countUp, Map<BlockPos, int[]> seatHands,
                                  @Nullable int[] preferredDrawStack, long nowMillis)
    {
        if (preferredDrawStack == null)
        {
            return;
        }
        for (Map.Entry<BlockPos, Integer> entry : countUp.entrySet())
        {
            int[] dest = seatHands.get(entry.getKey());
            if (dest == null)
            {
                continue;
            }
            int amount = Math.min(entry.getValue(), MULTI_DRAW_MAX);
            for (int i = 0; i < amount; i++)
            {
                this.flights.add(CardFlyAnimation.anonymous(false,
                        preferredDrawStack[0], preferredDrawStack[1],
                        preferredDrawStack[2], preferredDrawStack[3],
                        dest[0], dest[1], dest[2], dest[3],
                        0, 0,
                        nowMillis + i * MULTI_DRAW_STAGGER_MS, DRAW_DURATION_MS));
            }
        }
    }

    private void spawnAppear(UUID id, Placement now, Map<BlockPos, int[]> seatPlates,
                             @Nullable int[] preferredDrawStack,
                             @Nullable BlockPos playOrigin, long nowMillis)
    {
        if (now.kind() == Kind.HAND)
        {
            if (preferredDrawStack == null)
            {
                return;
            }
            // A draw into the viewer's own hand: the hand packet reaches its
            // owner alone, so this client already holds the card and can show
            // its face the whole way in. Flying it behind that real identity is
            // what also lets the strip hand the card over to the flight, so the
            // card arrives once instead of sitting in the fan while a second
            // copy slides towards it.
            this.spawn(id, now, flight(now.card(), id,
                    preferredDrawStack[0], preferredDrawStack[1],
                    preferredDrawStack[2], preferredDrawStack[3],
                    now.x(), now.y(), now.width(), now.height(),
                    0, now.rotationDeg(), true,
                    nowMillis, DRAW_DURATION_MS));
            return;
        }
        if (now.kind() != Kind.PLACED)
        {
            return;
        }
        this.lastTableCardId = id;
        if (playOrigin == null)
        {
            return; // no public seat attribution — just highlight the landing
        }
        int[] origin = seatPlates.get(playOrigin);
        if (origin == null)
        {
            return;
        }
        // A discard is public: it flies in with the face the table already
        // settled on — a card played face down keeps its back.
        this.spawn(id, now, flight(now.card(), id,
                origin[0], origin[1], origin[2], origin[3],
                now.x(), now.y(), now.width(), now.height(),
                0, now.rotationDeg(), faceUpOf(now),
                nowMillis, PLAY_DURATION_MS));
    }

    private void spawnMove(UUID id, Placement was, Placement now, long nowMillis)
    {
        // Reflow inside the owner's own strip — a draw or play widening the
        // fan, a wheel scroll, a manual sort — is a layout change, not a card
        // move. The strip already paints those cards face up at their live
        // positions, and a flight would both hide the face behind a card back
        // and make the fan lag the cursor.
        if (was.kind() == Kind.HAND && now.kind() == Kind.HAND)
        {
            return;
        }
        if (now.kind() == Kind.PLACED && was.kind() != Kind.PLACED)
        {
            this.lastTableCardId = id;
        }
        // In-hand ↔ table is a draw/play even when both ends are known.
        boolean draw = was.kind() != Kind.HAND && now.kind() == Kind.HAND;
        long duration = draw ? DRAW_DURATION_MS : PLAY_DURATION_MS;
        // Hand cards are always face-readable to their owner; leaving the hand
        // is exactly when applyPlayOrientation settles the public face.
        boolean faceUp = now.kind() == Kind.HAND || faceUpOf(now);
        this.spawn(id, now, flight(now.card(), id,
                was.x(), was.y(), was.width(), was.height(),
                now.x(), now.y(), now.width(), now.height(),
                was.rotationDeg(), now.rotationDeg(),
                faceUp, nowMillis, duration));
    }

    /**
     * Builds a flight carrying the placement's real card when the view knows
     * it, falling back to an anonymous back-only flight for a card the client
     * cannot identify (another player's hand).
     */
    private static CardFlyAnimation flight(@Nullable CardInstance card, UUID instanceId,
                                           int fromX, int fromY, int fromWidth, int fromHeight,
                                           int toX, int toY, int toWidth, int toHeight,
                                           int fromRotation, int toRotation, boolean faceUp,
                                           long startMillis, long durationMillis)
    {
        if (card != null)
        {
            return new CardFlyAnimation(card, instanceId,
                    fromX, fromY, fromWidth, fromHeight,
                    toX, toY, toWidth, toHeight,
                    fromRotation, toRotation, faceUp, startMillis, durationMillis);
        }
        return CardFlyAnimation.anonymous(faceUp,
                fromX, fromY, fromWidth, fromHeight,
                toX, toY, toWidth, toHeight,
                fromRotation, toRotation, startMillis, durationMillis);
    }

    /**
     * Adds a flight and, when it flies under a real card identity, parks its
     * destination for the length of the flight. The screen filters an
     * in-flight card out of the frame data (the flight owns its presence), so
     * without the parked destination the baseline would forget the card and
     * its landing would read as a brand-new appearance — a second flight
     * towards the spot the first one just reached.
     */
    private void spawn(UUID instanceId, Placement destination, CardFlyAnimation animation)
    {
        this.flights.add(animation);
        if (animation.card() != null)
        {
            this.pendingLocal.put(instanceId, destination);
        }
    }

    /**
     * Landing face of a placement: a card the view knows keeps the orientation
     * the table gave it, so a face-down card is never turned over by its own
     * flight. An unknown card falls back to "public", which only decides this
     * flag — the renderer paints a stand-in back for it either way.
     */
    private static boolean faceUpOf(Placement placement)
    {
        return placement.card() == null || placement.card().isFaceUp();
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
