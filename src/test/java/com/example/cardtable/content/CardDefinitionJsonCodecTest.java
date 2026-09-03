package com.example.cardtable.content;

import com.example.cardtable.api.CardDefinition;
import com.example.cardtable.api.CardSetDefinition;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CardDefinitionJsonCodecTest
{
    private static final CardDefinitionJsonCodec.TextureMapper MAPPER =
            relative -> new ResourceLocation("cardtable", "card/standard/" + relative);

    @Test
    void parsesPackMeta()
    {
        JsonObject json = JsonParser.parseString("""
                {
                  "format": 1,
                  "id": "cardtable:standard",
                  "name": "标准扑克",
                  "version": "1.0.0",
                  "set": { "name": "标准扑克", "back": "back" }
                }
                """).getAsJsonObject();

        CardDefinitionJsonCodec.PackMeta meta = CardDefinitionJsonCodec.parsePackMeta(json);
        assertEquals("cardtable:standard", meta.id().toString());
        assertEquals("1.0.0", meta.version());
        assertEquals("标准扑克", meta.set().name());
        assertEquals("back", meta.set().back());
    }

    @Test
    void rejectsUnsupportedFormatAndMissingFields()
    {
        assertThrows(Exception.class, () -> CardDefinitionJsonCodec.parsePackMeta(
                JsonParser.parseString("{\"format\": 99, \"id\": \"a:b\", \"name\": \"n\", \"version\": \"1\"}")
                        .getAsJsonObject()));
        assertThrows(Exception.class, () -> CardDefinitionJsonCodec.parsePackMeta(
                JsonParser.parseString("{\"format\": 1, \"name\": \"n\", \"version\": \"1\"}")
                        .getAsJsonObject()));
    }

    @Test
    void parsesCardAndDerivesIds()
    {
        CardDefinitionJsonCodec.PackMeta meta = new CardDefinitionJsonCodec.PackMeta(
                new ResourceLocation("cardtable", "standard"), "标准扑克", "1.0.0", null);
        JsonObject json = JsonParser.parseString("""
                {
                  "id": "ace_of_spades",
                  "display_name": {"text": "黑桃A"},
                  "front": "ace_of_spades",
                  "sort": 3
                }
                """).getAsJsonObject();

        CardDefinitionJsonCodec.ParsedCard parsed = CardDefinitionJsonCodec.parseCard(json, meta, MAPPER);
        CardDefinition definition = parsed.definition();
        assertEquals("cardtable:standard/ace_of_spades", definition.id().toString());
        assertEquals("cardtable:card/standard/ace_of_spades", definition.frontTexture().toString());
        assertEquals("cardtable:standard", definition.cardSet().toString());
        assertEquals(3, definition.sortIndex());
    }

    // The canonical form is derived from the parsed model: key order,
    // whitespace and an explicit default sort index must not change the hash.
    @Test
    void hashIsStableAcrossFormatting()
    {
        CardDefinitionJsonCodec.PackMeta meta = new CardDefinitionJsonCodec.PackMeta(
                new ResourceLocation("cardtable", "standard"), "标准扑克", "1.0.0", null);
        CardDefinitionJsonCodec.ParsedCard authored = CardDefinitionJsonCodec.parseCard(
                JsonParser.parseString("""
                        { "front": "ace_of_spades", "id": "ace_of_spades", "sort": 0,
                          "display_name": {"text": "黑桃A"} }
                        """).getAsJsonObject(), meta, MAPPER);
        CardDefinitionJsonCodec.ParsedCard differentFormatting = CardDefinitionJsonCodec.parseCard(
                JsonParser.parseString("""
                        { "id": "ace_of_spades", "display_name": {"text": "黑桃A"},
                          "front": "ace_of_spades" }
                        """).getAsJsonObject(), meta, MAPPER);

        assertEquals(authored.canonicalLine(), differentFormatting.canonicalLine());
        assertEquals(CardDefinitionJsonCodec.contentHash(List.of(authored.canonicalLine())),
                CardDefinitionJsonCodec.contentHash(List.of(differentFormatting.canonicalLine())));
    }

    @Test
    void hashChangesWhenContentChanges()
    {
        CardDefinitionJsonCodec.PackMeta meta = new CardDefinitionJsonCodec.PackMeta(
                new ResourceLocation("cardtable", "standard"), "标准扑克", "1.0.0", null);
        CardDefinitionJsonCodec.ParsedCard card = CardDefinitionJsonCodec.parseCard(
                JsonParser.parseString("""
                        { "id": "ace_of_spades", "display_name": {"text": "黑桃A"}, "front": "ace_of_spades" }
                        """).getAsJsonObject(), meta, MAPPER);
        CardDefinitionJsonCodec.ParsedCard changed = CardDefinitionJsonCodec.parseCard(
                JsonParser.parseString("""
                        { "id": "ace_of_spades", "display_name": {"text": "黑桃A!"}, "front": "ace_of_spades" }
                        """).getAsJsonObject(), meta, MAPPER);

        assertNotEquals(card.canonicalLine(), changed.canonicalLine());
        assertNotEquals(CardDefinitionJsonCodec.contentHash(List.of(card.canonicalLine())),
                CardDefinitionJsonCodec.contentHash(List.of(changed.canonicalLine())));
    }

    @Test
    void hashIsOrderIndependent()
    {
        String first = "card|a|s|f|-|0|d";
        String second = "set|s|-|back";
        String oneWay = CardDefinitionJsonCodec.contentHash(List.of(first, second));
        String otherWay = CardDefinitionJsonCodec.contentHash(List.of(second, first));
        assertEquals(oneWay, otherWay);
    }

    @Test
    void registersSetWithMappedBack()
    {
        CardDefinitionJsonCodec.PackMeta meta = new CardDefinitionJsonCodec.PackMeta(
                new ResourceLocation("cardtable", "standard"), "标准扑克", "1.0.0",
                new CardDefinitionJsonCodec.PackMeta.SetMeta("标准扑克", "back"));
        String[] canonical = new String[1];
        CardSetDefinition set = null;
        var sink = new java.util.ArrayList<CardSetDefinition>();
        String line = CardDefinitionJsonCodec.registerSet(meta, MAPPER, sink::add);
        assertEquals(1, sink.size());
        assertEquals("cardtable:card/standard/back", sink.get(0).defaultBackTexture().toString());
        assertEquals("set|cardtable:standard|cardtable:card/standard/back|", line.substring(0,
                line.lastIndexOf('|') + 1));
    }
}
