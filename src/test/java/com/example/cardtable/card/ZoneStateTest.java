package com.example.cardtable.card;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneStateTest
{
    private static CardInstance card(String definition)
    {
        return new CardInstance(new ResourceLocation("cardtable", definition));
    }

    @Test
    void stackKeepsBottomToTopOrderAndClampsNothing()
    {
        ZoneState zone = ZoneState.stack();
        zone.addToStackTop(card("ace"));
        zone.addToStackTop(card("king"));
        assertEquals(2, zone.size());
        assertEquals(new ResourceLocation("cardtable", "king"), zone.takeFromStackTop().definitionId());
        assertEquals(1, zone.size());
        assertEquals(ZoneState.Storage.STACK, zone.storage());
    }

    @Test
    void placedEntriesClampToUnitRange()
    {
        ZoneState zone = ZoneState.placed();
        zone.addPlaced(card("ace"), 1.5F, -0.2F);
        ZoneState.PlacedCard placed = zone.placedCards().get(0);
        assertEquals(1.0F, placed.x());
        assertEquals(0.0F, placed.y());
    }

    @Test
    void placedRepositionAndRemove()
    {
        ZoneState zone = ZoneState.placed();
        CardInstance ace = card("ace");
        zone.addPlaced(ace, 0.1F, 0.1F);
        assertTrue(zone.reposition(ace.instanceId(), 0.4F, 0.6F));
        assertFalse(zone.reposition(UUID.randomUUID(), 0.4F, 0.6F));
        assertEquals(0.4F, zone.find(ace.instanceId()).get().x());
        assertEquals(ace, zone.remove(ace.instanceId()).get());
        assertTrue(zone.isEmpty());
    }

    @Test
    void stackNbtRoundTrip()
    {
        ZoneState zone = ZoneState.stack();
        zone.addToStackTop(card("ace"));
        zone.addToStackTop(card("king"));

        ZoneState loaded = ZoneState.load(zone.save());
        assertNotNull(loaded);
        assertEquals(ZoneState.Storage.STACK, loaded.storage());
        assertEquals(2, loaded.size());
        assertEquals(new ResourceLocation("cardtable", "king"), loaded.takeFromStackTop().definitionId());
        assertEquals(new ResourceLocation("cardtable", "ace"), loaded.takeFromStackTop().definitionId());
    }

    @Test
    void placedNbtRoundTripPreservesClampedCoordinates()
    {
        ZoneState zone = ZoneState.placed();
        zone.addPlaced(card("ace"), 0.25F, 0.75F);

        ZoneState loaded = ZoneState.load(zone.save());
        assertNotNull(loaded);
        assertEquals(ZoneState.Storage.PLACED, loaded.storage());
        assertEquals(0.25F, loaded.placedCards().get(0).x());
        assertEquals(0.75F, loaded.placedCards().get(0).y());
    }

    @Test
    void malformedLoadReturnsNull()
    {
        assertNull(ZoneState.load(new CompoundTag()));
    }

    @Test
    void deepCopyIsIndependent()
    {
        ZoneState zone = ZoneState.placed();
        CardInstance ace = card("ace");
        zone.addPlaced(ace, 0.1F, 0.1F);
        ZoneState copy = new ZoneState(zone);
        zone.remove(ace.instanceId());

        assertEquals(1, copy.size());
        assertEquals(0, zone.size());

        ZoneState stackCopy = new ZoneState(ZoneState.stack());
        assertEquals(0, stackCopy.size());
    }

    @Test
    void removeIfDefinitionCoversBothStorages()
    {
        CardInstance ace = card("ace");
        CardInstance king = card("king");
        ZoneState stack = ZoneState.stack();
        stack.addToStackTop(ace);
        stack.addToStackTop(king);
        stack.removeIfDefinition(Set.of(new ResourceLocation("cardtable", "ace")));
        assertEquals(List.of(king), stack.stackCards());

        ZoneState placed = ZoneState.placed();
        placed.addPlaced(ace, 0.0F, 0.0F);
        placed.removeIfDefinition(Set.of(new ResourceLocation("cardtable", "ace")));
        assertTrue(placed.isEmpty());
    }
}
