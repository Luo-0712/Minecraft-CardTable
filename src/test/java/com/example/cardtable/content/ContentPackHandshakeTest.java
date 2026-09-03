package com.example.cardtable.content;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentPackHandshakeTest
{
    private static ContentPack pack(String id, String version, String hash)
    {
        return ContentPack.textureless(new ResourceLocation(id), id, version, hash);
    }

    @Test
    void matchingListsPass()
    {
        ContentPack server = pack("cardtable:standard", "1.0.0", "abc123");
        var client = List.of(new ContentPackHandshake.PackEntry("cardtable:standard", "1.0.0", "abc123"));

        ContentPackHandshake.CompareResult result = ContentPackHandshake.compare(List.of(server), client);
        assertTrue(result.passed());
    }

    @Test
    void missingPackFailsWithItsId()
    {
        ContentPack server = pack("cardtable:standard", "1.0.0", "abc123");
        ContentPackHandshake.CompareResult result = ContentPackHandshake.compare(List.of(server), List.of());
        assertFalse(result.passed());
        assertTrue(result.failure().getString().contains("cardtable:standard"));
    }

    @Test
    void mismatchedHashOrVersionFails()
    {
        ContentPack server = pack("cardtable:standard", "1.0.0", "abc123");
        assertTrue(ContentPackHandshake.compare(List.of(server),
                List.of(new ContentPackHandshake.PackEntry("cardtable:standard", "1.0.0", "ffff")))
                .failure() != null);
        assertTrue(ContentPackHandshake.compare(List.of(server),
                List.of(new ContentPackHandshake.PackEntry("cardtable:standard", "2.0.0", "abc123")))
                .failure() != null);
    }

    // Extra client packs are harmless: the server never references ids it
    // does not know about, so the player may keep them installed.
    @Test
    void extraClientPacksAreIgnored()
    {
        ContentPack server = pack("cardtable:standard", "1.0.0", "abc123");
        var client = List.of(
                new ContentPackHandshake.PackEntry("cardtable:standard", "1.0.0", "abc123"),
                new ContentPackHandshake.PackEntry("mymod:fancy", "9.9.9", "beef"));
        assertTrue(ContentPackHandshake.compare(List.of(server), client).passed());
    }

    @Test
    void failureMessageNamesEveryProblem()
    {
        ContentPack first = pack("cardtable:standard", "1.0.0", "abc123");
        ContentPack second = pack("mymod:fancy", "2.0.0", "def456");
        var client = List.of(new ContentPackHandshake.PackEntry("mymod:fancy", "2.0.0", "deadbeef"));

        Component failure = ContentPackHandshake.compare(List.of(first, second), client).failure();
        assertTrue(failure != null);
        String message = failure.getString();
        assertTrue(message.contains("cardtable:standard"));
        assertTrue(message.contains("mymod:fancy"));
    }
}
