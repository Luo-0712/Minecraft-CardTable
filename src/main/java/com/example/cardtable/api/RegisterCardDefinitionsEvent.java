package com.example.cardtable.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.Event;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The single write access point into the {@link CardRegistry}. Fired on the
 * mod event bus once during common setup, after the core has registered every
 * card loaded from content packs; third-party mods may register their own
 * definitions here and never need a Forge registry of their own.
 *
 * <p>Duplicate ids are rejected so two content providers can never silently
 * shadow each other's cards.</p>
 */
public class RegisterCardDefinitionsEvent extends Event
{
    private final Map<ResourceLocation, CardDefinition> cards = new HashMap<>();
    private final Map<ResourceLocation, CardSetDefinition> sets = new HashMap<>();

    /** Registers one card; throws if the id was already taken in this event. */
    public void register(CardDefinition card)
    {
        java.util.Objects.requireNonNull(card, "card");
        if (this.cards.putIfAbsent(card.id(), card) != null)
        {
            throw new IllegalArgumentException("Duplicate card id: " + card.id());
        }
    }

    /** Registers one card set; throws if the id was already taken in this event. */
    public void register(CardSetDefinition set)
    {
        java.util.Objects.requireNonNull(set, "set");
        if (this.sets.putIfAbsent(set.id(), set) != null)
        {
            throw new IllegalArgumentException("Duplicate card set id: " + set.id());
        }
    }

    /**
     * Internal view of the registration buffer, read exactly once by the core
     * content pack loader after dispatch. Not part of the public contract.
     */
    public Map<ResourceLocation, CardDefinition> cardsSnapshot()
    {
        return Collections.unmodifiableMap(this.cards);
    }

    /**
     * Internal view of the registration buffer, read exactly once by the core
     * content pack loader after dispatch. Not part of the public contract.
     */
    public Map<ResourceLocation, CardSetDefinition> setsSnapshot()
    {
        return Collections.unmodifiableMap(this.sets);
    }
}
