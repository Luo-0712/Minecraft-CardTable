package com.example.cardtable.card;

import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.menu.CardTableMenu;
import com.example.cardtable.network.packet.CardActionPacket;
import com.example.cardtable.table.TableGroupService;
import com.example.cardtable.table.TableGroupState;
import com.example.cardtable.table.TableSectionState;
import net.minecraft.core.BlockPos;
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

        switch (target.zone())
        {
            case DRAW_PILE ->
            {
                located.removeFromZone().run();
                groupState.addToDrawPileTop(located.card());
                return true;
            }
            case DISCARD_PILE ->
            {
                located.removeFromZone().run();
                // A played card leaves its owner's hidden hand, so the discard
                // pile shows it face up; the pile is the table's public record
                // of what has been played. F can still turn it back over.
                located.card().setFaceUp(true);
                groupState.addToDiscardPileTop(located.card());
                return true;
            }
            case SURFACE ->
            {
                TableSectionState section = sectionState(level, group, target.sectionPos());
                if (section == null)
                {
                    return false;
                }
                located.removeFromZone().run();
                section.getSurface().add(located.card(), x, y);
                return true;
            }
            case HAND ->
            {
                TableSectionState section = sectionState(level, group, target.sectionPos());
                if (section == null || !actor.getUUID().equals(section.getOccupantId()))
                {
                    return false; // Cards may only enter one's own hand.
                }
                located.removeFromZone().run();
                section.addHandCard(located.card());
                return true;
            }
        }
        return false;
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
        if (shuffle.zone().zone() != ZoneRef.Zone.DRAW_PILE)
        {
            return false;
        }
        TableGroupState groupState = groupState(level, group);
        if (groupState == null)
        {
            return false;
        }
        // Server-side randomness only; the resulting order travels through
        // the normal sync path, clients never shuffle. Fisher-Yates over the
        // pile (RandomSource is not a java.util.Random).
        List<CardInstance> pile = groupState.getDrawPile();
        for (int index = pile.size() - 1; index > 0; index--)
        {
            Collections.swap(pile, index, level.getRandom().nextInt(index + 1));
        }
        return true;
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
