package com.example.cardtable.card;

import com.example.cardtable.api.CardDefinition;
import com.example.cardtable.api.CardRegistry;
import com.example.cardtable.api.CardSetDefinition;
import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.item.DeckItem;
import com.example.cardtable.table.TableGroupService;
import com.example.cardtable.table.TableGroupState;
import com.example.cardtable.table.TableSectionState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Server-side authority for the table's deck slot: inserting a deck item
 * fills the group draw pile with one instance of every card in its set;
 * taking it back out atomically reclaims every card of that deck from every
 * zone (piles, surfaces and hidden hands) so a deck never leaves cards
 * stranded on the table.
 *
 * <p>All mutations bump the group version and go through
 * {@link TableGroupService#syncGroup}, reusing the existing block entity
 * sync path.</p>
 */
public final class DeckService
{
    private DeckService()
    {
    }

    /** Called when the deck slot goes from empty to holding a deck item. */
    public static boolean onDeckInserted(Level level, TableGroupService.GroupView group,
                                         ItemStack deckStack, @Nullable ServerPlayer actor)
    {
        if (level.isClientSide || !isAllowed(level, group, actor))
        {
            return false;
        }
        Optional<ResourceLocation> deckId = DeckItem.deckId(deckStack);
        if (deckId.isEmpty())
        {
            return false;
        }
        CardSetDefinition set = CardRegistry.getSet(deckId.get());
        if (set == null)
        {
            return false;
        }

        CardTableBlockEntity master = masterAt(level, group);
        if (master == null)
        {
            return false;
        }
        TableGroupState groupState = master.getGroupState();
        if (!groupState.getDeckStack().isEmpty())
        {
            return false; // One deck at a time; the slot state is authoritative.
        }

        // Cards enter face-down in set order; the pile top is the last entry.
        for (CardDefinition definition : CardRegistry.cardsInSet(deckId.get()))
        {
            groupState.getDrawPile().add(new CardInstance(definition.id()));
        }
        groupState.setDeckStack(deckStack.copy());
        groupState.bumpVersion();
        TableGroupService.syncGroup(level, group);
        return true;
    }

    /**
     * Called when the deck slot loses its deck item. Reclaims every instance
     * belonging to that deck from all zones in one server-side transaction
     * before syncing, so no intermediate state can reach the clients.
     */
    public static boolean onDeckRemoved(Level level, TableGroupService.GroupView group,
                                        @Nullable ServerPlayer actor)
    {
        if (level.isClientSide || !isAllowed(level, group, actor))
        {
            return false;
        }
        CardTableBlockEntity master = masterAt(level, group);
        if (master == null)
        {
            return false;
        }
        TableGroupState groupState = master.getGroupState();
        ItemStack removedDeck = groupState.getDeckStack();
        if (removedDeck.isEmpty())
        {
            return false;
        }
        Optional<ResourceLocation> deckId = DeckItem.deckId(removedDeck);
        Set<ResourceLocation> reclaimedIds = deckId.isPresent()
                ? idsOfSet(deckId.get()) : Set.of();

        groupState.getDrawPile().removeIf(card -> reclaimedIds.contains(card.definitionId()));
        groupState.getDiscardPile().removeIf(card -> reclaimedIds.contains(card.definitionId()));

        for (var pos : group.positions())
        {
            if (level.getBlockEntity(pos) instanceof CardTableBlockEntity section)
            {
                TableSectionState sectionState = section.getSectionState();
                sectionState.getSurface().cards().stream()
                        .filter(entry -> reclaimedIds.contains(entry.card().definitionId()))
                        .map(entry -> entry.card().instanceId())
                        .forEach(sectionState.getSurface()::remove);
                sectionState.getHand().removeIf(card -> reclaimedIds.contains(card.definitionId()));
            }
        }

        groupState.setDeckStack(ItemStack.EMPTY);
        groupState.bumpVersion();
        TableGroupService.syncGroup(level, group);
        // Hands may have lost reclaimed cards; re-push every occupant's hand.
        HandSyncService.pushAll(level, group);
        return true;
    }

    /** Whether {@code actor} may operate the deck slot; must be seated (or system-initiated). */
    private static boolean isAllowed(Level level, TableGroupService.GroupView group, @Nullable ServerPlayer actor)
    {
        return actor == null || TableGroupService.isSeated(level, group.masterPos(), actor);
    }

    @Nullable
    private static CardTableBlockEntity masterAt(Level level, TableGroupService.GroupView group)
    {
        return level.getBlockEntity(group.masterPos()) instanceof CardTableBlockEntity master ? master : null;
    }

    private static Set<ResourceLocation> idsOfSet(ResourceLocation setId)
    {
        Set<ResourceLocation> ids = new HashSet<>();
        for (CardDefinition definition : CardRegistry.cardsInSet(setId))
        {
            ids.add(definition.id());
        }
        return ids;
    }
}
