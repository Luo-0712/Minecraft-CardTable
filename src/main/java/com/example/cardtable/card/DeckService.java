package com.example.cardtable.card;

import com.example.cardtable.api.CardDefinition;
import com.example.cardtable.api.CardRegistry;
import com.example.cardtable.api.CardSetDefinition;
import com.example.cardtable.api.TableLayoutDefinition;
import com.example.cardtable.api.ZoneDefinition;
import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.item.DeckItem;
import com.example.cardtable.table.TableGroupService;
import com.example.cardtable.table.TableGroupState;
import com.example.cardtable.table.TableSectionState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Server-side authority for the table's deck slot: inserting a deck item
 * binds its set's layout, instantiates every zone the layout declares (the
 * kind == STACK convention marks the piles) and loads one instance of every
 * card into the layout's stock pile; taking the deck back out atomically
 * reclaims every card of that deck from every zone (piles, surfaces and
 * hidden hands) so a deck never leaves cards stranded on the table.
 *
 * <p>All mutations bump the group version and go through
 * {@link TableGroupService#syncGroup}, reusing the existing block entity
 * sync path.</p>
 */
public final class DeckService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DeckService.class);

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

        // Layout binding: the set decides the layout, and a set whose layout
        // is missing or unregistered is rejected — the core ships no built-in
        // fallback layout, so there is nothing sensible to degrade to.
        ResourceLocation layoutId = set.layout();
        TableLayoutDefinition layout = layoutId == null ? null : CardRegistry.getLayout(layoutId);
        if (layout == null)
        {
            LOGGER.warn("Deck insert rejected: set {} references unknown layout {}", set.id(), layoutId);
            notifyActor(actor, "gui.cardtable.deck_layout_missing", set.id());
            return false;
        }
        TableLayoutDefinition normalizedLayout = layout.normalized();
        if (normalizedLayout.initialZoneFor(deckId.get()) == null)
        {
            LOGGER.warn("Deck insert rejected: layout {} declares no usable stock pile",
                    normalizedLayout.id());
            notifyActor(actor, "gui.cardtable.deck_stock_missing", normalizedLayout.id());
            return false;
        }

        if (!loadDeck(groupState, deckId.get(), normalizedLayout, level.getRandom()))
        {
            return false;
        }
        groupState.setDeckStack(deckStack.copy());
        groupState.bumpVersion();
        TableGroupService.syncGroup(level, group);
        return true;
    }

    /**
     * Pure core of the deck insert, shared with the feasibility test: binds
     * the set/layout, instantiates one group-level container for every
     * declared zone per the structural conventions and loads the whole deck
     * face-down into the layout's stock pile (set order, then shuffled when a
     * random source is given; the pile top is the last entry).
     *
     * @param random when non-null, the stock pile is shuffled after loading so
     *               a freshly inserted deck is already randomized
     * @return {@code false} when the layout names no stock pile or the state
     *         diverges from the layout (nothing is mutated then)
     */
    static boolean loadDeck(TableGroupState groupState, ResourceLocation deckId,
                            TableLayoutDefinition normalizedLayout)
    {
        return loadDeck(groupState, deckId, normalizedLayout, null);
    }

    static boolean loadDeck(TableGroupState groupState, ResourceLocation deckId,
                            TableLayoutDefinition normalizedLayout, @Nullable RandomSource random)
    {
        ZoneDefinition stock = normalizedLayout.initialZoneFor(deckId);
        if (stock == null)
        {
            return false; // the whole deck needs one pile to land in
        }
        groupState.setLayoutBinding(deckId, normalizedLayout.id());
        instantiateZoneContainers(groupState, normalizedLayout);
        ZoneState container = groupState.getSharedZones().get(stock.id());
        if (container == null || container.storage() != ZoneState.Storage.STACK)
        {
            return false; // state/layout divergence: stay safe
        }
        for (CardDefinition definition : CardRegistry.cardsInSet(deckId))
        {
            container.addToStackTop(new CardInstance(definition.id()));
        }
        if (random != null)
        {
            container.shuffle(random);
        }
        return true;
    }

    /**
     * Instantiates the generic zone containers for the active layout. Every
     * zone is a group-level instance; the reserved hand/surface ids keep
     * their dedicated containers and are never created here.
     */
    private static void instantiateZoneContainers(TableGroupState groupState,
                                                  TableLayoutDefinition normalizedLayout)
    {
        for (ZoneDefinition zone : normalizedLayout.zones())
        {
            if (TableLayoutDefinition.ZONE_FREE.equals(zone.id())
                    || TableLayoutDefinition.ZONE_HAND.equals(zone.id()))
            {
                continue; // reserved ids keep their dedicated containers
            }
            groupState.getSharedZones().put(zone.id(), ZoneState.stackFor(zone.kind()));
        }
    }

    /** Surface hint for a rejected deck insert; the server log stays authoritative. */
    private static void notifyActor(@Nullable ServerPlayer actor, String key, Object... args)
    {
        if (actor != null)
        {
            actor.sendSystemMessage(Component.translatable(key, args));
        }
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

        for (var pos : group.positions())
        {
            if (level.getBlockEntity(pos) instanceof CardTableBlockEntity section)
            {
                TableSectionState sectionState = section.getSectionState();
                sectionState.getHand().removeIf(card -> reclaimedIds.contains(card.definitionId()));
            }
        }

        // The blank surface and the declared zones are group-level; their
        // cards of this deck are reclaimed too (the surface itself survives).
        groupState.getSurface().removeIfDefinition(reclaimedIds);
        groupState.getSharedZones().values().forEach(zone -> zone.removeIfDefinition(reclaimedIds));

        groupState.setDeckStack(ItemStack.EMPTY);
        // Taking the deck out resets the table to the empty state: no layout
        // binding, no declared containers — just the bare surface and hands.
        groupState.resetLayoutState();
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
