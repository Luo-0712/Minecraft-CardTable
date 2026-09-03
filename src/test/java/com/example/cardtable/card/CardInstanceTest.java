package com.example.cardtable.card;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CardInstanceTest
{
    private static ResourceLocation id(String path)
    {
        return new ResourceLocation("cardtable", "standard/" + path);
    }

    @Test
    void newCardsStartFaceDownAndUpright()
    {
        CardInstance card = new CardInstance(id("ace_of_spades"));
        assertEquals(false, card.isFaceUp());
        assertEquals(0, card.rotation());
    }

    @Test
    void rotationNormalizesToQuarterTurns()
    {
        CardInstance card = new CardInstance(id("ace_of_spades"));
        card.setRotation(450);
        assertEquals(90, card.rotation());
        card.setRotation(-90);
        assertEquals(270, card.rotation());
        card.rotate();
        assertEquals(0, card.rotation());
    }

    @Test
    void flipTogglesFace()
    {
        CardInstance card = new CardInstance(id("ace_of_spades"));
        card.flip();
        assertEquals(true, card.isFaceUp());
        card.flip();
        assertEquals(false, card.isFaceUp());
    }

    @Test
    void roundTripsThroughNbt()
    {
        CardInstance card = new CardInstance(id("ace_of_spades"));
        card.flip();
        card.setRotation(180);

        CardInstance loaded = CardInstance.load(card.save());
        assertEquals(card.instanceId(), loaded.instanceId());
        assertEquals(card.definitionId(), loaded.definitionId());
        assertEquals(card.isFaceUp(), loaded.isFaceUp());
        assertEquals(card.rotation(), loaded.rotation());
    }

    @Test
    void damagedTagsLoadAsNull()
    {
        assertNull(CardInstance.load(new CompoundTag()));
        CompoundTag broken = new CompoundTag();
        broken.putUUID("Id", UUID.randomUUID());
        broken.putString("Definition", "not a resource location");
        assertNull(CardInstance.load(broken));
    }

    @Test
    void instancesAreUnique()
    {
        assertNotEquals(new CardInstance(id("ace_of_spades")).instanceId(),
                new CardInstance(id("ace_of_spades")).instanceId());
    }
}
