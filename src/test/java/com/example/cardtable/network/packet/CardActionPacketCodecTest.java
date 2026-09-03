package com.example.cardtable.network.packet;

import com.example.cardtable.card.ZoneRef;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec2;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the byte-level contract of the action packet: whatever {@code encode()}
 * writes must be consumed by {@code decode()} exactly once.
 *
 * <p>The two shared piles used to be the exception: {@code writeZone()} wrote a
 * section-position presence flag for them, while {@code readZone()} skipped it
 * via {@code isGroupLevel()}. Every Move aimed at a pile therefore left one
 * unconsumed byte in the buffer, and the following {@code surfacePos} was read
 * from the wrong position.</p>
 */
class CardActionPacketCodecTest
{
    private static final BlockPos TABLE_POS = new BlockPos(12, 64, -7);
    private static final BlockPos SECTION_POS = new BlockPos(12, 64, -8);
    private static final UUID CARD_ID = UUID.randomUUID();

    @Test
    void moveToDrawPileRoundTrips()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(ZoneRef.Zone.DRAW_PILE, null), null));
    }

    @Test
    void moveToDiscardPileRoundTrips()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(ZoneRef.Zone.DISCARD_PILE, null), null));
    }

    @Test
    void moveToSurfaceRoundTrips()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(ZoneRef.Zone.SURFACE, SECTION_POS), new Vec2(0.25F, 0.75F)));
    }

    @Test
    void moveToHandRoundTrips()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(ZoneRef.Zone.HAND, SECTION_POS), null));
    }

    @Test
    void flipRoundTrips()
    {
        assertActionRoundTrip(new CardActionPacket.Action.Flip(CARD_ID));
    }

    @Test
    void rotateRoundTrips()
    {
        assertActionRoundTrip(new CardActionPacket.Action.Rotate(CARD_ID));
    }

    @Test
    void drawRoundTrips()
    {
        assertActionRoundTrip(new CardActionPacket.Action.Draw(3));
    }

    @Test
    void shuffleRoundTrips()
    {
        assertActionRoundTrip(new CardActionPacket.Action.Shuffle(
                new ZoneRef(ZoneRef.Zone.DRAW_PILE, null)));
    }

    /** Non-Move actions are records with value equality, so a plain compare suffices. */
    private static void assertActionRoundTrip(CardActionPacket.Action action)
    {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        CardActionPacket.encode(new CardActionPacket(TABLE_POS, action), buffer);
        CardActionPacket decoded = CardActionPacket.decode(buffer);

        assertEquals(0, buffer.readableBytes(),
                "encode() and decode() must consume exactly the same bytes");
        assertEquals(TABLE_POS, decoded.tablePosition());
        assertEquals(action, decoded.action());
    }

    private static void assertMoveRoundTrip(CardActionPacket.Action.Move move)
    {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        CardActionPacket.encode(new CardActionPacket(TABLE_POS, move), buffer);
        CardActionPacket decoded = CardActionPacket.decode(buffer);

        assertEquals(0, buffer.readableBytes(),
                "a Move aimed at " + move.target().zone() + " must leave no unconsumed byte");
        assertEquals(TABLE_POS, decoded.tablePosition());
        assertTrue(decoded.action() instanceof CardActionPacket.Action.Move,
                "the action kind must survive the round trip");

        CardActionPacket.Action.Move decodedMove = (CardActionPacket.Action.Move) decoded.action();
        assertEquals(move.instanceId(), decodedMove.instanceId());
        assertEquals(move.target().zone(), decodedMove.target().zone());
        assertEquals(move.target().sectionPos(), decodedMove.target().sectionPos());
        assertVec2Equals(move.surfacePos(), decodedMove.surfacePos());
    }

    // Vec2 is compared component-wise: it is not guaranteed to implement equals().
    private static void assertVec2Equals(@Nullable Vec2 expected, @Nullable Vec2 actual)
    {
        if (expected == null)
        {
            assertNull(actual, "an absent surface position must stay absent");
            return;
        }
        assertNotNull(actual, "the surface position must survive the round trip");
        assertEquals(expected.x, actual.x, 0.0F);
        assertEquals(expected.y, actual.y, 0.0F);
    }
}
