package com.example.cardtable.client;

import com.example.cardtable.network.packet.TableNoticePacket;
import net.minecraft.Util;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Client-only store for the latest shared table toast. The server decides
 * when a notice exists; this side only decides how long it stays on screen
 * and how it is worded. Cleared automatically after a short lifetime so a
 * closed screen cannot leak the previous message into the next opening.
 */
public final class ClientTableNotices
{
    /** How long one notice stays visible. */
    private static final long NOTICE_LIFETIME_MS = 2_800L;

    @Nullable
    private static UUID tableId;
    private static Component message = Component.empty();
    private static long expireAtMs;

    private ClientTableNotices()
    {
    }

    /** Shows a shuffle toast for the given table; replaces any active notice. */
    public static void showShuffle(TableNoticePacket packet)
    {
        boolean hasZone = !packet.zoneLabel().isEmpty();
        show(packet.tableId(), hasZone
                ? Component.translatable("gui.cardtable.notice.shuffle", packet.actorName(), packet.zoneLabel())
                : Component.translatable("gui.cardtable.notice.shuffle_generic", packet.actorName()));
    }

    private static void show(@Nullable UUID tableId, Component message)
    {
        ClientTableNotices.tableId = tableId;
        ClientTableNotices.message = message;
        ClientTableNotices.expireAtMs = Util.getMillis() + NOTICE_LIFETIME_MS;
    }

    /**
     * The active notice for the table the player is currently viewing, or an
     * empty component. The caller passes the group's TableId read from its
     * own synced group state — the same identity source the card data uses.
     * Returns empty when expired or when the screen points at a different
     * table than the one the event came from.
     */
    public static Component activeNotice(@Nullable UUID tableId)
    {
        if (Util.getMillis() > expireAtMs || message.getString().isEmpty())
        {
            return Component.empty();
        }
        if (tableId == null || !tableId.equals(ClientTableNotices.tableId))
        {
            return Component.empty();
        }
        return message;
    }

    /** Drops any active notice (screen closed / logout). */
    public static void clear()
    {
        tableId = null;
        message = Component.empty();
        expireAtMs = 0L;
    }
}
