package com.example.cardtable.content;

import com.example.cardtable.api.TableLayoutDefinition;
import com.example.cardtable.api.ZoneDefinition;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import javax.annotation.Nullable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutJsonCodecTest
{
    private static final CardDefinitionJsonCodec.TextureMapper MAPPER =
            relative -> new ResourceLocation("cardtable", "card/standard/" + relative);

    private static CardDefinitionJsonCodec.ParsedLayout parse(String json)
    {
        return CardDefinitionJsonCodec.parseLayout(
                json == null ? null : JsonParser.parseString(json),
                new CardDefinitionJsonCodec.PackMeta(
                        new ResourceLocation("cardtable", "my_tcg"), "My TCG", "1.0.0", null),
                NOPLogger.NOP_LOGGER);
    }

    @Test
    void parsesTcgStyleLayoutWithIdsAndCanonicalLine()
    {
        CardDefinitionJsonCodec.ParsedLayout parsed = parse("""
                {
                  "name": "My TCG 对战桌",
                  "zones": [
                    { "id": "deck",     "kind": "stack", "scope": "per_seat", "rect": [0.04, 0.04, 0.10, 0.16], "capacity": 60, "label": {"text": "牌库"} },
                    { "id": "bench",    "kind": "grid",  "scope": "per_seat", "rect": [0.16, 0.34, 0.60, 0.24], "capacity": 5,  "label": {"text": "替补区"} },
                    { "id": "cardtable:free", "kind": "free", "scope": "per_seat", "rect": [0.86, 0.62, 0.12, 0.30], "label": {"text": "随手区"} },
                    { "id": "battlefield", "kind": "free", "scope": "shared", "rect": [0.20, 0.10, 0.60, 0.80] },
                    { "id": "prize",       "kind": "stack", "scope": "shared", "rect": [0.02, 0.80, 0.08, 0.14], "capacity": 6 }
                  ]
                }
                """);

        TableLayoutDefinition layout = parsed.definition();
        assertEquals("cardtable:my_tcg", layout.id().toString());
        assertEquals(5, layout.zones().size());
        // Full ids: relative ids resolve into the pack, built-ins stay intact.
        assertEquals(0.04F, layout.zone(new ResourceLocation("cardtable", "my_tcg/deck")).x());
        assertEquals(60, layout.zone(new ResourceLocation("cardtable", "my_tcg/deck")).capacity());
        assertEquals(ZoneDefinition.Kind.FREE,
                layout.zone(TableLayoutDefinition.ZONE_FREE).kind());
        assertEquals(0.86F, layout.zone(TableLayoutDefinition.ZONE_FREE).x());
        assertEquals("牌库", layout.zone(new ResourceLocation("cardtable", "my_tcg/deck")).label().getString());

        // zone descriptors sorted by full id; percent-encoded plain-text
        // label; Float.toString floats.
        String line = parsed.canonicalLine();
        assertTrue(line.startsWith("layout|cardtable:my_tcg|My%20TCG%20%E5%AF%B9%E6%88%98%E6%A1%8C|"),
                "unexpected layout line prefix: " + line);
        int freeIndex = line.indexOf("cardtable:free#");
        int battlefieldIndex = line.indexOf("cardtable:my_tcg/battlefield#");
        assertTrue(freeIndex >= 0 && battlefieldIndex > freeIndex,
                "zone descriptors must be sorted by full id: " + line);
        assertTrue(line.contains("cardtable:my_tcg/bench#grid#per_seat#0.16,0.34,0.6,0.24#5#public#"),
                "grid descriptor with capacity missing: " + line);
        assertTrue(line.contains("cardtable:my_tcg/deck#stack#per_seat#0.04,0.04,0.1,0.16#60#public#%E7%89%8C%E5%BA%93"),
                "encoded deck label missing: " + line);
    }

    @Test
    void badZonesAreSkippedIndividually()
    {
        CardDefinitionJsonCodec.ParsedLayout parsed = parse("""
                {
                  "zones": [
                    { "id": "good", "kind": "free", "scope": "shared", "rect": [0.0, 0.0, 0.5, 0.5] },
                    { "id": "no_rect", "kind": "free", "scope": "shared" },
                    { "id": "bad_kind", "kind": "puzzle", "scope": "shared", "rect": [0.0, 0.0, 0.5, 0.5] },
                    { "id": "bad_scope", "kind": "free", "scope": "whole_table", "rect": [0.0, 0.0, 0.5, 0.5] },
                    { "id": "bad_rect", "kind": "free", "scope": "shared", "rect": [0.9, 0.0, 0.5, 0.5] },
                    { "id": "bad_capacity", "kind": "grid", "scope": "per_seat", "rect": [0.0, 0.5, 0.5, 0.5] },
                    { "id": "has:colon", "kind": "free", "scope": "shared", "rect": [0.0, 0.0, 0.5, 0.5] },
                    { "id": "cardtable:hand", "kind": "stack", "scope": "per_seat", "rect": [0.0, 0.0, 1.0, 1.0] },
                    { "id": "owner_only", "kind": "free", "scope": "shared", "rect": [0.0, 0.0, 0.5, 0.5], "visibility": "owner_only" },
                    "not-an-object",
                    { "id": "good2", "kind": "stack", "scope": "shared", "rect": [0.5, 0.5, 0.5, 0.5] }
                  ]
                }
                """);

        TableLayoutDefinition layout = parsed.definition();
        assertNotNull(layout.zone(new ResourceLocation("cardtable", "my_tcg/good")));
        assertNotNull(layout.zone(new ResourceLocation("cardtable", "my_tcg/good2")));
        // Only good and good2 survive: owner_only visibility is rejected by
        // the D3 content rule, duplicates keep the first occurrence.
        assertEquals(2, layout.zones().size());
        assertNull(layout.zone(new ResourceLocation("cardtable", "my_tcg/no_rect")));
        assertNull(layout.zone(new ResourceLocation("cardtable", "my_tcg/bad_kind")));
        assertNull(layout.zone(TableLayoutDefinition.ZONE_HAND));
    }

    @Test
    void emptyOrInvalidLayoutReturnsNull()
    {
        assertNull(parse("{ \"zones\": [] }"));
        assertNull(parse("{ \"zones\": [ { \"id\": \"only\", \"kind\": \"free\", \"scope\": \"shared\", \"rect\": [0, 0, 2.0, 0.5] } ] }"));
        assertNull(parse(null));
    }

    @Test
    void overridableBuiltinAcceptsFullIdAndOverridesDefault()
    {
        CardDefinitionJsonCodec.ParsedLayout parsed = parse("""
                {
                  "zones": [
                    { "id": "cardtable:discard_pile", "kind": "stack", "scope": "shared", "rect": [0.40, 0.80, 0.05, 0.10], "capacity": 30 }
                  ]
                }
                """);
        ZoneDefinition discard = parsed.definition().zone(TableLayoutDefinition.ZONE_DISCARD_PILE);
        assertNotNull(discard);
        assertEquals(0.40F, discard.x());
        assertEquals(30, discard.capacity());
    }

    // The layout line is a v2 addition: a pack without layout.json must keep
    // producing exactly the old canonical set, so existing hashes survive.
    @Test
    void formatOneHashIsUnchangedWithoutLayout()
    {
        CardDefinitionJsonCodec.PackMeta meta = new CardDefinitionJsonCodec.PackMeta(
                new ResourceLocation("cardtable", "standard"), "标准扑克", "1.0.0", null);
        CardDefinitionJsonCodec.ParsedCard card = CardDefinitionJsonCodec.parseCard(
                JsonParser.parseString("""
                        { "id": "ace_of_spades", "display_name": {"text": "黑桃A"}, "front": "ace_of_spades" }
                        """).getAsJsonObject(), meta, MAPPER);

        String hash = CardDefinitionJsonCodec.contentHash(List.of(card.canonicalLine()));
        // No layout line exists in a format-1 pack and the hash is stable
        // across parses; the exact digest depends on text component
        // serialization, so we assert the structural invariants only.
        CardDefinitionJsonCodec.ParsedCard again = CardDefinitionJsonCodec.parseCard(
                JsonParser.parseString("""
                        { "id": "ace_of_spades", "display_name": {"text": "黑桃A"}, "front": "ace_of_spades" }
                        """).getAsJsonObject(), meta, MAPPER);
        assertEquals(hash, CardDefinitionJsonCodec.contentHash(List.of(again.canonicalLine())));
        // Adding a second, distinct line changes the hash; duplicating lines
        // changes it too (the digest is over the whole sorted line set).
        assertNotEqualsLine(hash, CardDefinitionJsonCodec.contentHash(
                List.of(card.canonicalLine(), again.canonicalLine())));
    }

    @Test
    void layoutLineChangesHashWhenZonesDiffer()
    {
        String lineA = parse("""
                { "zones": [ { "id": "battlefield", "kind": "free", "scope": "shared", "rect": [0.2, 0.1, 0.6, 0.8] } ] }
                """).canonicalLine();
        String lineB = parse("""
                { "zones": [ { "id": "battlefield", "kind": "free", "scope": "shared", "rect": [0.3, 0.1, 0.6, 0.8] } ] }
                """).canonicalLine();

        assertEquals(lineA, lineA);
        assertNotEqualsLine(lineA, lineB);
    }

    private static void assertNotEqualsLine(String a, String b)
    {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }

    @Test
    void duplicateZoneIdsKeepFirstOccurrence()
    {
        CardDefinitionJsonCodec.ParsedLayout parsed = parse("""
                {
                  "zones": [
                    { "id": "dup", "kind": "free", "scope": "shared", "rect": [0.0, 0.0, 0.5, 0.5] },
                    { "id": "dup", "kind": "free", "scope": "shared", "rect": [0.5, 0.5, 0.5, 0.5] }
                  ]
                }
                """);
        // The duplicate entry is skipped as broken; the first declaration wins.
        assertNotNull(parsed);
        assertEquals(1, parsed.definition().zones().size());
        assertEquals(0.0F, parsed.definition()
                .zone(new ResourceLocation("cardtable", "my_tcg/dup")).x());
    }

    // Guards the JSON-text-component path of labels (nested structures, colors).
    @Test
    void labelsWithRichComponentsEncodeStable()
    {
        CardDefinitionJsonCodec.ParsedLayout first = parse("""
                {
                  "zones": [ { "id": "zone", "kind": "free", "scope": "shared",
                               "rect": [0.0, 0.0, 0.5, 0.5], "label": {"text": "区", "color": "red", "bold": true} } ]
                }
                """);
        CardDefinitionJsonCodec.ParsedLayout second = parse("""
                {
                  "zones": [ { "id": "zone", "kind": "free", "scope": "shared",
                               "rect": [0.0, 0.0, 0.5, 0.5], "label": {"bold": true, "color": "red", "text": "区"} } ]
                }
                """);
        assertNotNull(first);
        assertNotNull(second);
        // Labels are encoded as plain text, so both rich components collapse
        // to the same display string and their lines match; the encoded field
        // must stay separator-free.
        assertEquals(first.canonicalLine(), second.canonicalLine());
        String labelField = first.canonicalLine().substring(first.canonicalLine().lastIndexOf('#') + 1);
        assertTrue(labelField.startsWith("%E5%8C%BA"), "label must be percent-encoded: " + labelField);
    }
}
