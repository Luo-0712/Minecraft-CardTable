package com.example.cardtable.card;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

/**
 * Locates a card zone: a group-level pile (no section) or one table block's
 * surface/hand zone (with the block's position). The section position is only
 * meaningful for section zones; the server re-resolves it authoritatively.
 */
public record ZoneRef(Zone zone, @Nullable BlockPos sectionPos)
{
    /** The two shared piles plus the two per-section zones. */
    public enum Zone
    {
        DRAW_PILE,
        DISCARD_PILE,
        SURFACE,
        HAND;

        public boolean isGroupLevel()
        {
            return this == DRAW_PILE || this == DISCARD_PILE;
        }
    }
}
