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
 * Locks the two wire shapes of the cursor packet and the identity model
 * behind them: a client→server report carries only the menu block position
 * (the addressing CardActionPacket uses, so the server can resolve the
 * group), while a server→client broadcast carries the group's TableId —
 * the identity both ends read from their own synced group state, matching
 * how the card data is keyed — plus the seated player's UUID and profile
 * name. Encode and decode must consume exactly the same bytes either way.
 */
class CursorSyncPacketCodecTest
{
    private static final BlockPos MENU_POS = new BlockPos(12, 64, -7);
    private static final UUID TABLE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID PLAYER_ID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    @Test
    void reportCarriesNoIdentity()
    {
        assertRoundTrip(CursorSyncPacket.report(MENU_POS, 0.25F, 0.75F));
    }

    @Test
    void broadcastCarriesGroupTableIdAndIdentity()
    {
        assertRoundTrip(new CursorSyncPacket(null, TABLE_ID, PLAYER_ID, "Steve", 0.0F, 1.0F));
    }

    @Test
    void reportKeepsRawFloatsForServerSideClamp()
    {
        // The clamp lives in the server handle path; the codec must carry
        // any float the handler will later clamp unchanged.
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        CursorSyncPacket.encode(CursorSyncPacket.report(MENU_POS, -0.5F, 1.5F), buffer);
        CursorSyncPacket decoded = CursorSyncPacket.decode(buffer);
        assertEquals(-0.5F, decoded.normX());
        assertEquals(1.5F, decoded.normY());
    }

    @Test
    void broadcastDoesNotCarryAPosition()
    {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        CursorSyncPacket.encode(new CursorSyncPacket(null, TABLE_ID, PLAYER_ID, "Alex", 0.5F, 0.5F), buffer);
        assertNull(CursorSyncPacket.decode(buffer).reportPosition(),
                "a broadcast must carry the group identity, never a block position");
    }

    private static void assertRoundTrip(CursorSyncPacket packet)
    {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        CursorSyncPacket.encode(packet, buffer);
        CursorSyncPacket decoded = CursorSyncPacket.decode(buffer);

        assertEquals(0, buffer.readableBytes(),
                "encode() and decode() must consume exactly the same bytes");
        assertEquals(packet.reportPosition(), decoded.reportPosition());
        assertEquals(packet.tableId(), decoded.tableId(), "the group TableId must survive the round trip");
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
