package com.example.cardtable.card;

import com.example.cardtable.api.CardRegistry;
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
 * <p>Ownership rules keep the sandbox honest: shared zones (piles, surfaces)
 * are operable by any seated player, while a hand belongs to its occupant
 * only.</p>
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
        if (action instanceof CardActionPacket.Action.Draw draw)
        {
            return drawCards(level, group, actor, draw.count());
        }
        if (action instanceof CardActionPacket.Action.Shuffle shuffle)
        {
            return shuffle(level, group, shuffle);
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

        // Built-in zone ids keep their classic behavior; layout-declared ids
        // run through the layout dictionary validation chain first.
        ResourceLocation zoneId = target.zoneId();
        if (TableLayoutDefinition.ZONE_DRAW_PILE.equals(zoneId))
        {
            located.removeFromZone().run();
            groupState.addToDrawPileTop(located.card());
            return true;
        }
        if (TableLayoutDefinition.ZONE_DISCARD_PILE.equals(zoneId))
        {
            located.removeFromZone().run();
            // A played card leaves its owner's hidden hand, so the discard
            // pile shows it face up; the pile is the table's public record
            // of what has been played. F can still turn it back over.
            located.card().setFaceUp(true);
            groupState.addToDiscardPileTop(located.card());
            return true;
        }
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
     * The layout the table currently runs, resolved from the group's binding.
     * Falls back to the built-in default (the classic placement) when no
     * layout is bound; returns {@code null} only when a bound layout vanished
     * from the registry, in which case layout-declared ids are rejected.
     */
    private static TableLayoutDefinition resolveLayout(TableGroupState groupState)
    {
        ResourceLocation layoutId = groupState.getActiveLayoutId();
        if (layoutId == null)
        {
            return TableLayoutDefinition.defaultLayout();
        }
        TableLayoutDefinition layout = CardRegistry.getLayout(layoutId);
        return layout != null ? layout.normalized() : null;
    }

    // Draw / shuffle ---------------------------------------------------------

    private static boolean drawCards(Level level, TableGroupService.GroupView group,
                                     ServerPlayer actor, int count)
    {
        TableGroupState groupState = groupState(level, group);
        TableSectionState seat = ownSeat(level, group, actor);
        if (groupState == null || seat == null)
        {
            return false;
        }
        List<CardInstance> drawn = new ArrayList<>(Math.min(count, groupState.getDrawPile().size()));
        for (int index = 0; index < count; index++)
        {
            CardInstance card = groupState.takeFromDrawPileTop();
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
        seat.addHandCards(drawn);
        return true;
    }

    private static boolean shuffle(Level level, TableGroupService.GroupView group,
                                   CardActionPacket.Action.Shuffle shuffle)
    {
        ResourceLocation shuffleZoneId = shuffle.zone().zoneId();
        if (TableLayoutDefinition.ZONE_DRAW_PILE.equals(shuffleZoneId))
        {
            // Classic draw pile shuffle (unchanged behavior).
            TableGroupState groupState = groupState(level, group);
            if (groupState == null)
            {
                return false;
            }
            // Server-side randomness only; the resulting order travels through
            // the normal sync path, clients never shuffle. Fisher-Yates over
            // the pile (RandomSource is not a java.util.Random).
            List<CardInstance> pile = groupState.getDrawPile();
            for (int index = pile.size() - 1; index > 0; index--)
            {
                Collections.swap(pile, index, level.getRandom().nextInt(index + 1));
            }
            return true;
        }
        // Generalized shuffle (Phase 3): any STACK zone in the active layout
        // may be shuffled; SHARED needs no seat section.
        {
            // Generalized shuffle (Phase 3): any STACK zone in the active
            // layout may be shuffled; SHARED needs no seat section.
            TableGroupState groupState = groupState(level, group);
            TableLayoutDefinition layout = groupState == null ? null : resolveLayout(groupState);
            ZoneDefinition definition = layout == null ? null : layout.zone(shuffleZoneId);
            if (definition == null || definition.kind() != ZoneDefinition.Kind.STACK)
            {
                return false;
            }
            if (definition.scope() == ZoneDefinition.Scope.PER_SEAT
                    && (shuffle.zone().sectionPos() == null
                        || sectionState(level, group, shuffle.zone().sectionPos()) == null))
            {
                return false;
            }
            ZoneState zone = definition.scope() == ZoneDefinition.Scope.SHARED
                    ? groupState.getSharedZones().get(shuffleZoneId)
                    : sectionState(level, group, shuffle.zone().sectionPos()).getSeatZones().get(shuffleZoneId);
            if (zone == null || zone.storage() != ZoneState.Storage.STACK)
            {
                return false;
            }
            shuffleList(zone.stackCards());
            groupState.bumpVersion();
            TableGroupService.syncGroup(level, group);
            return true;
        }
    }

    private static void shuffleList(List<CardInstance> list)
    {
        // Server-side randomness only; the resulting order travels through
        // the normal sync path, clients never shuffle. Fisher-Yates
        // (RandomSource is not a java.util.Random).
        for (int index = list.size() - 1; index > 0; index--)
        {
            Collections.swap(list, index, new java.security.SecureRandom().nextInt(index + 1));
        }
    }

    // Locating ---------------------------------------------------------------

    /** Finds an instance across every zone; hands are only searched in the actor's own seat. */
    @Nullable
    private static Located locate(Level level, TableGroupService.GroupView group,
                                  UUID actorId, UUID instanceId)
    {
        TableGroupState groupState = groupState(level, group);
        if (groupState != null)
        {
            if (containsInstance(groupState.getDrawPile(), instanceId))
            {
                return new Located(findInstance(groupState.getDrawPile(), instanceId),
                        () -> groupState.getDrawPile().removeIf(card -> card.instanceId().equals(instanceId)));
            }
            if (containsInstance(groupState.getDiscardPile(), instanceId))
            {
                return new Located(findInstance(groupState.getDiscardPile(), instanceId),
                        () -> groupState.getDiscardPile().removeIf(card -> card.instanceId().equals(instanceId)));
            }
        }
        for (BlockPos pos : group.positions())
        {
            if (!(level.getBlockEntity(pos) instanceof CardTableBlockEntity section))
            {
                continue;
            }
            TableSectionState sectionState = section.getSectionState();
            var found = sectionState.getSurface().find(instanceId);
            if (found.isPresent())
            {
                return new Located(found.get().card(), () -> sectionState.getSurface().remove(instanceId));
            }
            // A hand is invisible and inert to everyone but its owner.
            if (actorId.equals(sectionState.getOccupantId())
                    && containsInstance(sectionState.getHand(), instanceId))
            {
                return new Located(findInstance(sectionState.getHand(), instanceId),
                        () -> sectionState.removeHandCard(instanceId));
            }
        }
        return null;
    }

    // Helpers ----------------------------------------------------------------

    private record Located(CardInstance card, Runnable removeFromZone)
    {
    }

    private static boolean containsInstance(List<CardInstance> cards, UUID instanceId)
    {
        return findInstance(cards, instanceId) != null;
    }

    @Nullable
    private static CardInstance findInstance(List<CardInstance> cards, UUID instanceId)
    {
        for (CardInstance card : cards)
        {
            if (card.instanceId().equals(instanceId))
            {
                return card;
            }
        }
        return null;
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
