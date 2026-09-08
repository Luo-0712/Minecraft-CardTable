package com.example.cardtable.card;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Locates a card zone by layout id plus optional seat section. The id is the
 * open, content-driven address (a pack-relative id like
 * {@code cardtable:my_tcg/bench}, or the reserved {@code cardtable:free}
 * surface); the section position selects the seat instance for
 * PER_SEAT zones and must be {@code null} for SHARED zones. The server
 * re-resolves and re-validates both parts authoritatively.
 */
public record ZoneRef(ResourceLocation zoneId, @Nullable BlockPos sectionPos)
{
    public ZoneRef
    {
        java.util.Objects.requireNonNull(zoneId, "zoneId");
    }

    @Override
    public String toString()
    {
        return "ZoneRef[" + this.zoneId + (this.sectionPos != null ? " @" + this.sectionPos.toShortString() : "") + "]";
    }
}
