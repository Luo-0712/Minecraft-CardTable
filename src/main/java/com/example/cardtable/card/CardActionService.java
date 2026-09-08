package com.example.cardtable.card;

import com.example.cardtable.api.CardRegistry;
import com.example.cardtable.api.TableActionDefinition;
import com.example.cardtable.api.TableLayoutDefinition;
import com.example.cardtable.api.ZoneDefinition;
import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.menu.CardTableMenu;
import com.example.cardtable.network.packet.CardActionPacket;
import com.example.cardtable.table.TableGroupService;
import com.example.cardtable.table.TableGroupState;
import com.example.cardtable.table.TableSectionState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Server-side authority for every table action. Validates seat and
 * ownership constraints, mutates zones in place, bumps the group
 * version once, and syncs through the existing block entity path plus the
 * directed hand packets. Clients never mutate zone state themselves.
 *
 * <p>The core knows no game rules: the reserved {@code free}/{@code hand}
 * zones keep their dedicated containers, everything else must be declared by
 * the active layout, and the {@code Perform} action runs one entry of the
 * layout's action table through a generic primitive (draw N from a stack,
 * shuffle a stack, flip/rotate a card). Ownership rules keep the sandbox
 * honest: shared zones are operable by any seated player, while a hand
 * belongs to its occupant only.</p>
 */
public final class CardActionService
{
    private static final float DEFAULT_SURFACE_POS = 0.5F;

    private CardActionService()
    {
    }

    public static void handle(@Nullable ServerPlayer actor, CardActionPacket packet)
    {
        if (actor == null || actor.level().isClientSide)
        {
            return;
        }
        // Same guard shape as CardTableMembershipPacket: the action only
        // counts while its menu is still open, valid and positioned here.
        if (!(actor.containerMenu instanceof CardTableMenu menu)
                || !menu.getTablePosition().equals(packet.tablePosition())
                || !menu.stillValid(actor))
        {
            return;
        }
        Level level = actor.level();
        TableGroupService.GroupView group = TableGroupService.resolve(level, packet.tablePosition());
        if (group == null || !TableGroupService.isSeated(level, packet.tablePosition(), actor))
        {
            return;
        }

        if (execute(level, group, actor, packet.action()))
        {
            TableGroupState groupState = groupState(level, group);
            if (groupState != null)
            {
                groupState.bumpVersion();
            }
            TableGroupService.syncGroup(level, group);
            HandSyncService.pushAll(level, group);
        }
    }

    // Action dispatch -------------------------------------------------------

    private static boolean execute(Level level, TableGroupService.GroupView group,
                                   ServerPlayer actor, CardActionPacket.Action action)
    {
        if (action instanceof CardActionPacket.Action.Move move)
        {
            return move(level, group, actor, move);
        }
        if (action instanceof CardActionPacket.Action.Flip flip)
        {
            Located located = locate(level, group, actor.getUUID(), flip.instanceId());
            if (located == null)
            {
                return false;
            }
            located.card().flip();
            return true;
        }
        if (action instanceof CardActionPacket.Action.Rotate rotate)
        {
            Located located = locate(level, group, actor.getUUID(), rotate.instanceId());
            if (located == null)
            {
                return false;
            }
            located.card().rotate();
            return true;
        }
        if (action instanceof CardActionPacket.Action.Perform perform)
        {
            return performAction(level, group, actor, perform);
        }
        return false;
    }

    // Move ------------------------------------------------------------------

    private static boolean move(Level level, TableGroupService.GroupView group, ServerPlayer actor,
                                CardActionPacket.Action.Move move)
    {
        Located located = locate(level, group, actor.getUUID(), move.instanceId());
        if (located == null)
        {
            return false;
        }
        ZoneRef target = move.target();
        TableGroupState groupState = groupState(level, group);
        if (groupState == null)
        {
            return false;
        }
        float x = move.surfacePos() != null ? move.surfacePos().x : DEFAULT_SURFACE_POS;
        float y = move.surfacePos() != null ? move.surfacePos().y : DEFAULT_SURFACE_POS;

        // The reserved zones keep their dedicated containers; layout-declared
        // ids run through the layout dictionary validation chain first.
        ResourceLocation zoneId = target.zoneId();
        if (TableLayoutDefinition.ZONE_FREE.equals(zoneId) || TableLayoutDefinition.ZONE_HAND.equals(zoneId))
        {
            TableSectionState section = sectionState(level, group, target.sectionPos());
            if (section == null)
            {
                return false;
            }
            if (TableLayoutDefinition.ZONE_HAND.equals(zoneId)
                    && !actor.getUUID().equals(section.getOccupantId()))
            {
                return false; // Cards may only enter one's own hand.
            }
            located.removeFromZone().run();
            if (TableLayoutDefinition.ZONE_FREE.equals(zoneId))
            {
                section.getSurface().add(located.card(), x, y);
            }
            else
            {
                section.addHandCard(located.card());
            }
            return true;
        }
        // Layout-declared zones: validate against the active layout dictionary
        // before touching any state. Unknown ids are silently rejected (and
        // never bump the version), so a stale client cannot corrupt anything.
        TableLayoutDefinition layout = resolveLayout(groupState);
        if (layout == null)
        {
            return false;
        }
        ZoneDefinition definition = layout.zone(zoneId);
        if (definition == null)
        {
            return false; // not in the active layout dictionary
        }
        // Scope match: SHARED must not carry a seat section, PER_SEAT must
        // carry one that belongs to this table group.
        if (definition.scope() == ZoneDefinition.Scope.SHARED)
        {
            if (target.sectionPos() != null)
            {
                return false;
            }
        }
        else if (target.sectionPos() == null || sectionState(level, group, target.sectionPos()) == null)
        {
            return false;
        }
        // Capacity is a layout rule checked against the target container.
        ZoneState zone = definition.scope() == ZoneDefinition.Scope.SHARED
                ? groupState.getSharedZones().get(zoneId)
                : (sectionState(level, group, target.sectionPos()) == null
                   || sectionState(level, group, target.sectionPos()).getSeatZones() == null
                   || sectionState(level, group, target.sectionPos()).getSeatZones().get(zoneId) == null
                   ? null
                   : sectionState(level, group, target.sectionPos()).getSeatZones().get(zoneId));
        if (zone == null)
        {
            return false; // container missing: layout/state divergence, stay safe
        }
        if (definition.capacity() > 0 && zone.size() >= definition.capacity())
        {
            return false; // zone full
        }
        // Kind match: STACK ignores coordinates, PLACED (GRID/FREE) needs a
        // position which is clamped into 0..1 by the container itself.
        if (zone.storage() == ZoneState.Storage.STACK)
        {
            located.removeFromZone().run();
            zone.addToStackTop(located.card());
            return true;
        }
        if (move.surfacePos() == null)
        {
            return false; // placed zones require a position
        }
        // GRID drops snap to a slot center; the client may pre-quantize and the
        // idempotent helper lands on the same slot (FREE keeps the raw point).
        if (definition.kind() == ZoneDefinition.Kind.GRID)
        {
            float[] snapped = ZoneDefinition.quantizeGrid(x, y, definition.capacity());
            x = snapped[0];
            y = snapped[1];
        }
        located.removeFromZone().run();
        zone.addPlaced(located.card(), x, y);
        return true;
    }

    /**
     * The normalized layout the table currently runs, resolved from the
     * group's binding. {@code null} when no deck is bound (the empty table)
     * or when the bound layout vanished from the registry; in both cases
     * layout-declared ids are rejected and only the reserved zones work.
     */
    @Nullable
    private static TableLayoutDefinition resolveLayout(TableGroupState groupState)
    {
        ResourceLocation layoutId = groupState.getActiveLayoutId();
        if (layoutId == null)
        {
            return null;
        }
        TableLayoutDefinition layout = CardRegistry.getLayout(layoutId);
        return layout == null ? null : layout.normalized();
    }

    // Declared actions --------------------------------------------------------

    /**
     * Runs one action of the active layout's action table through its generic
     * primitive. The action id must exist in the table and a DRAW/SHUFFLE
     * source must be a declared STACK zone; everything else is rejected
     * without touching state (and without bumping the version).
     */
    private static boolean performAction(Level level, TableGroupService.GroupView group,
                                         ServerPlayer actor, CardActionPacket.Action.Perform perform)
    {
        TableGroupState groupState = groupState(level, group);
        TableLayoutDefinition layout = groupState == null ? null : resolveLayout(groupState);
        TableActionDefinition action = layout == null ? null : actionById(layout, perform.actionId());
        if (action == null)
        {
            return false; // not in the active action table: stale or hostile client
        }
        return applyDeclaredAction(layout, groupState, ownSeat(level, group, actor),
                actor.getUUID(), action, perform.instanceId(), level.getRandom(),
                collectSections(level, group));
    }

    @Nullable
    private static TableActionDefinition actionById(TableLayoutDefinition layout,
                                                    ResourceLocation actionId)
    {
        for (TableActionDefinition action : layout.actions())
        {
            if (action.id().equals(actionId))
            {
                return action;
            }
        }
        return null;
    }

    /**
     * Pure core of the declared-action primitives, shared with the
     * feasibility test: applies one action to plain state. All server guards
     * (menu validity, seating) happen in {@link #handle}; this method only
     * enforces the layout rules.
     *
     * @param actorSeat the acting player's seat ({@code null} when not seated)
     * @param sections  every section of the table group, for locating cards
     * @param random    server-side randomness for SHUFFLE
     */
    static boolean applyDeclaredAction(TableLayoutDefinition layout,
                                       TableGroupState groupState,
                                       @Nullable TableSectionState actorSeat,
                                       @Nullable UUID actorId,
                                       TableActionDefinition action,
                                       @Nullable UUID instanceId,
                                       RandomSource random,
                                       List<TableSectionState> sections)
    {
        switch (action.type())
        {
            case DRAW ->
            {
                // DRAW primitive: take up to {@code amount} cards from the
                // source pile top into the actor's own hand.
                ZoneState pile = stackContainer(layout, groupState, actorSeat, action.sourceZone());
                if (pile == null || actorSeat == null)
                {
                    return false;
                }
                List<CardInstance> drawn = new ArrayList<>(Math.min(action.amount(), pile.stackCards().size()));
                for (int index = 0; index < action.amount(); index++)
                {
                    CardInstance card = pile.takeFromStackTop();
                    if (card == null)
                    {
                        break;
                    }
                    drawn.add(card);
                }
                if (drawn.isEmpty())
                {
                    return false;
                }
                actorSeat.addHandCards(drawn);
                return true;
            }
            case SHUFFLE ->
            {
                // SHUFFLE primitive: Fisher-Yates over the source pile; the
                // resulting order travels through the normal sync path.
                ZoneState pile = stackContainer(layout, groupState, actorSeat, action.sourceZone());
                if (pile == null)
                {
                    return false;
                }
                List<CardInstance> cards = pile.stackCards();
                for (int index = cards.size() - 1; index > 0; index--)
                {
                    Collections.swap(cards, index, random.nextInt(index + 1));
                }
                return true;
            }
            case FLIP ->
            {
                Located located = locateIn(groupState, actorId, instanceId, sections);
                if (located == null)
                {
                    return false;
                }
                located.card().flip();
                return true;
            }
            case ROTATE ->
            {
                Located located = locateIn(groupState, actorId, instanceId, sections);
                if (located == null)
                {
                    return false;
                }
                located.card().rotate();
                return true;
            }
        }
        return false;
    }

    /**
     * The STACK container of a declared zone for the acting player: SHARED
     * zones live on the group, PER_SEAT ones in the actor's own seat.
     */
    @Nullable
    private static ZoneState stackContainer(TableLayoutDefinition layout, TableGroupState groupState,
                                            @Nullable TableSectionState actorSeat,
                                            @Nullable ResourceLocation zoneId)
    {
        if (zoneId == null)
        {
            return null;
        }
        ZoneDefinition definition = layout.zone(zoneId);
        if (definition == null || definition.kind() != ZoneDefinition.Kind.STACK)
        {
            return null; // only declared piles can be drawn from or shuffled
        }
        if (definition.scope() == ZoneDefinition.Scope.SHARED)
        {
            return groupState.getSharedZones().get(zoneId);
        }
        return actorSeat == null ? null : actorSeat.getSeatZones().get(zoneId);
    }

    // Locating ---------------------------------------------------------------

    /** Finds an instance across every zone; hands are only searched in the actor's own seat. */
    @Nullable
    private static Located locate(Level level, TableGroupService.GroupView group,
                                  UUID actorId, UUID instanceId)
    {
        TableGroupState groupState = groupState(level, group);
        if (groupState == null)
        {
            return null;
        }
        return locateIn(groupState, actorId, instanceId, collectSections(level, group));
    }

    /**
     * Pure locator shared with {@link #applyDeclaredAction}: shared zones
     * first, then each section's surface, seat zones and finally the actor's
     * own hand. A card in no searchable zone cannot be acted upon.
     */
    @Nullable
    private static Located locateIn(TableGroupState groupState, @Nullable UUID actorId,
                                    @Nullable UUID instanceId, List<TableSectionState> sections)
    {
        if (instanceId == null)
        {
            return null;
        }
        for (ZoneState zone : groupState.getSharedZones().values())
        {
            Located located = locateInZone(zone, instanceId);
            if (located != null)
            {
                return located;
            }
        }
        for (TableSectionState section : sections)
        {
            var found = section.getSurface().find(instanceId);
            if (found.isPresent())
            {
                return new Located(found.get().card(), () -> section.getSurface().remove(instanceId));
            }
            for (ZoneState zone : section.getSeatZones().values())
            {
                Located located = locateInZone(zone, instanceId);
                if (located != null)
                {
                    return located;
                }
            }
            // A hand is invisible and inert to everyone but its owner.
            if (actorId != null && actorId.equals(section.getOccupantId()))
            {
                for (CardInstance card : section.getHand())
                {
                    if (card.instanceId().equals(instanceId))
                    {
                        return new Located(card, () -> section.removeHandCard(instanceId));
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static Located locateInZone(ZoneState zone, UUID instanceId)
    {
        if (zone.storage() == ZoneState.Storage.STACK)
        {
            for (CardInstance card : zone.stackCards())
            {
                if (card.instanceId().equals(instanceId))
                {
                    return new Located(card, () ->
                            zone.stackCards().removeIf(pileCard -> pileCard.instanceId().equals(instanceId)));
                }
            }
            return null;
        }
        var found = zone.find(instanceId);
        return found.map(entry -> new Located(entry.card(), () -> zone.remove(instanceId))).orElse(null);
    }

    // Helpers ----------------------------------------------------------------

    private record Located(CardInstance card, Runnable removeFromZone)
    {
    }

    /** Every section state of the table group, in the group's position order. */
    private static List<TableSectionState> collectSections(Level level, TableGroupService.GroupView group)
    {
        List<TableSectionState> sections = new ArrayList<>();
        for (BlockPos pos : group.positions())
        {
            if (level.getBlockEntity(pos) instanceof CardTableBlockEntity section)
            {
                sections.add(section.getSectionState());
            }
        }
        return sections;
    }

    @Nullable
    private static TableGroupState groupState(Level level, TableGroupService.GroupView group)
    {
        return level.getBlockEntity(group.masterPos()) instanceof CardTableBlockEntity master
                ? master.getGroupState() : null;
    }

    @Nullable
    private static TableSectionState sectionState(Level level, TableGroupService.GroupView group,
                                                  @Nullable BlockPos sectionPos)
    {
        if (sectionPos == null || !group.positions().contains(sectionPos))
        {
            return null;
        }
        return level.getBlockEntity(sectionPos) instanceof CardTableBlockEntity section
                ? section.getSectionState() : null;
    }

    @Nullable
    private static TableSectionState ownSeat(Level level, TableGroupService.GroupView group, ServerPlayer actor)
    {
        for (BlockPos pos : group.positions())
        {
            if (level.getBlockEntity(pos) instanceof CardTableBlockEntity section
                    && actor.getUUID().equals(section.getSectionState().getOccupantId()))
            {
                return section.getSectionState();
            }
        }
        return null;
    }
}
