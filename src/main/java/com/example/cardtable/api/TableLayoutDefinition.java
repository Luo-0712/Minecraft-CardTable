package com.example.cardtable.api;

import com.example.cardtable.CardTableMod;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of one table layout: the set of zones a table shows
 * while a deck of the owning {@link CardSetDefinition} is loaded. A layout
 * only describes <em>where zones are</em>; it never carries game rules.
 *
 * <p>Four reserved built-in zone ids ({@code cardtable:draw_pile},
 * {@code cardtable:discard_pile}, {@code cardtable:hand},
 * {@code cardtable:free}) keep the core behaviors working in every layout.
 * {@link #normalized()} applies the implicit-inheritance rule so every
 * layout always ends up with the built-in zones: undeclared built-ins are
 * filled from {@link #defaultLayout()}, and full-id declarations may override
 * rect/capacity/label of {@code draw_pile}/{@code discard_pile}/{@code free}
 * but never their scope/kind. The function is pure so the server and the
 * client can normalize the same layout id independently and agree.</p>
 */
public final class TableLayoutDefinition
{
    /** Reserved hand zone: a per-seat stack rendered as the fixed hand strip. */
    public static final ResourceLocation ZONE_HAND = new ResourceLocation(CardTableMod.MODID, "hand");
    /** Reserved draw pile: the fixed target of the Draw action. */
    public static final ResourceLocation ZONE_DRAW_PILE = new ResourceLocation(CardTableMod.MODID, "draw_pile");
    /** Reserved discard pile: face-up public record. */
    public static final ResourceLocation ZONE_DISCARD_PILE = new ResourceLocation(CardTableMod.MODID, "discard_pile");
    /** Reserved free placement zone, the descendant of the old SURFACE. */
    public static final ResourceLocation ZONE_FREE = new ResourceLocation(CardTableMod.MODID, "free");

    /** The fallback layout every undeclared-layout set runs; replicates current placement. */
    public static final ResourceLocation DEFAULT_LAYOUT_ID = new ResourceLocation(CardTableMod.MODID, "default");

    private final ResourceLocation id;
    @Nullable
    private final Component displayName;
    private final List<ZoneDefinition> zones;
    @Nullable
    private final Map<ResourceLocation, ZoneDefinition> zoneIndex;

    private TableLayoutDefinition(Builder builder)
    {
        this(builder.id, builder.displayName, builder.zones);
    }

    private TableLayoutDefinition(ResourceLocation id, @Nullable Component displayName, List<ZoneDefinition> zones)
    {
        this.id = id;
        this.displayName = displayName;
        this.zones = List.copyOf(zones);
        Map<ResourceLocation, ZoneDefinition> index = new LinkedHashMap<>();
        for (ZoneDefinition zone : this.zones)
        {
            index.put(zone.id(), zone);
        }
        this.zoneIndex = index.isEmpty() ? null : index;
    }

    public static Builder builder(ResourceLocation id)
    {
        return new Builder(id);
    }

    /** Unique layout id, e.g. {@code cardtable:default}. */
    public ResourceLocation id()
    {
        return this.id;
    }

    /** Optional display name; {@code null} means the client falls back to the set name. */
    @Nullable
    public Component displayName()
    {
        return this.displayName;
    }

    /** The declared zones in declaration order; may be empty before normalization. */
    public List<ZoneDefinition> zones()
    {
        return this.zones;
    }

    @Nullable
    public ZoneDefinition zone(@Nullable ResourceLocation id)
    {
        return id == null || this.zoneIndex == null ? null : this.zoneIndex.get(id);
    }

    @Override
    public String toString()
    {
        return "TableLayoutDefinition[" + this.id + " " + this.zones.size() + " zone(s)]";
    }

    /**
     * Implicit-inheritance normalization (pure function). Guarantees the
     * result contains all four built-in zones: undeclared built-ins are
     * filled from {@link #defaultLayout()}; full-id declarations of
     * {@code draw_pile}/{@code discard_pile}/{@code free} override
     * rect/capacity/label while scope/kind stay locked to the built-in
     * values (a mismatching declaration falls back to the built-in
     * placement); {@code hand} is a system zone and can never be declared.
     */
    public TableLayoutDefinition normalized()
    {
        Map<ResourceLocation, ZoneDefinition> result = new LinkedHashMap<>();
        // Declared zones first, in declaration order, minus unusable entries.
        for (ZoneDefinition zone : this.zones)
        {
            ResourceLocation zoneId = zone.id();
            if (ZONE_HAND.equals(zoneId))
            {
                continue; // system zone: declaring it is always invalid
            }
            if (isOverridableBuiltin(zoneId))
            {
                ZoneDefinition builtin = defaultLayout().zone(zoneId);
                if (zone.kind() != builtin.kind() || zone.scope() != builtin.scope())
                {
                    continue; // scope/kind tampering: skip, implicit default takes over
                }
                // Rebuild with the locked scope/kind so the override can never drift.
                result.put(zoneId, ZoneDefinition.builder(zoneId)
                        .kind(builtin.kind()).scope(builtin.scope())
                        .rect(zone.x(), zone.y(), zone.w(), zone.h())
                        .capacity(zone.capacity()).visibility(zone.visibility())
                        .label(zone.label()).build());
            }
            else
            {
                result.putIfAbsent(zoneId, zone);
            }
        }
        // Implicit inheritance: fill any missing built-in from the default layout.
        for (ZoneDefinition builtin : defaultLayout().zones())
        {
            result.putIfAbsent(builtin.id(), builtin);
        }
        return new TableLayoutDefinition(this.id, this.displayName, new ArrayList<>(result.values()));
    }

    private static boolean isOverridableBuiltin(ResourceLocation zoneId)
    {
        return ZONE_DRAW_PILE.equals(zoneId) || ZONE_DISCARD_PILE.equals(zoneId) || ZONE_FREE.equals(zoneId);
    }

    /**
     * The built-in default layout: draw pile top-left; discard pile covering
     * the whole playfield, so any drop outside the hand strip, the draw pile
     * or a seat's free area discards the card (the pile itself renders at the
     * playfield's centre); the per-seat free zone keeps the player's front
     * half of their cell (the bottom half, closest to where they sit).
     * Always available, never registered through content.
     */
    public static TableLayoutDefinition defaultLayout()
    {
        return new TableLayoutDefinition(DEFAULT_LAYOUT_ID, null, List.of(
                ZoneDefinition.builder(ZONE_DRAW_PILE)
                        .kind(ZoneDefinition.Kind.STACK).scope(ZoneDefinition.Scope.SHARED)
                        .rect(0.02F, 0.02F, 0.05F, 0.10F).build(),
                ZoneDefinition.builder(ZONE_DISCARD_PILE)
                        .kind(ZoneDefinition.Kind.STACK).scope(ZoneDefinition.Scope.SHARED)
                        .rect(0.0F, 0.0F, 1.0F, 1.0F).build(),
                ZoneDefinition.builder(ZONE_FREE)
                        .kind(ZoneDefinition.Kind.FREE).scope(ZoneDefinition.Scope.PER_SEAT)
                        .rect(0.0F, 0.5F, 1.0F, 0.5F).build()));
    }

    public static final class Builder
    {
        private final ResourceLocation id;
        @Nullable
        private Component displayName;
        private final List<ZoneDefinition> zones = new ArrayList<>();

        private Builder(ResourceLocation id)
        {
            this.id = Objects.requireNonNull(id, "id");
        }

        public Builder displayName(@Nullable Component displayName)
        {
            this.displayName = displayName;
            return this;
        }

        /** Appends one zone; duplicate zone ids and the reserved hand id are rejected. */
        public Builder zone(ZoneDefinition zone)
        {
            Objects.requireNonNull(zone, "zone");
            if (ZONE_HAND.equals(zone.id()))
            {
                throw new IllegalArgumentException("The reserved system zone " + ZONE_HAND
                        + " cannot be declared by a layout");
            }
            for (ZoneDefinition existing : this.zones)
            {
                if (existing.id().equals(zone.id()))
                {
                    throw new IllegalArgumentException("Duplicate zone id in layout " + this.id + ": " + zone.id());
                }
            }
            this.zones.add(zone);
            return this;
        }

        public TableLayoutDefinition build()
        {
            return new TableLayoutDefinition(this);
        }
    }
}
