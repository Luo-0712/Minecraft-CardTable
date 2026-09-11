package com.example.cardtable.card;

import com.example.cardtable.api.CardDefinition;
import com.example.cardtable.api.CardRegistry;
import com.example.cardtable.api.CardSetDefinition;
import com.example.cardtable.api.RegisterCardDefinitionsEvent;
import com.example.cardtable.api.TableActionDefinition;
import com.example.cardtable.api.TableLayoutDefinition;
import com.example.cardtable.api.ZoneDefinition;
import com.example.cardtable.table.TableGroupState;
import com.example.cardtable.table.TableSectionState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private static final ResourceLocation LAYOUT_ID = new ResourceLocation("cardtable", "face_state_test");
    private static final ResourceLocation DECK_ID = new ResourceLocation("cardtable", "face_state_test/deck");
    private static final ResourceLocation SET_ID = new ResourceLocation("cardtable", "face_state_test");
    private static final ResourceLocation CARD_ID = new ResourceLocation("cardtable", "face_state_test/ace");

    // Drawing ----------------------------------------------------------------

    @Test
    void aDrawnCardIsFaceDownInTheOwnerHand()
    {
        TableSectionState seat = occupiedSeat();
        CardInstance drawn = new CardInstance(CARD_ID);
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
        assertTrue(table.seat.removeHandCard(table.handCard.instanceId()) != null);
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
        CardInstance card = new CardInstance(CARD_ID);
        CardActionService.applyPlayOrientation(card, true, SURFACE, false, 0);
        assertTrue(card.isFaceUp(), "playing from hand reveals the card by default");
    }

    @Test
    void aHandCardCanBePlayedFaceDownOnRequest()
    {
        CardInstance card = new CardInstance(CARD_ID);
        CardActionService.applyPlayOrientation(card, true, SURFACE, true, 0);
        assertFalse(card.isFaceUp(), "shift-drop must keep the played card hidden");

        CardActionService.applyPlayOrientation(card, true, DECK_ID, true, 0);
        assertFalse(card.isFaceUp(), "the same holds for a declared zone");
    }

    @Test
    void aHandCardLandsWithTheActorsPlayRotation()
    {
        CardInstance card = new CardInstance(CARD_ID);
        CardActionService.applyPlayOrientation(card, true, SURFACE, false, 270);
        assertEquals(270, card.rotation(),
                "a card leaving hand must land upright on the actor's rotated view");
    }

    @Test
    void movingACardThatIsNotInHandKeepsItsFaceAndRotation()
    {
        CardInstance faceUp = new CardInstance(CARD_ID);
        faceUp.setFaceUp(true);
        faceUp.setRotation(90);
        CardActionService.applyPlayOrientation(faceUp, false, SURFACE, true, 0);
        assertTrue(faceUp.isFaceUp(), "a revealed table card stays revealed when moved");
        assertEquals(90, faceUp.rotation(), "table-to-table moves never reorient the card");

        CardInstance faceDown = new CardInstance(new ResourceLocation("cardtable", "face_state_test/hearts"));
        CardActionService.applyPlayOrientation(faceDown, false, SURFACE, false, 180);
        assertFalse(faceDown.isFaceUp(), "moving a table card must never reveal it");
        assertEquals(0, faceDown.rotation(), "an untouched rotation stays untouched");
    }

    @Test
    void returningACardToHandIsNeverAReveal()
    {
        CardInstance card = new CardInstance(CARD_ID);
        card.setRotation(90);
        CardActionService.applyPlayOrientation(card, true, HAND, false, 0);
        assertFalse(card.isFaceUp(), "picking a card back up keeps its face for the table's sake");
        assertEquals(90, card.rotation(), "returning to hand must not touch rotation either");
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

    /** A programmatic one-card table with draw/flip already wired — no pack files. */
    private static SeatedTable seatedTableWithDrawnCard()
    {
        ResourceLocation drawId = new ResourceLocation("cardtable", "face_state_test/draw");
        ResourceLocation flipId = new ResourceLocation("cardtable", "face_state_test/flip");
        TableLayoutDefinition layout = TableLayoutDefinition.builder(LAYOUT_ID)
                .displayName(Component.literal("Face state test table"))
                .zone(ZoneDefinition.builder(DECK_ID)
                        .kind(ZoneDefinition.Kind.STACK)
                        .rect(0.02F, 0.02F, 0.08F, 0.14F)
                        .capacity(4)
                        .build())
                .initial(TableLayoutDefinition.INITIAL_DEFAULT_KEY, DECK_ID)
                .action(TableActionDefinition.builder(drawId)
                        .type(TableActionDefinition.Type.DRAW)
                        .sourceZone(DECK_ID)
                        .amount(1)
                        .key("key.keyboard.d")
                        .build())
                .action(TableActionDefinition.builder(flipId)
                        .type(TableActionDefinition.Type.FLIP)
                        .key("key.keyboard.f")
                        .build())
                .build()
                .normalized();

        RegisterCardDefinitionsEvent event = new RegisterCardDefinitionsEvent();
        event.register(layout);
        event.register(CardSetDefinition.builder(SET_ID)
                .displayName(Component.literal("Face state test set"))
                .layout(layout.id())
                .build());
        event.register(CardDefinition.builder(CARD_ID)
                .displayName(Component.literal("Ace"))
                .frontTexture(new ResourceLocation("cardtable", "card/default_back"))
                .backTexture(new ResourceLocation("cardtable", "card/default_back"))
                .cardSet(SET_ID)
                .sortIndex(0)
                .build());
        CardRegistry.load(event.cardsSnapshot(), event.setsSnapshot(), event.layoutsSnapshot());

        TableSectionState seat = new TableSectionState();
        UUID actorId = UUID.randomUUID();
        seat.setOccupant(actorId);
        TableGroupState groupState = TableGroupState.create();
        assertTrue(DeckService.loadDeck(groupState, SET_ID, layout),
                "the fixture layout must provide a stock pile");

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
