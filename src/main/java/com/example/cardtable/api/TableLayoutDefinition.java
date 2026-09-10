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
 * Immutable description of one table layout: the zones a table shows and the
 * primitives it offers while a deck of the owning {@link CardSetDefinition}
 * is loaded. A layout never carries game rules.
 *
 * <p>Two structural conventions let the core stay free of any game concept:</p>
 * <ul>
 *   <li><b>{@code kind == STACK} is a pile.</b> Every STACK zone is a
 *       deck-owned pile the core instantiates one group-level container for.
 *       Zone ids are free-form; the core never interprets them.</li>
 *   <li><b>{@code initial} names the stock.</b> The layout maps a card-set
 *       selector to the STACK zone a newly inserted deck loads into. Today
 *       only the {@value #INITIAL_DEFAULT_KEY} default key exists; future
 *       multi-set layouts may add set-specific keys, so the mapping is an
 *       ordered map rather than a single field.</li>
 * </ul>
 *
 * <p>Only two reserved ids remain: {@code cardtable:hand} is a system zone
 * that can never be declared, and {@code cardtable:free} names the blank
 * table surface — a group-level free placement area the core owns directly,
 * so it is never part of a layout's zone list and declaring either id is
 * silently skipped. {@link #normalized()} is a pure function, so the server
 * and the client can normalize the same layout id independently and agree.</p>
 */
public final class TableLayoutDefinition
{
    /** Reserved hand zone: a per-seat stack rendered as the fixed hand strip. */
    public static final ResourceLocation ZONE_HAND = new ResourceLocation(CardTableMod.MODID, "hand");
    /**
     * Reserved surface id: the blank table itself, a group-level free
     * placement area owned by {@code TableGroupState}. Not a layout zone —
     * it always exists (deck or no deck) and is never declarable; the id
     * only serves as the {@code Move} target address for table drops.
     */
    public static final ResourceLocation ZONE_FREE = new ResourceLocation(CardTableMod.MODID, "free");

    /** Selector key of the default stock pile; today the only supported key. */
    public static final String INITIAL_DEFAULT_KEY = "*";

    private final ResourceLocation id;
    @Nullable
    private final Component displayName;
    private final List<ZoneDefinition> zones;
    private final List<TableActionDefinition> actions;
    private final Map<String, ResourceLocation> initialZones;
    @Nullable
    private final Map<ResourceLocation, ZoneDefinition> zoneIndex;

    private TableLayoutDefinition(Builder builder)
    {
        this(builder.id, builder.displayName, builder.zones, builder.actions, builder.initialZones);
    }

    private TableLayoutDefinition(ResourceLocation id, @Nullable Component displayName,
                                  List<ZoneDefinition> zones, List<TableActionDefinition> actions,
                                  Map<String, ResourceLocation> initialZones)
    {
        this.id = id;
        this.displayName = displayName;
        this.zones = List.copyOf(zones);
        this.actions = List.copyOf(actions);
        this.initialZones = Map.copyOf(initialZones);
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

    /** Unique layout id, e.g. {@code cardtable:standard/layout}. */
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

    /** The declared actions in declaration order; may be empty. */
    public List<TableActionDefinition> actions()
    {
        return this.actions;
    }

    /**
     * The stock mapping: card-set selector &rarr; pile zone id, in declaration
     * order. Content declares at least the {@value #INITIAL_DEFAULT_KEY} entry;
     * the map stays empty for layouts the pack never gave a stock.
     */
    public Map<String, ResourceLocation> initialZones()
    {
        return this.initialZones;
    }

    /**
     * The pile a newly inserted deck of {@code setId} loads into. Today only
     * the {@value #INITIAL_DEFAULT_KEY} selector exists, so the default entry
     * is the answer; a future multi-set layout may add set-specific keys that
     * take precedence over it. Returns {@code null} when the layout declares
     * no usable stock, in which case the deck insert must be rejected.
     */
    @Nullable
    public ZoneDefinition initialZoneFor(@Nullable ResourceLocation setId)
    {
        if (this.initialZones.isEmpty())
        {
            return null;
        }
        ResourceLocation target = setId != null ? this.initialZones.get(setId.toString()) : null;
        if (target == null)
        {
            target = this.initialZones.get(INITIAL_DEFAULT_KEY);
        }
        return target == null ? null : this.zone(target);
    }

    /** Every declared pile per the kind == STACK convention, in declaration order. */
    public List<ZoneDefinition> pileZones()
    {
        List<ZoneDefinition> piles = new ArrayList<>();
        for (ZoneDefinition zone : this.zones)
        {
            if (zone.kind() == ZoneDefinition.Kind.STACK)
            {
                piles.add(zone);
            }
        }
        return List.copyOf(piles);
    }

    @Nullable
    public ZoneDefinition zone(@Nullable ResourceLocation id)
    {
        return id == null || this.zoneIndex == null ? null : this.zoneIndex.get(id);
    }

    @Override
    public String toString()
    {
        return "TableLayoutDefinition[" + this.id + " " + this.zones.size() + " zone(s), "
                + this.actions.size() + " action(s)]";
    }

    /**
     * Normalization (pure function). Guarantees the result is a self-consistent
     * table: declarations of the reserved {@code hand}/{@code free} ids are
     * dropped (the hand is a system zone and the surface is core-owned), and
     * actions/stock entries whose zones do not resolve to a declared STACK
     * zone are dropped so both peers agree on the usable action table.
     */
    public TableLayoutDefinition normalized()
    {
        Map<ResourceLocation, ZoneDefinition> result = new LinkedHashMap<>();
        for (ZoneDefinition zone : this.zones)
        {
            ResourceLocation zoneId = zone.id();
            if (ZONE_HAND.equals(zoneId) || ZONE_FREE.equals(zoneId))
            {
                continue; // reserved ids are never declarable layout zones
            }
            result.putIfAbsent(zoneId, zone);
        }

        // Keep only actions the normalized layout can actually run.
        List<TableActionDefinition> actions = new ArrayList<>();
        for (TableActionDefinition action : this.actions)
        {
            if (action.sourceZone() != null)
            {
                ZoneDefinition source = result.get(action.sourceZone());
                if (source == null || source.kind() != ZoneDefinition.Kind.STACK)
                {
                    continue; // dangling or non-pile source: the table cannot run it
                }
            }
            actions.add(action);
        }

        // Keep only stock entries pointing at a declared pile.
        Map<String, ResourceLocation> initialZones = new LinkedHashMap<>();
        for (Map.Entry<String, ResourceLocation> entry : this.initialZones.entrySet())
        {
            ZoneDefinition target = result.get(entry.getValue());
            if (target != null && target.kind() == ZoneDefinition.Kind.STACK)
            {
                initialZones.put(entry.getKey(), entry.getValue());
            }
        }

        return new TableLayoutDefinition(this.id, this.displayName,
                new ArrayList<>(result.values()), actions, initialZones);
    }

    public static final class Builder
    {
        private final ResourceLocation id;
        @Nullable
        private Component displayName;
        private final List<ZoneDefinition> zones = new ArrayList<>();
        private final List<TableActionDefinition> actions = new ArrayList<>();
        private final Map<String, ResourceLocation> initialZones = new LinkedHashMap<>();

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

        /** Appends one action; duplicate action ids are rejected. */
        public Builder action(TableActionDefinition action)
        {
            Objects.requireNonNull(action, "action");
            for (TableActionDefinition existing : this.actions)
            {
                if (existing.id().equals(action.id()))
                {
                    throw new IllegalArgumentException("Duplicate action id in layout " + this.id + ": " + action.id());
                }
            }
            this.actions.add(action);
            return this;
        }

        /**
         * Declares the stock pile for a card-set selector. Content must at
         * least declare the {@value #INITIAL_DEFAULT_KEY} entry; the target
         * should be a STACK zone of this layout (checked at normalization).
         */
        public Builder initial(String selector, ResourceLocation zoneId)
        {
            Objects.requireNonNull(selector, "selector");
            Objects.requireNonNull(zoneId, "zoneId");
            this.initialZones.put(selector, zoneId);
            return this;
        }

        public TableLayoutDefinition build()
        {
            return new TableLayoutDefinition(this);
        }
    }
}
