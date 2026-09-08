package com.example.cardtable.content;

import com.example.cardtable.api.TableActionDefinition;
import com.example.cardtable.api.TableLayoutDefinition;
import com.example.cardtable.api.ZoneDefinition;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static final String STOCK_AND_ACTIONS = """
            , "initial": { "*": "deck" },
              "actions": [
                { "id": "draw",    "type": "draw",    "source": "deck", "amount": 1, "key": "key.keyboard.d" },
                { "id": "shuffle", "type": "shuffle", "source": "deck", "key": "key.keyboard.s" },
                { "id": "flip",    "type": "flip",    "key": "key.keyboard.f" }
              ]
            """;

    @Test
    void parsesZonesStockAndActionsWithCanonicalLine()
    {
        CardDefinitionJsonCodec.ParsedLayout parsed = parse("""
                {
                  "name": "My TCG 对战桌",
                  "zones": [
                    { "id": "deck",     "kind": "stack", "scope": "shared",   "rect": [0.04, 0.04, 0.10, 0.16], "capacity": 60, "label": {"text": "牌库"} },
                    { "id": "bench",    "kind": "grid",  "scope": "per_seat", "rect": [0.16, 0.34, 0.60, 0.24], "capacity": 5,  "label": {"text": "替补区"} },
                    { "id": "cardtable:free", "kind": "free", "scope": "per_seat", "rect": [0.86, 0.62, 0.12, 0.30], "label": {"text": "随手区"} },
                    { "id": "battlefield", "kind": "free", "scope": "shared", "rect": [0.20, 0.10, 0.60, 0.80] },
                    { "id": "prize",       "kind": "stack", "scope": "shared", "rect": [0.02, 0.80, 0.08, 0.14], "capacity": 6 }
                  ]""" + STOCK_AND_ACTIONS + """
                }
                """);

        TableLayoutDefinition layout = parsed.definition();
        assertEquals("cardtable:my_tcg", layout.id().toString());
        assertEquals(5, layout.zones().size());
        // Full ids: relative ids resolve into the pack, the reserved surface
        // keeps its full id.
        assertEquals(0.04F, layout.zone(new ResourceLocation("cardtable", "my_tcg/deck")).x());
        assertEquals(60, layout.zone(new ResourceLocation("cardtable", "my_tcg/deck")).capacity());
        assertEquals(ZoneDefinition.Kind.FREE,
                layout.zone(TableLayoutDefinition.ZONE_FREE).kind());
        assertEquals(0.86F, layout.zone(TableLayoutDefinition.ZONE_FREE).x());
        assertEquals("牌库", layout.zone(new ResourceLocation("cardtable", "my_tcg/deck")).label().getString());

        // The stock mapping resolves to the declared shared pile.
        assertEquals(new ResourceLocation("cardtable", "my_tcg/deck"),
                layout.initialZones().get("*"));
        assertEquals("cardtable:my_tcg/deck", layout.initialZoneFor(new ResourceLocation("other", "set")).id().toString());

        // The action table resolves relative sources and drops nothing here.
        assertEquals(3, layout.actions().size());
        assertEquals(TableActionDefinition.Type.DRAW, layout.actions().get(0).type());
        assertEquals(new ResourceLocation("cardtable", "my_tcg/deck"),
                layout.actions().get(0).sourceZone());
        assertNull(layout.actions().get(2).sourceZone());

        // Zone descriptors sorted by full id, then the stock, then the actions;
        // percent-encoded labels; Float.toString floats.
        String line = parsed.canonicalLine();
        assertTrue(line.startsWith("layout3|cardtable:my_tcg|My%20TCG%20%E5%AF%B9%E6%88%98%E6%A1%8C|"),
                "unexpected layout line prefix: " + line);
        int freeIndex = line.indexOf("cardtable:free#");
        int battlefieldIndex = line.indexOf("cardtable:my_tcg/battlefield#");
        assertTrue(freeIndex >= 0 && battlefieldIndex > freeIndex,
                "zone descriptors must be sorted by full id: " + line);
        assertTrue(line.contains("cardtable:my_tcg/bench#grid#per_seat#0.16,0.34,0.6,0.24#5#public#"),
                "grid descriptor with capacity missing: " + line);
        assertTrue(line.contains("cardtable:my_tcg/deck#stack#shared#0.04,0.04,0.1,0.16#60#public#%E7%89%8C%E5%BA%93"),
                "encoded deck label missing: " + line);
        assertTrue(line.contains("|*#cardtable:my_tcg/deck|"),
                "stock descriptor missing: " + line);
        assertTrue(line.contains("cardtable:my_tcg/draw#draw#cardtable:my_tcg/deck#-#1#key.keyboard.d#-"),
                "draw action descriptor missing: " + line);
        assertTrue(line.contains("cardtable:my_tcg/shuffle#shuffle#cardtable:my_tcg/deck#-#1#key.keyboard.s#-"),
                "shuffle action descriptor missing: " + line);
        assertTrue(line.contains("cardtable:my_tcg/flip#flip#-#-#1#key.keyboard.f#-"),
                "flip action descriptor missing: " + line);
    }

    @Test
    void badZonesAndActionsAreSkippedIndividually()
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
                    { "id": "cardtable:draw_pile", "kind": "stack", "scope": "shared", "rect": [0.0, 0.0, 0.5, 0.5] },
                    { "id": "owner_only", "kind": "free", "scope": "shared", "rect": [0.0, 0.0, 0.5, 0.5], "visibility": "owner_only" },
                    "not-an-object",
                    { "id": "stock", "kind": "stack", "scope": "shared", "rect": [0.5, 0.5, 0.4, 0.4] }
                  ],
                  "initial": { "*": "stock" },
                  "actions": [
                    { "id": "ok_draw",  "type": "draw", "source": "stock", "key": "key.keyboard.d" },
                    { "id": "bad_type", "type": "teleport" },
                    { "id": "bad_source", "type": "shuffle", "source": "nowhere" },
                    "not-an-object"
                  ]
                }
                """);

        TableLayoutDefinition layout = parsed.definition();
        assertNotNull(layout.zone(new ResourceLocation("cardtable", "my_tcg/good")));
        assertNotNull(layout.zone(new ResourceLocation("cardtable", "my_tcg/stock")));
        // Only good and stock survive: the deleted builtin draw_pile id is no
        // longer legal, duplicates keep the first occurrence, owner_only is
        // rejected by the content rule.
        assertEquals(2, layout.zones().size());
        assertNull(layout.zone(new ResourceLocation("cardtable", "my_tcg/no_rect")));
        assertNull(layout.zone(TableLayoutDefinition.ZONE_HAND));
        assertNull(layout.zone(new ResourceLocation("cardtable", "draw_pile")));

        // ok_draw and bad_source parse through (per-entry failures only):
        // the dangling source is dropped later by normalization.
        assertEquals(2, layout.actions().size());
        assertEquals(new ResourceLocation("cardtable", "my_tcg/ok_draw"), layout.actions().get(0).id());
        assertEquals(1, layout.normalized().actions().size());
        assertEquals(new ResourceLocation("cardtable", "my_tcg/ok_draw"),
                layout.normalized().actions().get(0).id());
    }

    @Test
    void emptyOrInvalidLayoutReturnsNull()
    {
        assertNull(parse("{ \"zones\": [], \"initial\": {\"*\": \"x\"} }"));
        assertNull(parse("{ \"zones\": [ { \"id\": \"only\", \"kind\": \"free\", \"scope\": \"shared\", \"rect\": [0, 0, 2.0, 0.5] } ], \"initial\": {\"*\": \"x\"} }"));
        assertNull(parse(null));
    }

    // The stock mapping is load-bearing: it rejects the whole layout, which
    // the pack loader turns into a whole-pack rejection.
    @Test
    void missingOrBadStockMappingThrows()
    {
        // No initial section at all.
        assertThrows(com.google.gson.JsonParseException.class, () -> parse("""
                { "zones": [ { "id": "deck", "kind": "stack", "scope": "shared", "rect": [0, 0, 0.1, 0.2] } ] }
                """));
        // Missing the required "*" default key.
        assertThrows(com.google.gson.JsonParseException.class, () -> parse("""
                { "zones": [ { "id": "deck", "kind": "stack", "scope": "shared", "rect": [0, 0, 0.1, 0.2] } ],
                  "initial": { "cardtable:standard": "deck" } }
                """));
        // Target is not a declared zone.
        assertThrows(com.google.gson.JsonParseException.class, () -> parse("""
                { "zones": [ { "id": "deck", "kind": "stack", "scope": "shared", "rect": [0, 0, 0.1, 0.2] } ],
                  "initial": { "*": "nowhere" } }
                """));
        // Target is declared but not a stack.
        assertThrows(com.google.gson.JsonParseException.class, () -> parse("""
                { "zones": [ { "id": "deck", "kind": "free", "scope": "shared", "rect": [0, 0, 0.1, 0.2] } ],
                  "initial": { "*": "deck" } }
                """));
        // Target is a stack but per-seat: the whole deck needs one shared pile.
        assertThrows(com.google.gson.JsonParseException.class, () -> parse("""
                { "zones": [ { "id": "deck", "kind": "stack", "scope": "per_seat", "rect": [0, 0, 0.1, 0.2] } ],
                  "initial": { "*": "deck" } }
                """));
    }

    // The reserved surface zone keeps its full-id override: rect/capacity/
    // label may change, scope/kind are locked by normalization.
    @Test
    void reservedFreeZoneAcceptsFullIdOverride()
    {
        CardDefinitionJsonCodec.ParsedLayout parsed = parse("""
                {
                  "zones": [
                    { "id": "deck", "kind": "stack", "scope": "shared", "rect": [0.0, 0.0, 0.1, 0.2] },
                    { "id": "cardtable:free", "kind": "free", "scope": "per_seat", "rect": [0.40, 0.80, 0.05, 0.10] }
                  ],
                  "initial": { "*": "deck" }
                }
                """);
        ZoneDefinition free = parsed.definition().zone(TableLayoutDefinition.ZONE_FREE);
        assertNotNull(free);
        assertEquals(0.40F, free.x());
        assertEquals(ZoneDefinition.Kind.FREE, free.kind());
        assertEquals(ZoneDefinition.Scope.PER_SEAT, free.scope());
        // Normalization keeps the override because scope/kind were untouched.
        TableLayoutDefinition normalized = parsed.definition().normalized();
        assertEquals(0.40F, normalized.zone(TableLayoutDefinition.ZONE_FREE).x());
    }

    // The card line is a frozen contract: the digest over set|/card| lines
    // must stay stable regardless of the layout refactor.
    @Test
    void cardLineHashIsStableAcrossParses()
    {
        CardDefinitionJsonCodec.PackMeta meta = new CardDefinitionJsonCodec.PackMeta(
                new ResourceLocation("cardtable", "standard"), "标准扑克", "1.0.0", null);
        CardDefinitionJsonCodec.ParsedCard card = CardDefinitionJsonCodec.parseCard(
                JsonParser.parseString("""
                        { "id": "ace_of_spades", "display_name": {"text": "黑桃A"}, "front": "ace_of_spades" }
                        """).getAsJsonObject(), meta, MAPPER);

        String hash = CardDefinitionJsonCodec.contentHash(List.of(card.canonicalLine()));
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
                { "zones": [ { "id": "deck", "kind": "stack", "scope": "shared", "rect": [0.2, 0.1, 0.6, 0.8] } ],
                  "initial": { "*": "deck" } }
                """).canonicalLine();
        String lineB = parse("""
                { "zones": [ { "id": "deck", "kind": "stack", "scope": "shared", "rect": [0.3, 0.1, 0.6, 0.8] } ],
                  "initial": { "*": "deck" } }
                """).canonicalLine();

        assertNotEqualsLine(lineA, lineB);
    }

    @Test
    void layoutLineChangesHashWhenActionsOrStockDiffer()
    {
        String base = """
                { "zones": [ { "id": "deck", "kind": "stack", "scope": "shared", "rect": [0.2, 0.1, 0.6, 0.8] } ],
                  "initial": { "*": "deck" },
                  "actions": [ { "id": "draw", "type": "draw", "source": "deck", "key": "key.keyboard.d" } ] }
                """;
        String withAnotherAction = """
                { "zones": [ { "id": "deck", "kind": "stack", "scope": "shared", "rect": [0.2, 0.1, 0.6, 0.8] } ],
                  "initial": { "*": "deck" },
                  "actions": [ { "id": "draw", "type": "draw", "source": "deck", "amount": 2, "key": "key.keyboard.d" } ] }
                """;
        String withSetKey = """
                { "zones": [ { "id": "deck", "kind": "stack", "scope": "shared", "rect": [0.2, 0.1, 0.6, 0.8] } ],
                  "initial": { "*": "deck", "cardtable:standard": "deck" },
                  "actions": [ { "id": "draw", "type": "draw", "source": "deck", "key": "key.keyboard.d" } ] }
                """;

        String baseLine = parse(base).canonicalLine();
        assertNotEqualsLine(baseLine, parse(withAnotherAction).canonicalLine());
        assertNotEqualsLine(baseLine, parse(withSetKey).canonicalLine());
        assertEquals(baseLine, parse(base).canonicalLine()); // deterministic
    }

    @Test
    void duplicateZoneIdsKeepFirstOccurrence()
    {
        CardDefinitionJsonCodec.ParsedLayout parsed = parse("""
                {
                  "zones": [
                    { "id": "deck", "kind": "stack", "scope": "shared", "rect": [0.0, 0.0, 0.5, 0.5] },
                    { "id": "deck", "kind": "stack", "scope": "shared", "rect": [0.5, 0.5, 0.5, 0.5] }
                  ],
                  "initial": { "*": "deck" }
                }
                """);
        // The duplicate entry is skipped as broken; the first declaration wins.
        assertNotNull(parsed);
        assertEquals(1, parsed.definition().zones().size());
        assertEquals(0.0F, parsed.definition()
                .zone(new ResourceLocation("cardtable", "my_tcg/deck")).x());
    }

    // Guards the JSON-text-component path of labels (nested structures, colors).
    @Test
    void labelsWithRichComponentsEncodeStable()
    {
        CardDefinitionJsonCodec.ParsedLayout first = parse("""
                {
                  "zones": [ { "id": "zone", "kind": "stack", "scope": "shared",
                               "rect": [0.0, 0.0, 0.5, 0.5], "label": {"text": "区", "color": "red", "bold": true} } ],
                  "initial": { "*": "zone" }
                }
                """);
        CardDefinitionJsonCodec.ParsedLayout second = parse("""
                {
                  "zones": [ { "id": "zone", "kind": "stack", "scope": "shared",
                               "rect": [0.0, 0.0, 0.5, 0.5], "label": {"bold": true, "color": "red", "text": "区"} } ],
                  "initial": { "*": "zone" }
                }
                """);
        assertNotNull(first);
        assertNotNull(second);
        // Labels are encoded as plain text, so both rich components collapse
        // to the same display string and their lines match; the encoded field
        // must stay separator-free.
        assertEquals(first.canonicalLine(), second.canonicalLine());
        String line = first.canonicalLine();
        assertTrue(line.contains("#%E5%8C%BA|"), "label must be percent-encoded: " + line);
        assertFalse(line.contains("|区|"), "raw text must never leak into the line");
    }

    private static void assertNotEqualsLine(String a, String b)
    {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }
}
