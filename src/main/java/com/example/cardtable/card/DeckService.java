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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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

        // Cards enter face-down in set order; the pile top is the last entry.
        for (CardDefinition definition : CardRegistry.cardsInSet(deckId.get()))
        {
            groupState.getDrawPile().add(new CardInstance(definition.id()));
        }
        groupState.setDeckStack(deckStack.copy());
        // Layout binding (D1/D2): the set decides the layout. A missing or
        // unregistered layout degrades to the built-in default with a warning
        // instead of rejecting the deck.
        ResourceLocation layoutId = set.layout();
        if (layoutId != null && CardRegistry.getLayout(layoutId) == null)
        {
            LOGGER.warn("Set {} references unknown layout {}; falling back to {}",
                    set.id(), layoutId, TableLayoutDefinition.DEFAULT_LAYOUT_ID);
            layoutId = null;
        }
        groupState.setLayoutBinding(deckId.get(), layoutId);
        ensureZoneContainers(level, group, groupState, layoutId);
        groupState.bumpVersion();
        TableGroupService.syncGroup(level, group);
        return true;
    }

    /**
     * Instantiates the generic zone containers (shared + per-seat) for the
     * active layout. Built-in zones keep their dedicated fields and are never
     * created here; unknown layout ids (should not happen after the insert
     * check) simply create nothing.
     */
    private static void ensureZoneContainers(Level level, TableGroupService.GroupView group,
                                             TableGroupState groupState, @Nullable ResourceLocation layoutId)
    {
        TableLayoutDefinition layout = layoutId == null
                ? TableLayoutDefinition.defaultLayout() : CardRegistry.getLayout(layoutId);
        if (layout == null)
        {
            return;
        }
        TableLayoutDefinition normalizedLayout = layout.normalized();
        for (var zone : normalizedLayout.zones())
        {
            if (TableLayoutDefinition.ZONE_DRAW_PILE.equals(zone.id())
                    || TableLayoutDefinition.ZONE_DISCARD_PILE.equals(zone.id())
                    || TableLayoutDefinition.ZONE_FREE.equals(zone.id())
                    || TableLayoutDefinition.ZONE_HAND.equals(zone.id()))
            {
                continue; // built-ins keep their dedicated containers
            }
            if (zone.scope() == ZoneDefinition.Scope.SHARED)
            {
                groupState.getSharedZones().put(zone.id(), ZoneState.stackFor(zone.kind()));
            }
            else
            {
                for (BlockPos pos : group.positions())
                {
                    if (level.getBlockEntity(pos) instanceof CardTableBlockEntity section)
                    {
                        section.getSectionState().getSeatZones()
                                .put(zone.id(), ZoneState.stackFor(zone.kind()));
                    }
                }
            }
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
                // Layout-declared PER_SEAT zones are reclaimed too.
                sectionState.getSeatZones().values().forEach(zone -> zone.removeIfDefinition(reclaimedIds));
            }
        }

        // Layout-declared SHARED zones are reclaimed too.
        groupState.getSharedZones().values().forEach(zone -> zone.removeIfDefinition(reclaimedIds));

        groupState.setDeckStack(ItemStack.EMPTY);
        // D2: taking the deck out resets the table to the classic empty state.
        groupState.resetLayoutState();
        for (var pos : group.positions())
        {
            if (level.getBlockEntity(pos) instanceof CardTableBlockEntity section)
            {
                section.getSectionState().resetSeatZones();
            }
        }
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
