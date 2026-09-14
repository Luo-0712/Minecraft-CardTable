package com.example.cardtable.client;

import com.example.cardtable.card.CardInstance;
import com.example.cardtable.card.HandOrder;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side mirror of the local player's hands, one entry per seated
 * section. Contents arrive exclusively via the directed
 * {@code HandSyncPacket}; nothing else ever writes here, so a hand can only
 * exist on the client that owns it. {@link #reorder} is the one local
 * prediction path: it applies a pending manual sort immediately and is
 * overwritten by the next authoritative snapshot.
 */
public final class ClientHandStore
{
    private static final Map<BlockPos, List<CardInstance>> HANDS = new HashMap<>();

    private ClientHandStore()
    {
    }

    /** Replaces the mirrored hand of one section (main thread only). */
    public static void update(BlockPos sectionPosition, List<CardInstance> cards)
    {
        if (cards.isEmpty())
        {
            HANDS.remove(sectionPosition);
        }
        else
        {
            HANDS.put(sectionPosition, List.copyOf(cards));
        }
    }

    /**
     * Optimistic local reorder while the server round-trip is in flight.
     * The next {@code HandSyncPacket} remains authoritative.
     */
    public static void reorder(BlockPos sectionPosition, UUID instanceId, int toIndex)
    {
        List<CardInstance> current = HANDS.get(sectionPosition);
        if (current == null || current.isEmpty())
        {
            return;
        }
        List<CardInstance> next = new ArrayList<>(current);
        if (!HandOrder.reorder(next, instanceId, toIndex))
        {
            return;
        }
        HANDS.put(sectionPosition, List.copyOf(next));
    }

    public static List<CardInstance> hand(BlockPos sectionPosition)
    {
        return HANDS.getOrDefault(sectionPosition, List.of());
    }

    /** Drops all mirrors (world unload / disconnect). */
    public static void clearAll()
    {
        HANDS.clear();
    }
}
