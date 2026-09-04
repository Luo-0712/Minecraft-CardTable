package com.example.cardtable.network.packet;

import com.example.cardtable.api.TableLayoutDefinition;
import com.example.cardtable.card.ZoneRef;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
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
 * <p>Since protocol 3 the zone is addressed by its open layout id plus an
 * optional seat section; unknown ids are rejected server-side and never
 * reach the state.</p>
 */
class CardActionPacketCodecTest
{
    private static final BlockPos TABLE_POS = new BlockPos(12, 64, -7);
    private static final BlockPos SECTION_POS = new BlockPos(12, 64, -8);
    private static final UUID CARD_ID = UUID.randomUUID();
    private static final ResourceLocation PACK_ZONE =
            new ResourceLocation("cardtable", "my_tcg/bench");

    @Test
    void moveToDrawPileRoundTrips()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(TableLayoutDefinition.ZONE_DRAW_PILE, null), null));
    }

    @Test
    void moveToDiscardPileRoundTrips()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(TableLayoutDefinition.ZONE_DISCARD_PILE, null), null));
    }

    @Test
    void moveToFreeZoneRoundTrips()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(TableLayoutDefinition.ZONE_FREE, SECTION_POS), new Vec2(0.25F, 0.75F)));
    }

    @Test
    void moveToHandRoundTrips()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(TableLayoutDefinition.ZONE_HAND, SECTION_POS), null));
    }

    @Test
    void moveToPackZoneWithSectionRoundTrips()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(PACK_ZONE, SECTION_POS), new Vec2(0.1F, 0.9F)));
    }

    @Test
    void moveToPackZoneWithoutSectionRoundTrips()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(PACK_ZONE, null), new Vec2(0.5F, 0.5F)));
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
                new ZoneRef(TableLayoutDefinition.ZONE_DRAW_PILE, null)));
    }

    @Test
    void shuffleToPackStackZoneRoundTrips()
    {
        assertActionRoundTrip(new CardActionPacket.Action.Shuffle(new ZoneRef(PACK_ZONE, SECTION_POS)));
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
                "a Move aimed at " + move.target().zoneId() + " must leave no unconsumed byte");
        assertEquals(TABLE_POS, decoded.tablePosition());
        assertTrue(decoded.action() instanceof CardActionPacket.Action.Move,
                "the action kind must survive the round trip");

        CardActionPacket.Action.Move decodedMove = (CardActionPacket.Action.Move) decoded.action();
        assertEquals(move.instanceId(), decodedMove.instanceId());
        assertEquals(move.target().zoneId(), decodedMove.target().zoneId());
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
