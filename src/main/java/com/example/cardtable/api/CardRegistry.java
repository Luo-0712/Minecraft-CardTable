package com.example.cardtable.api;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only facade over every known {@link CardDefinition} and
 * {@link CardSetDefinition}. Content is loaded once during common setup (pack
 * files plus third-party registrations) and never changes afterwards, so
 * callers may cache lookups freely.
 *
 * <p>The registry is deliberately not a Forge registry: card ids are pure
 * data keys, which is what allows the external card builder to ship content
 * without compiling against Minecraft.</p>
 */
public final class CardRegistry
{
    private static volatile Map<ResourceLocation, CardDefinition> cards = Map.of();
    private static volatile Map<ResourceLocation, CardSetDefinition> sets = Map.of();
    private static volatile Map<ResourceLocation, TableLayoutDefinition> layouts = Map.of();

    private CardRegistry()
    {
    }

    /**
     * Installs the final content snapshot. Internal: called exactly once by
     * the core content pack loader after the registration event; no other
     * caller may invoke this.
     */
    public static void load(Map<ResourceLocation, CardDefinition> loadedCards,
                            Map<ResourceLocation, CardSetDefinition> loadedSets)
    {
        load(loadedCards, loadedSets, Map.of());
    }

    /**
     * Installs the final content snapshot including table layouts. Internal:
     * called exactly once by the core content pack loader after the
     * registration event; no other caller may invoke this.
     */
    public static void load(Map<ResourceLocation, CardDefinition> loadedCards,
                            Map<ResourceLocation, CardSetDefinition> loadedSets,
                            Map<ResourceLocation, TableLayoutDefinition> loadedLayouts)
    {
        cards = Map.copyOf(loadedCards);
        sets = Map.copyOf(loadedSets);
        layouts = Map.copyOf(loadedLayouts);
    }

    @Nullable
    public static CardDefinition get(@Nullable ResourceLocation id)
    {
        return id == null ? null : cards.get(id);
    }

    public static Optional<CardDefinition> find(@Nullable ResourceLocation id)
    {
        return Optional.ofNullable(get(id));
    }

    public static Collection<CardDefinition> all()
    {
        return Collections.unmodifiableCollection(cards.values());
    }

    /** Every card whose {@link CardDefinition#cardSet()} equals {@code setId}. */
    public static Collection<CardDefinition> cardsInSet(ResourceLocation setId)
    {
        java.util.List<CardDefinition> result = new java.util.ArrayList<>();
        for (CardDefinition card : cards.values())
        {
            if (setId.equals(card.cardSet()))
            {
                result.add(card);
            }
        }
        result.sort(java.util.Comparator.comparingInt(CardDefinition::sortIndex)
                .thenComparing(card -> card.id().toString()));
        return result;
    }

    @Nullable
    public static CardSetDefinition getSet(@Nullable ResourceLocation id)
    {
        return id == null ? null : sets.get(id);
    }

    public static Optional<CardSetDefinition> findSet(@Nullable ResourceLocation id)
    {
        return Optional.ofNullable(getSet(id));
    }

    public static Collection<CardSetDefinition> allSets()
    {
        return Collections.unmodifiableCollection(sets.values());
    }

    /** The layout with the given id; does not include the built-in default layout. */
    @Nullable
    public static TableLayoutDefinition getLayout(@Nullable ResourceLocation id)
    {
        return id == null ? null : layouts.get(id);
    }

    public static Optional<TableLayoutDefinition> findLayout(@Nullable ResourceLocation id)
    {
        return Optional.ofNullable(getLayout(id));
    }

    public static Collection<TableLayoutDefinition> allLayouts()
    {
        return Collections.unmodifiableCollection(layouts.values());
    }
}
