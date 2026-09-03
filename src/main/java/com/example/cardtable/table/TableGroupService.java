package com.example.cardtable.table;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.block.custom.CardTableBlock;
import com.example.cardtable.block.entity.CardTableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side facade for merged card tables: resolves the group a block
 * belongs to, seats and releases players, and keeps group identity in sync
 * when tables are placed or removed.
 *
 * <p>Membership lives per block ({@link TableSectionState}, one seat per
 * table), while group identity lives on the master block (smallest position,
 * see {@link TableGraph#electMaster}).</p>
 */
public final class TableGroupService
{
    private TableGroupService()
    {
    }

    /** Immutable view of one merged group. */
    public record GroupView(BlockPos masterPos, Set<BlockPos> positions)
    {
    }

    public enum SeatResult
    {
        JOINED,
        /** The clicked table block already has an occupant; open as spectator. */
        SEAT_TAKEN
    }

    /** Forge handler blocking player placements that would exceed the group cap. */
    @Mod.EventBusSubscriber(modid = CardTableMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    private static class PlacementGuard
    {
        @SubscribeEvent
        public static void onEntityPlace(BlockEvent.EntityPlaceEvent event)
        {
            if (!(event.getLevel() instanceof ServerLevel serverLevel)
                    || !(event.getPlacedBlock().getBlock() instanceof CardTableBlock))
            {
                return;
            }
            // The block is already in the world when this fires; the neighbour
            // count therefore already includes it.
            if (collectTables(serverLevel, event.getPos()).size() > TableGraph.MAX_TABLES_PER_GROUP)
            {
                event.setCanceled(true);
            }
        }
    }

    // Resolves the merged group containing the given table block by BFS over
    // 4-connected card table blocks.
    @Nullable
    public static GroupView resolve(Level level, BlockPos position)
    {
        Set<BlockPos> tables = collectTables(level, position);
        if (tables.isEmpty())
        {
            return null;
        }
        return new GroupView(TableGraph.electMaster(tables), tables);
    }

    // Right-click on a specific table block: occupy that block's seat. Joining
    // while seated elsewhere in the same group moves the seat.
    public static SeatResult join(Level level, BlockPos position, Player player)
    {
        CardTableBlockEntity target = blockEntityAt(level, position);
        if (target == null)
        {
            return SeatResult.SEAT_TAKEN;
        }

        GroupView group = resolve(level, position);
        if (group == null)
        {
            return SeatResult.SEAT_TAKEN;
        }

        UUID playerId = player.getUUID();
        UUID targetOccupant = target.getSectionState().getOccupantId();
        if (playerId.equals(targetOccupant))
        {
            return SeatResult.JOINED; // Already seated here.
        }
        if (targetOccupant != null)
        {
            return SeatResult.SEAT_TAKEN;
        }

        for (BlockPos pos : group.positions())
        {
            CardTableBlockEntity section = blockEntityAt(level, pos);
            if (section == null)
            {
                continue;
            }
            TableSectionState sectionState = section.getSectionState();
            if (!pos.equals(position) && playerId.equals(sectionState.getOccupantId()))
            {
                // Move within the group: release the previous seat.
                sectionState.setOccupant(null);
            }
        }
        target.getSectionState().setOccupant(playerId);
        syncGroup(level, group);
        return SeatResult.JOINED;
    }

    // Releases this player's seat anywhere in the group containing the block.
    public static boolean leave(Level level, BlockPos position, Player player)
    {
        GroupView group = resolve(level, position);
        if (group == null)
        {
            return false;
        }

        boolean left = false;
        for (BlockPos pos : group.positions())
        {
            CardTableBlockEntity section = blockEntityAt(level, pos);
            if (section == null)
            {
                continue;
            }
            TableSectionState sectionState = section.getSectionState();
            if (player.getUUID().equals(sectionState.getOccupantId()))
            {
                sectionState.setOccupant(null);
                left = true;
            }
        }

        if (left)
        {
            syncGroup(level, group);
        }
        return left;
    }

    /** Whether the player occupies any seat in the group containing the block. */
    public static boolean isSeated(Level level, BlockPos position, Player player)
    {
        GroupView group = resolve(level, position);
        if (group == null)
        {
            return false;
        }
        for (BlockPos pos : group.positions())
        {
            CardTableBlockEntity section = blockEntityAt(level, pos);
            if (section != null && player.getUUID().equals(section.getSectionState().getOccupantId()))
            {
                return true;
            }
        }
        return false;
    }

    // Called from CardTableBlock#onPlace after a table block appears: adopt the
    // elected master's group identity across the merged group.
    public static void onTablePlaced(Level level, BlockPos position)
    {
        if (level.isClientSide)
        {
            return;
        }

        Set<BlockPos> tables = collectTables(level, position);
        if (tables.size() <= 1)
        {
            return; // Standalone table; its states were minted by the BlockEntity.
        }

        BlockPos masterPos = TableGraph.electMaster(tables);
        CardTableBlockEntity master = blockEntityAt(level, masterPos);
        if (master == null)
        {
            return;
        }

        // The elected master's group identity wins; every other section adopts it.
        TableGroupState masterGroupState = master.getGroupState();
        for (BlockPos pos : tables)
        {
            if (pos.equals(masterPos))
            {
                continue;
            }
            CardTableBlockEntity section = blockEntityAt(level, pos);
            if (section != null && !masterGroupState.getTableId().equals(section.getGroupState().getTableId()))
            {
                section.setGroupState(new TableGroupState(masterGroupState));
            }
        }
        syncGroup(level, new GroupView(masterPos, tables));
    }

    // Called from CardTableBlock#onRemove after the block disappeared: the
    // survivors re-resolve. If the removed block was not a cut vertex the
    // group is unchanged; otherwise each component keeps its own sections'
    // data and simply stands alone. The master copy may differ between the
    // split sides (accepted: table id is informational in this phase).
    public static void onTableRemoved(Level level, BlockPos position)
    {
        if (level.isClientSide)
        {
            return;
        }

        // The removed block is already gone, so BFS sees only survivors.
        for (Direction direction : Direction.Plane.HORIZONTAL)
        {
            BlockPos neighborPos = position.relative(direction);
            if (blockEntityAt(level, neighborPos) == null)
            {
                continue;
            }
            GroupView group = resolve(level, neighborPos);
            if (group != null)
            {
                syncGroup(level, group);
            }
        }
    }

    /** Whether a newly placed table can merge into the group at {@code position} without exceeding the cap. */
    public static boolean canMerge(Level level, BlockPos position)
    {
        return collectTables(level, position).size() <= TableGraph.MAX_TABLES_PER_GROUP;
    }

    // Marks every block of the group changed and pushes the new state to clients.
    private static void syncGroup(Level level, GroupView group)
    {
        for (BlockPos pos : group.positions())
        {
            CardTableBlockEntity section = blockEntityAt(level, pos);
            if (section != null)
            {
                section.markStateChanged();
            }
        }
    }

    // Flood-fills card table blocks connected through their four horizontal sides.
    private static Set<BlockPos> collectTables(Level level, BlockPos start)
    {
        Set<BlockPos> tables = new HashSet<>();
        if (blockEntityAt(level, start) == null)
        {
            return tables;
        }

        Deque<BlockPos> queue = new ArrayDeque<>();
        tables.add(start);
        queue.add(start);
        while (!queue.isEmpty())
        {
            BlockPos current = queue.poll();
            for (Direction direction : Direction.Plane.HORIZONTAL)
            {
                BlockPos neighborPos = current.relative(direction);
                if (!tables.contains(neighborPos) && blockEntityAt(level, neighborPos) != null)
                {
                    tables.add(neighborPos);
                    queue.add(neighborPos);
                }
            }
        }
        return tables;
    }

    @Nullable
    private static CardTableBlockEntity blockEntityAt(Level level, BlockPos position)
    {
        return level.getBlockEntity(position) instanceof CardTableBlockEntity tableEntity
                && tableEntity.getBlockState().getBlock() instanceof CardTableBlock ? tableEntity : null;
    }
}
