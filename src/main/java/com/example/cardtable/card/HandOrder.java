package com.example.cardtable.card;

import java.util.List;
import java.util.UUID;

/**
 * Pure hand-order helpers shared by the server section state and the
 * client-side hand mirror. Index semantics: {@code toIndex} is the insert
 * position in the list <em>after</em> the dragged card has been removed —
 * the same coordinate the hand strip's gap picker produces.
 */
public final class HandOrder
{
    private HandOrder()
    {
    }

    /**
     * Moves {@code instanceId} to {@code toIndex} inside {@code hand}.
     * Out-of-range targets clamp into the list; a missing card or a
     * no-op reorder returns {@code false} so callers can skip a version bump.
     */
    public static boolean reorder(List<CardInstance> hand, UUID instanceId, int toIndex)
    {
        int from = indexOf(hand, instanceId);
        if (from < 0)
        {
            return false;
        }
        CardInstance card = hand.remove(from);
        int at = Math.max(0, Math.min(toIndex, hand.size()));
        if (at == from)
        {
            // Restoring the original slot: put it back and report no change.
            hand.add(from, card);
            return false;
        }
        hand.add(at, card);
        return true;
    }

    /** Index of {@code instanceId} in {@code hand}, or -1 when absent. */
    public static int indexOf(List<CardInstance> hand, UUID instanceId)
    {
        for (int index = 0; index < hand.size(); index++)
        {
            if (hand.get(index).instanceId().equals(instanceId))
            {
                return index;
            }
        }
        return -1;
    }
}
