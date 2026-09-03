package com.example.cardtable.card;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceZoneTest
{
    private CardInstance card(String path)
    {
        return new CardInstance(new ResourceLocation("cardtable", "standard/" + path));
    }

    @Test
    void addFindRepositionRemove()
    {
        SurfaceZone zone = new SurfaceZone();
        CardInstance card = card("ace_of_spades");
        zone.add(card, 0.25F, 0.75F);
        assertEquals(1, zone.size());
        assertTrue(zone.find(card.instanceId()).isPresent());
        assertEquals(0.25F, zone.find(card.instanceId()).get().x());

        assertTrue(zone.reposition(card.instanceId(), 0.5F, 0.5F));
        assertEquals(0.5F, zone.find(card.instanceId()).get().x());

        assertTrue(zone.remove(card.instanceId()).isPresent());
        assertEquals(0, zone.size());
        assertTrue(zone.remove(card.instanceId()).isEmpty());
    }

    @Test
    void positionsAreClampedToUnitSquare()
    {
        SurfaceZone zone = new SurfaceZone();
        CardInstance card = card("ace_of_spades");
        zone.add(card, 5.0F, -2.0F);
        SurfaceZone.SurfaceCard entry = zone.find(card.instanceId()).get();
        assertEquals(1.0F, entry.x());
        assertEquals(0.0F, entry.y());
    }

    @Test
    void unknownIdsAreRejected()
    {
        SurfaceZone zone = new SurfaceZone();
        assertFalse(zone.reposition(UUID.randomUUID(), 0.5F, 0.5F));
        assertTrue(zone.remove(UUID.randomUUID()).isEmpty());
    }

    @Test
    void roundTripsThroughNbt()
    {
        SurfaceZone zone = new SurfaceZone();
        CardInstance first = card("ace_of_spades");
        CardInstance second = card("king_of_hearts");
        zone.add(first, 0.1F, 0.2F);
        zone.add(second, 0.8F, 0.9F);
        second.setFaceUp(true);

        SurfaceZone loaded = new SurfaceZone();
        loaded.load(zone.save());

        assertEquals(2, loaded.size());
        assertTrue(loaded.find(first.instanceId()).isPresent());
        assertEquals(0.1F, loaded.find(first.instanceId()).get().x());
        assertTrue(loaded.find(second.instanceId()).get().card().isFaceUp());
    }
}
