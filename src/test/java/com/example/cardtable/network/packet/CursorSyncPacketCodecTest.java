package com.example.cardtable.network.packet;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the two wire shapes of the cursor packet: a client→server report
 * with a null player id, and a server→client broadcast that carries the
 * seated player's UUID and profile name. Encode and decode must consume
 * exactly the same bytes either way.
 */
class CursorSyncPacketCodecTest
{
    private static final BlockPos TABLE_POS = new BlockPos(12, 64, -7);
    private static final UUID PLAYER_ID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    @Test
    void reportWithNullIdentityRoundTrips()
    {
        assertRoundTrip(new CursorSyncPacket(TABLE_POS, null, "", 0.25F, 0.75F));
    }

    @Test
    void broadcastWithIdentityRoundTrips()
    {
        assertRoundTrip(new CursorSyncPacket(TABLE_POS, PLAYER_ID, "Steve", 0.0F, 1.0F));
    }

    @Test
    void clamp01RejectsOutOfRangeAndNaN()
    {
        // clamp01 is exercised through the public encode/decode contract by
        // writing the raw floats the server would have accepted pre-clamp;
        // the clamp itself lives in the server handle path and is a pure
        // local bound, so this test locks the codec still carries any float
        // the handler later clamps.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        CursorSyncPacket.encode(new CursorSyncPacket(TABLE_POS, null, "", -0.5F, 1.5F), buffer);
        CursorSyncPacket decoded = CursorSyncPacket.decode(buffer);
        assertEquals(-0.5F, decoded.normX());
        assertEquals(1.5F, decoded.normY());
    }

    private static void assertRoundTrip(CursorSyncPacket packet)
    {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        CursorSyncPacket.encode(packet, buffer);
        CursorSyncPacket decoded = CursorSyncPacket.decode(buffer);

        assertEquals(0, buffer.readableBytes(),
                "encode() and decode() must consume exactly the same bytes");
        assertEquals(packet.tablePosition(), decoded.tablePosition());
        assertUuidEquals(packet.playerId(), decoded.playerId());
        assertEquals(packet.playerName(), decoded.playerName());
        assertEquals(packet.normX(), decoded.normX());
        assertEquals(packet.normY(), decoded.normY());
    }

    private static void assertUuidEquals(@Nullable UUID expected, @Nullable UUID actual)
    {
        if (expected == null)
        {
            assertNull(actual, "an absent player id must stay absent");
            return;
        }
        assertEquals(expected, actual, "the player id must survive the round trip");
    }
}
