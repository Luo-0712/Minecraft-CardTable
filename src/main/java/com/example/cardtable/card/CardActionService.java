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
 * <p>The core knows no game rules: the reserved {@code free} surface and the
 * {@code hand} keep their dedicated containers, everything else must be
 * declared by the active layout, and the {@code Perform} action runs one
 * entry of the layout's action table through a generic primitive (draw N
 * from a stack, shuffle a stack, flip/rotate a card). Ownership rules keep
 * the sandbox honest: every declared zone is a group-level instance operable
 * by any seated player, the blank surface is shared table space, and a hand
 * belongs to its occupant only (the {@code cardtable:hand} target is always
 * resolved to the acting player's own seat).</p>
 *
 * <p>Face orientation is the table's business, not the hand's: a hand card
 * has no public face (its owner reads it client-side), so flipping it is
 * refused, and leaving the hand is what settles the face — face-up by
 * default, face-down when the client asks for it.</p>
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
            // Flipping inside a hidden hand would poison the persistent
            // orientation: the owner already sees the face client-side, and a
            // card flipped in hand would land face-up on the table later.
            if (located == null || located.inHand())
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
            // A hand is addressed implicitly: the card always lands in the
            // acting player's own seat, never anyone else's.
            TableSectionState ownSeat = null;
            if (TableLayoutDefinition.ZONE_HAND.equals(zoneId))
            {
                ownSeat = ownSeat(level, group, actor);
                if (ownSeat == null)
                {
                    return false;
                }
            }
            located.removeFromZone().run();
            if (TableLayoutDefinition.ZONE_FREE.equals(zoneId))
            {
                // The blank surface is one group-level instance; any seated
                // player may place anywhere on it.
                applyPlayOrientation(located, zoneId, move.faceDown());
                groupState.getSurface().addPlaced(located.card(), x, y);
            }
            else
            {
                ownSeat.addHandCard(located.card());
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
        // Every layout zone is a group-level instance; the container must
        // exist for the active layout.
        ZoneState zone = groupState.getSharedZones().get(zoneId);
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
            applyPlayOrientation(located, zoneId, move.faceDown());
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
        applyPlayOrientation(located, zoneId, move.faceDown());
        zone.addPlaced(located.card(), x, y);
        return true;
    }

    /**
     * Face of a card entering a public zone, for the one transition that
     * defines it: leaving the owner's hand. A hand card is always played
     * face-up (the owner sees its face anyway, so the reveal is the honest
     * default) unless the client explicitly asked for a face-down play
     * (shift-drop); every other move keeps whatever face the card already
     * has, and re-entering a hand is never a reveal.
     */
    private static void applyPlayOrientation(Located located, ResourceLocation targetZoneId, boolean faceDown)
    {
        applyPlayOrientation(located.card(), located.inHand(), targetZoneId, faceDown);
    }

    /**
     * Pure form of the play rule above (same package so the tests can lock it
     * without a Minecraft bootstrap).
     *
     * @param fromHand    the card is leaving its owner's hidden hand
     * @param faceDown    the client asked for a face-down play (shift-drop)
     */
    static void applyPlayOrientation(CardInstance card, boolean fromHand,
                                     ResourceLocation targetZoneId, boolean faceDown)
    {
        if (!fromHand || TableLayoutDefinition.ZONE_HAND.equals(targetZoneId))
        {
            return;
        }
        card.setFaceUp(!faceDown);
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
                ZoneState pile = stackContainer(layout, groupState, action.sourceZone());
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
                ZoneState pile = stackContainer(layout, groupState, action.sourceZone());
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
                // Same rule as the direct Flip action: a hidden hand has no
                // public orientation, so flipping there is not a move.
                if (located == null || located.inHand())
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
     * The STACK container of a declared zone. Every zone is a group-level
     * instance, so the actor's seat plays no part here.
     */
    @Nullable
    private static ZoneState stackContainer(TableLayoutDefinition layout, TableGroupState groupState,
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
        return groupState.getSharedZones().get(zoneId);
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
     * Pure locator shared with {@link #applyDeclaredAction}: the blank
     * surface and the declared zones (all group-level) first, then each
     * section — where only the actor's own hand is searchable. A card in no
     * searchable zone cannot be acted upon.
     */
    @Nullable
    private static Located locateIn(TableGroupState groupState, @Nullable UUID actorId,
                                    @Nullable UUID instanceId, List<TableSectionState> sections)
    {
        if (instanceId == null)
        {
            return null;
        }
        var found = groupState.getSurface().find(instanceId);
        if (found.isPresent())
        {
            return new Located(found.get().card(), () -> groupState.getSurface().remove(instanceId), false);
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
            // A hand is invisible and inert to everyone but its owner.
            if (actorId != null && actorId.equals(section.getOccupantId()))
            {
                for (CardInstance card : section.getHand())
                {
                    if (card.instanceId().equals(instanceId))
                    {
                        return new Located(card, () -> section.removeHandCard(instanceId), true);
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
                            zone.stackCards().removeIf(pileCard -> pileCard.instanceId().equals(instanceId)), false);
                }
            }
            return null;
        }
        var found = zone.find(instanceId);
        return found.map(entry -> new Located(entry.card(), () -> zone.remove(instanceId), false)).orElse(null);
    }

    // Helpers ----------------------------------------------------------------

    /**
     * @param inHand whether the card was found in the actor's own hidden
     *               hand: such a card has no public face yet, which is what
     *               the play-orientation rule and the flip guard hinge on.
     */
    private record Located(CardInstance card, Runnable removeFromZone, boolean inHand)
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
