package com.example.cardtable.client;

import com.example.cardtable.network.packet.CursorSyncPacket;
import net.minecraft.Util;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-only mirror of remote seated players' mouse cursors for the table
 * currently on screen. The server relays samples; this side only decides
 * which of them are still fresh enough to draw and what colour each UUID
 * wears. Nothing is persisted — clearing on screen close (or after a short
 * timeout) is enough to drop players who left, closed the screen, or
 * disconnected.
 */
public final class ClientCursorStore
{
    /** How long one sample stays visible without a fresh update. */
    private static final long CURSOR_LIFETIME_MS = 2_000L;

    /**
     * Fixed 8-colour palette chosen for contrast against the light quartz
     * board and against each other. Assignment is a pure function of the
     * player UUID, so a reconnect or seat change keeps the same colour.
     */
    private static final int[] PALETTE = {
            0xFFE6194B,
            0xFF3CB44B,
            0xFFFFE119,
            0xFF4363D8,
            0xFFF58231,
            0xFF911EB4,
            0xFF42D4F4,
            0xFFF032E6,
    };

    /** One drawable remote cursor after filtering and colouring. */
    public record CursorView(UUID playerId, String playerName, float normX, float normY, int color)
    {
    }

    private record Entry(UUID tableId, String playerName, float normX, float normY,
                         long updatedAtMs)
    {
    }

    private static final Map<UUID, Entry> CURSORS = new HashMap<>();

    private ClientCursorStore()
    {
    }

    /** Records one relayed sample. Ignored without a broadcast identity. */
    public static void update(CursorSyncPacket packet)
    {
        if (packet.playerId() == null || packet.tableId() == null)
        {
            return;
        }
        CURSORS.put(packet.playerId(), new Entry(packet.tableId(), packet.playerName(),
                packet.normX(), packet.normY(), Util.getMillis()));
    }

    /**
     * Live cursors for the viewed table, with expired entries dropped. The
     * caller passes the group's TableId read from its own synced group
     * state — the same identity source the card data uses — so nothing
     * packet-declared is ever matched against a locally resolved position.
     * Returns an empty list when the identity is unknown so a closed screen
     * cannot leak the previous session's cursors.
     */
    public static List<CursorView> activeCursors(@Nullable UUID tableId)
    {
        long now = Util.getMillis();
        CURSORS.entrySet().removeIf(entry -> now - entry.getValue().updatedAtMs() > CURSOR_LIFETIME_MS);
        if (tableId == null)
        {
            return List.of();
        }
        List<CursorView> active = new ArrayList<>();
        for (Map.Entry<UUID, Entry> entry : CURSORS.entrySet())
        {
            Entry sample = entry.getValue();
            if (!sample.tableId().equals(tableId))
            {
                continue;
            }
            UUID playerId = entry.getKey();
            active.add(new CursorView(playerId, sample.playerName(), sample.normX(), sample.normY(),
                    colorFor(playerId)));
        }
        return active;
    }

    /** Stable palette index for a player UUID; pure and side-effect free. */
    public static int colorFor(UUID playerId)
    {
        int hash = playerId.hashCode();
        hash ^= (hash >>> 16);
        return PALETTE[Math.floorMod(hash, PALETTE.length)];
    }

    /** Drops every mirrored cursor (screen closed / logout). */
    public static void clear()
    {
        CURSORS.clear();
    }
}
