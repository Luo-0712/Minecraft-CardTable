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

    // Declaring an icon is a content change, so one peer shipping the icon and
    // the other not shipping it must be caught — and reported against the pack
    // that actually differs, not just "some pack mismatched".
    @Test
    void differingIconDeclarationFailsAndNamesThePack()
    {
        String legacyLine = "set|cardtable:uno_cards|cardtable:card/uno_cards/back|{\"text\":\"UNO\"}";
        String iconLine = "set2|cardtable:uno_cards|cardtable:card/uno_cards/back|"
                + "cardtable:card/uno_cards/icon|{\"text\":\"UNO\"}";

        ContentPack server = pack("cardtable:uno_cards", "1.0.0",
                CardDefinitionJsonCodec.contentHash(List.of(legacyLine)));
        var client = List.of(new ContentPackHandshake.PackEntry("cardtable:uno_cards", "1.0.0",
                CardDefinitionJsonCodec.contentHash(List.of(iconLine))));

        ContentPackHandshake.CompareResult result = ContentPackHandshake.compare(List.of(server), client);
        assertFalse(result.passed());
        assertTrue(result.failure().getString().contains("cardtable:uno_cards"));
    }
}
