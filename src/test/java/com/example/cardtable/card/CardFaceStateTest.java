package com.example.cardtable.card;

import com.example.cardtable.api.CardRegistry;
import com.example.cardtable.api.RegisterCardDefinitionsEvent;
import com.example.cardtable.api.TableActionDefinition;
import com.example.cardtable.api.TableLayoutDefinition;
import com.example.cardtable.content.CardDefinitionJsonCodec;
import com.example.cardtable.table.TableGroupState;
import com.example.cardtable.table.TableSectionState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the face-state contract that the external-pack refactor broke: a card
 * is drawn face-down and read by its owner in hand, played face-up by default
 * (or face-down on request), and only ever flipped for real while it lies on
 * the table. The owner's view and the table's orientation are different
 * things; these tests keep them apart.
 */
class CardFaceStateTest
{
    private static final ResourceLocation SURFACE = TableLayoutDefinition.ZONE_FREE;
    private static final ResourceLocation HAND = TableLayoutDefinition.ZONE_HAND;
    private static final ResourceLocation BENCH = new ResourceLocation("cardtable", "demo_poker/bench");

    // Drawing ----------------------------------------------------------------

    @Test
    void aDrawnCardIsFaceDownInTheOwnerHand()
    {
        TableSectionState seat = occupiedSeat();
        CardInstance drawn = new CardInstance(new ResourceLocation("cardtable", "standard/ace_of_spades"));
        seat.addHandCard(drawn);
        assertFalse(drawn.isFaceUp(), "a card enters the hidden hand face-down");
    }

    // Flipping ---------------------------------------------------------------

    @Test
    void flippingAHandCardIsRefused()
    {
        SeatedTable table = seatedTableWithDrawnCard();
        assertFalse(flip(table, table.handCard.instanceId()),
                "a hand has no public face: flipping in hand must not touch the state");
        assertFalse(table.handCard.isFaceUp());
    }

    @Test
    void flippingACardOnTheTableStillWorks()
    {
        SeatedTable table = seatedTableWithDrawnCard();
        assertNotNull(table.seat.removeHandCard(table.handCard.instanceId()));
        table.groupState().getSurface().addPlaced(table.handCard, 0.5F, 0.5F);

        assertTrue(flip(table, table.handCard.instanceId()));
        assertTrue(table.handCard.isFaceUp());
        assertTrue(flip(table, table.handCard.instanceId()), "a table card may be flipped back");
        assertFalse(table.handCard.isFaceUp());
    }

    // Playing ----------------------------------------------------------------

    @Test
    void aHandCardIsPlayedFaceUpByDefault()
    {
        CardInstance card = new CardInstance(new ResourceLocation("cardtable", "standard/ace_of_spades"));
        CardActionService.applyPlayOrientation(card, true, SURFACE, false);
        assertTrue(card.isFaceUp(), "playing from hand reveals the card by default");
    }

    @Test
    void aHandCardCanBePlayedFaceDownOnRequest()
    {
        CardInstance card = new CardInstance(new ResourceLocation("cardtable", "standard/ace_of_spades"));
        CardActionService.applyPlayOrientation(card, true, SURFACE, true);
        assertFalse(card.isFaceUp(), "shift-drop must keep the played card hidden");

        CardActionService.applyPlayOrientation(card, true, BENCH, true);
        assertFalse(card.isFaceUp(), "the same holds for a declared zone");
    }

    @Test
    void movingACardThatIsNotInHandKeepsItsFace()
    {
        CardInstance faceUp = new CardInstance(new ResourceLocation("cardtable", "standard/ace_of_spades"));
        faceUp.setFaceUp(true);
        CardActionService.applyPlayOrientation(faceUp, false, SURFACE, true);
        assertTrue(faceUp.isFaceUp(), "a revealed table card stays revealed when moved");

        CardInstance faceDown = new CardInstance(new ResourceLocation("cardtable", "standard/ace_of_hearts"));
        CardActionService.applyPlayOrientation(faceDown, false, SURFACE, false);
        assertFalse(faceDown.isFaceUp(), "moving a table card must never reveal it");
    }

    @Test
    void returningACardToHandIsNeverAReveal()
    {
        CardInstance card = new CardInstance(new ResourceLocation("cardtable", "standard/ace_of_spades"));
        CardActionService.applyPlayOrientation(card, true, HAND, false);
        assertFalse(card.isFaceUp(), "picking a card back up keeps its face for the table's sake");
    }

    // Fixtures ---------------------------------------------------------------

    private record SeatedTable(TableLayoutDefinition layout, TableGroupState groupState,
                               TableSectionState seat, UUID actorId, CardInstance handCard)
    {
    }

    private static TableSectionState occupiedSeat()
    {
        TableSectionState seat = new TableSectionState();
        seat.setOccupant(UUID.randomUUID());
        return seat;
    }

    /** A real pack-driven table with one card already drawn into the seat's hand. */
    private static SeatedTable seatedTableWithDrawnCard()
    {
        String pack = "demo_poker";
        CardDefinitionJsonCodec.PackMeta meta = CardDefinitionJsonCodec.parsePackMeta(
                DemoPacksFeasibilityTest.readJson(pack, "pack.json").getAsJsonObject());
        CardDefinitionJsonCodec.ParsedLayout parsed = CardDefinitionJsonCodec.parseLayout(
                DemoPacksFeasibilityTest.readJson(pack, "layout.json"), meta, NOPLogger.NOP_LOGGER);
        assertNotNull(parsed, pack + " must ship a usable layout.json");

        RegisterCardDefinitionsEvent event = new RegisterCardDefinitionsEvent();
        event.register(parsed.definition());
        CardDefinitionJsonCodec.registerSet(meta, relative -> new ResourceLocation(
                        "cardtable", "card/" + meta.id().getPath() + "/" + relative),
                meta.id(), event::register);
        JsonArray cards = DemoPacksFeasibilityTest.readJson(pack, "cards.json").getAsJsonArray();
        for (JsonElement element : cards)
        {
            event.register(CardDefinitionJsonCodec.parseCard(element.getAsJsonObject(), meta,
                    relative -> new ResourceLocation("cardtable", "card/" + meta.id().getPath() + "/" + relative))
                    .definition());
        }
        CardRegistry.load(event.cardsSnapshot(), event.setsSnapshot(), event.layoutsSnapshot());

        TableLayoutDefinition layout = parsed.definition().normalized();
        TableSectionState seat = new TableSectionState();
        UUID actorId = UUID.randomUUID();
        seat.setOccupant(actorId);
        TableGroupState groupState = TableGroupState.create();
        assertTrue(DeckService.loadDeck(groupState, meta.id(), layout),
                "the pack layout must provide a stock pile");

        TableActionDefinition draw = layout.actions().stream()
                .filter(action -> action.type() == TableActionDefinition.Type.DRAW)
                .findFirst().orElseThrow();
        assertTrue(CardActionService.applyDeclaredAction(layout, groupState, seat, actorId, draw, null,
                RandomSource.create(42L), List.of(seat)));
        return new SeatedTable(layout, groupState, seat, actorId, seat.getHand().get(0));
    }

    private static boolean flip(SeatedTable table, UUID instanceId)
    {
        TableActionDefinition flip = table.layout().actions().stream()
                .filter(action -> action.type() == TableActionDefinition.Type.FLIP)
                .findFirst().orElseThrow();
        return CardActionService.applyDeclaredAction(table.layout(), table.groupState(), table.seat(),
                table.actorId(), flip, instanceId, RandomSource.create(42L), List.of(table.seat()));
    }
}
