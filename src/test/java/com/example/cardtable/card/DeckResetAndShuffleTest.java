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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the two pile-reset contracts the table needed for real play: a
 * freshly inserted deck is already shuffled, and the RESET primitive walks
 * every card of the active set back into the stock pile in set order.
 */
class DeckResetAndShuffleTest
{
    private static final ResourceLocation LAYOUT_ID = new ResourceLocation("cardtable", "reset_test");
    private static final ResourceLocation SET_ID = new ResourceLocation("cardtable", "reset_test");
    private static final ResourceLocation DECK_ID = new ResourceLocation("cardtable", "reset_test/deck");
    private static final ResourceLocation DISCARD_ID = new ResourceLocation("cardtable", "reset_test/discard");
    private static final ResourceLocation SHUFFLE_ID = new ResourceLocation("cardtable", "reset_test/shuffle");
    private static final ResourceLocation RESET_ID = new ResourceLocation("cardtable", "reset_test/reset");

    private TableLayoutDefinition layout;
    private List<ResourceLocation> setOrder;

    @BeforeEach
    void registerContent()
    {
        this.layout = TableLayoutDefinition.builder(LAYOUT_ID)
                .displayName(Component.literal("Reset test table"))
                .zone(ZoneDefinition.builder(DECK_ID)
                        .kind(ZoneDefinition.Kind.STACK)
                        .rect(0.02F, 0.02F, 0.08F, 0.14F)
                        .build())
                .zone(ZoneDefinition.builder(DISCARD_ID)
                        .kind(ZoneDefinition.Kind.STACK)
                        .rect(0.90F, 0.86F, 0.08F, 0.14F)
                        .build())
                .initial(TableLayoutDefinition.INITIAL_DEFAULT_KEY, DECK_ID)
                .action(TableActionDefinition.builder(SHUFFLE_ID)
                        .type(TableActionDefinition.Type.SHUFFLE)
                        .sourceZone(DECK_ID)
                        .key("key.keyboard.s")
                        .build())
                .action(TableActionDefinition.builder(RESET_ID)
                        .type(TableActionDefinition.Type.RESET)
                        .sourceZone(DECK_ID)
                        .key("key.keyboard.c")
                        .build())
                .build()
                .normalized();

        List<CardDefinition> cards = new ArrayList<>();
        for (int index = 0; index < 8; index++)
        {
            cards.add(CardDefinition.builder(new ResourceLocation("cardtable", "reset_test/card_" + index))
                    .displayName(Component.literal("Card " + index))
                    .frontTexture(new ResourceLocation("cardtable", "card/default_back"))
                    .backTexture(new ResourceLocation("cardtable", "card/default_back"))
                    .cardSet(SET_ID)
                    .sortIndex(index)
                    .build());
        }
        RegisterCardDefinitionsEvent event = new RegisterCardDefinitionsEvent();
        event.register(this.layout);
        event.register(CardSetDefinition.builder(SET_ID)
                .displayName(Component.literal("Reset test set"))
                .layout(this.layout.id())
                .build());
        cards.forEach(event::register);
        CardRegistry.load(event.cardsSnapshot(), event.setsSnapshot(), event.layoutsSnapshot());
        this.setOrder = cards.stream().map(CardDefinition::id).toList();
    }

    @Test
    void insertingADeckShufflesTheStockPile()
    {
        TableGroupState groupState = TableGroupState.create();
        assertTrue(DeckService.loadDeck(groupState, SET_ID, this.layout, RandomSource.create(42L)));
        List<ResourceLocation> stock = stockOrder(groupState);
        assertEquals(this.setOrder.size(), stock.size(), "the stock pile must hold one of every card of the set");
        assertTrue(stock.containsAll(this.setOrder));
        assertNotEquals(this.setOrder, stock,
                "a freshly inserted deck must not keep set order — it is shuffled");
    }

    @Test
    void resetGathersScatteredCardsBackIntoSetOrder()
    {
        TableGroupState groupState = TableGroupState.create();
        assertTrue(DeckService.loadDeck(groupState, SET_ID, this.layout));
        // Deterministic unshuffled start: stock is already in set order.

        TableSectionState seatA = occupiedSeat();
        TableSectionState seatB = occupiedSeat();
        ZoneState stock = groupState.getSharedZones().get(DECK_ID);
        ZoneState discard = groupState.getSharedZones().get(DISCARD_ID);

        CardInstance inHand = requireCard(stock.takeFromStackTop());
        inHand.setFaceUp(true);
        inHand.setRotation(90);
        seatA.addHandCard(inHand);

        CardInstance onTable = requireCard(stock.takeFromStackTop());
        onTable.setFaceUp(true);
        groupState.getSurface().addPlaced(onTable, 0.4F, 0.5F);

        CardInstance inDiscard = requireCard(stock.takeFromStackTop());
        inDiscard.setFaceUp(true);
        discard.addToStackTop(inDiscard);

        assertTrue(CardActionService.applyDeclaredAction(this.layout, groupState, seatA, seatA.getOccupantId(),
                action(RESET_ID), null, RandomSource.create(1L), List.of(seatA, seatB)));

        assertEquals(this.setOrder, stockOrder(groupState), "reset restores set order into the stock pile");
        assertTrue(discard.isEmpty(), "the discard pile is emptied by reset");
        assertTrue(seatA.getHand().isEmpty(), "hands are emptied by reset");
        assertTrue(groupState.getSurface().isEmpty(), "the blank surface is emptied by reset");
        for (CardInstance card : stock.stackCards())
        {
            assertFalse(card.isFaceUp(), "reset returns every card face-down");
            assertEquals(0, card.rotation(), "reset clears table rotation");
        }
    }

    @Test
    void shuffleKeepsEveryCardExactlyOnce()
    {
        TableGroupState groupState = TableGroupState.create();
        assertTrue(DeckService.loadDeck(groupState, SET_ID, this.layout));
        List<ResourceLocation> before = stockOrder(groupState);
        assertTrue(CardActionService.applyDeclaredAction(this.layout, groupState, null, null,
                action(SHUFFLE_ID), null, RandomSource.create(7L), List.of()));
        List<ResourceLocation> after = stockOrder(groupState);
        assertEquals(before.size(), after.size());
        assertTrue(after.containsAll(before), "shuffle may only reorder, never drop or invent cards");
        assertNotEquals(before, after, "with eight cards a fixed seed should move the order");
    }

    // Fixtures ---------------------------------------------------------------

    private static TableSectionState occupiedSeat()
    {
        TableSectionState seat = new TableSectionState();
        seat.setOccupant(UUID.randomUUID());
        return seat;
    }

    private static TableActionDefinition action(ResourceLocation id)
    {
        return CardRegistry.getLayout(LAYOUT_ID).actions().stream()
                .filter(entry -> entry.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static List<ResourceLocation> stockOrder(TableGroupState groupState)
    {
        return groupState.getSharedZones().get(DECK_ID).stackCards().stream()
                .map(CardInstance::definitionId)
                .toList();
    }

    private static CardInstance requireCard(CardInstance card)
    {
        assertTrue(card != null, "the fixture pile must still hold cards");
        return card;
    }
}
