package com.example.cardtable.client;

import com.example.cardtable.card.CardInstance;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side mirror of the local player's hands, one entry per seated
 * section. Contents arrive exclusively via the directed
 * {@code HandSyncPacket}; nothing else ever writes here, so a hand can only
 * exist on the client that owns it.
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
