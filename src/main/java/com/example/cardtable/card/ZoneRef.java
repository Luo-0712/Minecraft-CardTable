package com.example.cardtable.card;

import net.minecraft.resources.ResourceLocation;

/**
 * Locates a card zone by layout id. The id is the open, content-driven
 * address (a pack-relative id like {@code cardtable:my_tcg/bench}) or one of
 * the reserved ids: {@code cardtable:free} names the blank table surface and
 * {@code cardtable:hand} names the actor's own hidden hand. There is no seat
 * coordinate any more — the surface is a single group-level instance and a
 * hand is always resolved to the acting player's own seat server-side.
 */
public record ZoneRef(ResourceLocation zoneId)
{
    public ZoneRef
    {
        java.util.Objects.requireNonNull(zoneId, "zoneId");
    }

    @Override
    public String toString()
    {
        return "ZoneRef[" + this.zoneId + "]";
    }
}
