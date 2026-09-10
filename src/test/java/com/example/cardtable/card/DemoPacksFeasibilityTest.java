package com.example.cardtable.card;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.api.CardRegistry;
import com.example.cardtable.api.RegisterCardDefinitionsEvent;
import com.example.cardtable.api.TableActionDefinition;
import com.example.cardtable.api.TableLayoutDefinition;
import com.example.cardtable.api.ZoneDefinition;
import com.example.cardtable.content.CardDefinitionJsonCodec;
import com.example.cardtable.table.TableGroupState;
import com.example.cardtable.table.TableSectionState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feasibility proof for the empty-table refactor: two real packs — a classic
 * poker shape and a deliberately alien battle shape — are loaded from the
 * classpath through the production pipeline and driven through the production
 * deck-load and action primitives (no Minecraft bootstrap). The core must
 * neither know nor care that the two tables look nothing alike.
 */
class DemoPacksFeasibilityTest
{
    private static final String POKER = "demo_poker";
    private static final String BATTLE = "demo_battle";

    // Pack loading ------------------------------------------------------------

    /** Pack JSON straight off the classpath; shared with the other core tests. */
    @Nullable
    static JsonElement readJson(String pack, String file)
    {
        try (InputStream stream = DemoPacksFeasibilityTest.class.getResourceAsStream(
                "/assets/cardtable/cardpacks/" + pack + "/" + file))
        {
            if (stream == null)
            {
                return null;
            }
            return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch (IOException exception)
        {
            throw new UncheckedIOException(exception);
        }
    }

    /** The exact classpath pipeline of {@code ContentPackLoader.loadBuiltinPack}. */
    private static void loadRegistry(String pack)
    {
        CardDefinitionJsonCodec.PackMeta meta = CardDefinitionJsonCodec.parsePackMeta(
                readJson(pack, "pack.json").getAsJsonObject());
        CardDefinitionJsonCodec.ParsedLayout layout = CardDefinitionJsonCodec.parseLayout(
                readJson(pack, "layout.json"), meta, NOPLogger.NOP_LOGGER);
        assertNotNull(layout, pack + " must ship a usable layout.json");

        RegisterCardDefinitionsEvent event = new RegisterCardDefinitionsEvent();
        event.register(layout.definition());
        CardDefinitionJsonCodec.registerSet(meta, relative -> new ResourceLocation(
                CardTableMod.MODID, "card/" + meta.id().getPath() + "/" + relative),
                meta.id(), event::register);

        JsonArray cards = readJson(pack, "cards.json").getAsJsonArray();
        for (JsonElement element : cards)
        {
            CardDefinitionJsonCodec.ParsedCard parsed = CardDefinitionJsonCodec.parseCard(
                    element.getAsJsonObject(), meta, relative -> new ResourceLocation(
                            CardTableMod.MODID, "card/" + meta.id().getPath() + "/" + relative));
            event.register(parsed.definition());
        }
        CardRegistry.load(event.cardsSnapshot(), event.setsSnapshot(), event.layoutsSnapshot());
    }

    private static TableLayoutDefinition normalizedLayout(String pack)
    {
        loadRegistry(pack);
        ResourceLocation layoutId = CardRegistry.allSets().iterator().next().layout();
        assertNotNull(layoutId, "the pack set must bind its pack layout");
        TableLayoutDefinition layout = CardRegistry.getLayout(layoutId);
        assertNotNull(layout, "the pack layout must be registered");
        return layout.normalized();
    }

    private static TableGroupState loadedTable(String pack, TableLayoutDefinition layout)
    {
        TableGroupState groupState = TableGroupState.create();
        ResourceLocation deckId = CardRegistry.allSets().iterator().next().id();
        assertTrue(DeckService.loadDeck(groupState, deckId, layout),
                "the pack layout must provide a stock pile for the whole deck");
        return groupState;
    }

    private static boolean perform(TableLayoutDefinition layout, TableGroupState groupState,
                                   TableSectionState seat, UUID actorId, TableActionDefinition action,
                                   @Nullable UUID instanceId, List<TableSectionState> sections)
    {
        return CardActionService.applyDeclaredAction(layout, groupState, seat, actorId, action,
                instanceId, RandomSource.create(42L), sections);
    }

    // Pack A: demo_poker, the classic shape -----------------------------------

    @Test
    void classicPackStillPlaysLikeTheOldTable()
    {
        TableLayoutDefinition layout = normalizedLayout(POKER);
        ResourceLocation deckId = CardRegistry.allSets().iterator().next().id();

        // Convention one: both declared stacks are piles. No builtin pile ids
        // anywhere, and the blank surface is core state, not a layout zone.
        assertEquals(2, layout.zones().size());
        assertEquals(2, layout.pileZones().size());
        assertNull(layout.zone(TableLayoutDefinition.ZONE_FREE));
        assertNull(layout.zone(new ResourceLocation("cardtable", "draw_pile")));
        assertNull(layout.zone(new ResourceLocation("cardtable", "discard_pile")));

        // Convention two: the whole deck instantiates into the declared stock.
        TableSectionState seat = new TableSectionState();
        UUID actorId = UUID.randomUUID();
        seat.setOccupant(actorId);
        TableGroupState groupState = loadedTable(POKER, layout);
        ResourceLocation deckZone = layout.initialZoneFor(deckId).id();
        assertEquals(10, groupState.getSharedZones().get(deckZone).stackCards().size());
        assertEquals(0, groupState.getSharedZones().get(
                new ResourceLocation("cardtable", "demo_poker/discard")).stackCards().size());

        // Draw 1 (bound to D): pile top into the actor's own hand.
        TableActionDefinition draw = actionById(layout, "draw");
        assertEquals("key.keyboard.d", draw.key());
        assertTrue(perform(layout, groupState, seat, actorId, draw, null, List.of(seat)));
        assertEquals(1, seat.getHand().size());
        assertEquals(9, groupState.getSharedZones().get(deckZone).stackCards().size());

        // Shuffle (bound to S): same cards, pile size unchanged.
        TableActionDefinition shuffle = actionById(layout, "shuffle");
        assertEquals("key.keyboard.s", shuffle.key());
        Set<UUID> before = pileInstanceIds(groupState, deckZone);
        assertTrue(perform(layout, groupState, seat, actorId, shuffle, null, List.of(seat)));
        assertEquals(9, groupState.getSharedZones().get(deckZone).stackCards().size());
        assertEquals(before, pileInstanceIds(groupState, deckZone));

        // Flip (bound to F): flips a card lying on the table, not a hand one.
        // A hand is hidden information its owner already reads, so flipping
        // there has no public face to change and is refused outright.
        CardInstance handCard = seat.getHand().get(0);
        assertFalse(perform(layout, groupState, seat, actorId, actionById(layout, "flip"),
                handCard.instanceId(), List.of(seat)), "a hand card has no public face to flip");
        assertFalse(handCard.isFaceUp());

        assertNotNull(seat.removeHandCard(handCard.instanceId()));
        groupState.getSurface().addPlaced(handCard, 0.5F, 0.5F);
        assertTrue(perform(layout, groupState, seat, actorId, actionById(layout, "flip"),
                handCard.instanceId(), List.of(seat)));
        assertTrue(handCard.isFaceUp());

        // Taking the deck out resets the layout state but never the surface:
        // the blank table outlives the deck, its cards stay where they are.
        groupState.resetLayoutState();
        assertTrue(groupState.getSharedZones().isEmpty());
        assertNull(groupState.getActiveLayoutId());
        assertEquals(1, groupState.getSurface().size(),
                "the surface is independent of the deck lifecycle");
    }

    // Pack B: demo_battle, the alien shape ------------------------------------

    @Test
    void alienPackGrowsWhateverItDeclaresWithoutCoreSpecialCases()
    {
        TableLayoutDefinition layout = normalizedLayout(BATTLE);
        ResourceLocation deckId = CardRegistry.allSets().iterator().next().id();

        // Three declared zones (two piles, one grid) — none of them builtin
        // ids, all of them pack-owned names. The blank surface is core state.
        assertEquals(3, layout.zones().size());
        assertEquals(2, layout.pileZones().size());
        assertEquals(5, layout.zone(new ResourceLocation("cardtable", "demo_battle/market")).capacity());
        assertNull(layout.zone(TableLayoutDefinition.ZONE_FREE));

        // Seats no longer carry zone containers; every declared zone has one
        // group-level instance.
        TableSectionState seatA = new TableSectionState();
        TableSectionState seatB = new TableSectionState();
        UUID actorId = UUID.randomUUID();
        seatA.setOccupant(actorId);
        TableGroupState groupState = loadedTable(BATTLE, layout);

        // The whole deck lands in the shared stock pile.
        ResourceLocation deckZone = layout.initialZoneFor(deckId).id();
        assertEquals(10, groupState.getSharedZones().get(deckZone).stackCards().size());
        // The market grid container exists once, group-level, and starts empty;
        // the blank surface starts empty too.
        assertNotNull(groupState.getSharedZones().get(
                new ResourceLocation("cardtable", "demo_battle/market")));
        assertTrue(groupState.getSharedZones().get(
                new ResourceLocation("cardtable", "demo_battle/market")).isEmpty());
        assertTrue(groupState.getSurface().isEmpty());

        // Draw 2 (bound to G, not D): the pack picks its own keys.
        TableActionDefinition deal = actionById(layout, "deal");
        assertEquals("key.keyboard.g", deal.key());
        assertEquals(2, deal.amount());
        assertTrue(perform(layout, groupState, seatA, actorId, deal, null, List.of(seatA, seatB)));
        assertEquals(2, seatA.getHand().size());
        assertEquals(8, groupState.getSharedZones().get(deckZone).stackCards().size());
        // The other seat's containers are untouched.
        assertEquals(0, seatB.getHand().size());

        // Shuffle (bound to H).
        TableActionDefinition shuffle = actionById(layout, "shuffle");
        assertEquals("key.keyboard.h", shuffle.key());
        Set<UUID> before = pileInstanceIds(groupState, deckZone);
        assertTrue(perform(layout, groupState, seatA, actorId, shuffle, null, List.of(seatA, seatB)));
        assertEquals(8, groupState.getSharedZones().get(deckZone).stackCards().size());
        assertEquals(before, pileInstanceIds(groupState, deckZone));
    }

    // Cross-pack determinism ---------------------------------------------------

    @Test
    void packCanonicalLinesAreDeterministicAcrossParses()
    {
        for (String pack : List.of(POKER, BATTLE, "standard"))
        {
            String first = CardDefinitionJsonCodec.parseLayout(
                    readJson(pack, "layout.json"),
                    CardDefinitionJsonCodec.parsePackMeta(readJson(pack, "pack.json").getAsJsonObject()),
                    NOPLogger.NOP_LOGGER).canonicalLine();
            String second = CardDefinitionJsonCodec.parseLayout(
                    readJson(pack, "layout.json"),
                    CardDefinitionJsonCodec.parsePackMeta(readJson(pack, "pack.json").getAsJsonObject()),
                    NOPLogger.NOP_LOGGER).canonicalLine();
            assertEquals(first, second, pack + " must hash identically on both peers");
            assertTrue(first.startsWith("layout4|"), pack + " uses the layout4 canonical format");
        }
    }

    @Test
    void theTwoPacksProduceDifferentTablesAndKeys()
    {
        TableLayoutDefinition poker = normalizedLayout(POKER);
        TableLayoutDefinition battle = normalizedLayout(BATTLE);

        assertNotEquals(poker.pileZones().size() + ":" + poker.zones().size(),
                battle.pileZones().size() + ":" + battle.zones().size());
        // Even the same primitive (draw) is bound to a different key per pack.
        assertNotEquals(actionById(poker, "draw").key(), actionById(battle, "deal").key());
    }

    // Helpers -----------------------------------------------------------------

    private static TableActionDefinition actionById(TableLayoutDefinition layout, String relativeId)
    {
        for (TableActionDefinition action : layout.actions())
        {
            if (action.id().getPath().endsWith("/" + relativeId))
            {
                return action;
            }
        }
        throw new AssertionError("missing action " + relativeId + " in " + layout.id());
    }

    private static Set<UUID> pileInstanceIds(TableGroupState groupState, ResourceLocation zoneId)
    {
        Set<UUID> ids = new HashSet<>();
        for (CardInstance card : groupState.getSharedZones().get(zoneId).stackCards())
        {
            ids.add(card.instanceId());
        }
        return ids;
    }
}
