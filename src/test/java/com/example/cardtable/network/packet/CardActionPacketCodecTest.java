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
 * <p>Since protocol 3 the zone is addressed by its open layout id; since
 * protocol 4 {@code Perform} replaces the old {@code Draw}/{@code Shuffle}
 * kinds; since protocol 5 the zone ref carries no seat position any more —
 * the blank surface is one group-level zone and the hand target is resolved
 * to the sender's own seat server-side; since protocol 6 {@code Move} carries
 * {@code playRotation} so a card leaving hand can land upright on a rotated
 * view.</p>
 */
class CardActionPacketCodecTest
{
    private static final BlockPos TABLE_POS = new BlockPos(12, 64, -7);
    private static final UUID CARD_ID = UUID.randomUUID();
    private static final ResourceLocation PACK_ZONE =
            new ResourceLocation("cardtable", "my_tcg/bench");
    private static final ResourceLocation ACTION_ID =
            new ResourceLocation("cardtable", "my_tcg/draw");

    @Test
    void moveToReservedFreeZoneRoundTrips()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(TableLayoutDefinition.ZONE_FREE), new Vec2(0.25F, 0.75F), false, 0));
    }

    @Test
    void moveToHandRoundTrips()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(TableLayoutDefinition.ZONE_HAND), null, false, 0));
    }

    @Test
    void moveToPackZoneRoundTrips()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(PACK_ZONE), new Vec2(0.1F, 0.9F), false, 270));
    }

    @Test
    void faceDownPlayRequestSurvivesTheWire()
    {
        assertMoveRoundTrip(new CardActionPacket.Action.Move(CARD_ID,
                new ZoneRef(PACK_ZONE), new Vec2(0.5F, 0.5F), true, 90));
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
    void performWithoutInstanceRoundTrips()
    {
        assertActionRoundTrip(new CardActionPacket.Action.Perform(ACTION_ID, null));
    }

    @Test
    void performWithInstanceRoundTrips()
    {
        assertActionRoundTrip(new CardActionPacket.Action.Perform(ACTION_ID, CARD_ID));
    }

    @Test
    void reorderHandRoundTrips()
    {
        assertActionRoundTrip(new CardActionPacket.Action.ReorderHand(CARD_ID, 3));
    }

    @Test
    void reorderHandAtZeroRoundTrips()
    {
        assertActionRoundTrip(new CardActionPacket.Action.ReorderHand(CARD_ID, 0));
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
        assertVec2Equals(move.surfacePos(), decodedMove.surfacePos());
        assertEquals(move.faceDown(), decodedMove.faceDown(),
                "the face-down play intent must survive the round trip");
        assertEquals(move.playRotation(), decodedMove.playRotation(),
                "the play rotation must survive the round trip");
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
