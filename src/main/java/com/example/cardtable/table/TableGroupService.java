package com.example.cardtable.table;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.block.custom.CardTableBlock;
import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.card.HandSyncService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 *
 * <p>Merge timing note: {@code LevelChunk#setBlockState} fires
 * {@code Block#onPlace} <em>before</em> the new block entity exists, so merge
 * propagation must not run from {@code onPlace}; it runs from
 * {@code EntityPlaceEvent} instead, which fires after the block (and its
 * entity) is fully placed. Cancelling that event rolls back the placement
 * snapshot, which is how the group size cap blocks player placements.
 * Commands bypass the event; for those the group converges lazily on the
 * next {@link #join}/{@link #leave} via {@link #ensureGroupIdentity}.</p>
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
        SEAT_TAKEN,
        /** The clicked position is not an intact card table. */
        TABLE_MISSING
    }

    // Fires after the placed block and its block entity fully exist (unlike
    // Block#onPlace). Cancelling rolls back the placement snapshot, so this
    // both enforces the group size cap and propagates group identity.
    @Mod.EventBusSubscriber(modid = CardTableMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    private static class PlacementGuard
    {
        @SubscribeEvent
        public static void onEntityPlace(BlockEvent.EntityPlaceEvent event)
        {
            if (!(event.getLevel() instanceof ServerLevel level)
                    || !(event.getPlacedBlock().getBlock() instanceof CardTableBlock))
            {
                return;
            }

            // The block is already in the world here, so this count includes it.
            if (collectTables(level, event.getPos(), null).size() > TableGraph.MAX_TABLES_PER_GROUP)
            {
                event.setCanceled(true);
                return;
            }

            // The new block entity exists by now; adopt the elected master's
            // identity across the merged group.
            CardTableBlockEntity placed = blockEntityAt(level, event.getPos());
            if (placed != null)
            {
                propagateGroupIdentity(level, event.getPos(), null);
            }
        }
    }

    // Second line of defence for seat leaks: the menu's removed() hook covers
    // the normal close paths, but a disconnect can skip reliable container
    // cleanup, so logout globally reclaims every seat the player still holds.
    // Runs purely off loaded block entities — the player entity may already
    // be invalid when this fires, and level()/position are not consulted.
    @Mod.EventBusSubscriber(modid = CardTableMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    private static class LogoutGuard
    {
        @SubscribeEvent
        public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event)
        {
            if (event.getEntity() instanceof ServerPlayer player)
            {
                releaseSeatsForPlayer(player.server, player.getUUID());
            }
        }
    }

    /**
     * Releases every seat occupied by {@code playerId} across all loaded card
     * tables on the server, then re-syncs each affected group once. Idempotent:
     * a player holds at most one seat per group, and already-empty sections are
     * skipped, so double invocation (menu removal + logout) is harmless.
     */
    public static void releaseSeatsForPlayer(MinecraftServer server, UUID playerId)
    {
        Set<BlockPos> affectedMasters = new HashSet<>();
        for (ServerLevel level : server.getAllLevels())
        {
            // Loaded chunks only; iteration never triggers chunk loading.
            for (LevelChunk chunk : loadedChunks(level))
            {
                for (var entry : chunk.getBlockEntities().entrySet())
                {
                    if (!(entry.getValue() instanceof CardTableBlockEntity tableEntity)
                            || blockEntityAt(level, entry.getKey()) != tableEntity)
                    {
                        continue;
                    }
                    BlockPos position = entry.getKey();
                    TableSectionState sectionState = tableEntity.getSectionState();
                    if (!playerId.equals(sectionState.getOccupantId()))
                    {
                        continue;
                    }
                    sectionState.setOccupant(null);
                    GroupView group = resolve(level, position, null);
                    if (group != null)
                    {
                        affectedMasters.add(group.masterPos());
                    }
                }
            }
        }
        for (BlockPos masterPos : affectedMasters)
        {
            ServerLevel level = null;
            GroupView group = null;
            for (ServerLevel candidate : server.getAllLevels())
            {
                group = resolve(candidate, masterPos, null);
                if (group != null)
                {
                    level = candidate;
                    break;
                }
            }
            if (level != null && group != null)
            {
                syncGroup(level, group);
                HandSyncService.pushAll(level, group);
            }
        }
    }

    // Resolves the merged group containing the given table block by BFS over
    // 4-connected card table blocks.
    @Nullable
    public static GroupView resolve(Level level, BlockPos position)
    {
        return resolve(level, position, null);
    }

    @Nullable
    private static GroupView resolve(Level level, BlockPos position, @Nullable BlockPos excluded)
    {
        Set<BlockPos> tables = collectTables(level, position, excluded);
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
        if (!(level instanceof ServerLevel serverLevel))
        {
            return SeatResult.TABLE_MISSING;
        }
        ensureGroupIdentity(serverLevel, position);
        return joinResolved(serverLevel, position, player, null);
    }

    // Same as join, but the BFS skips the given position (used while the
    // clicked block's entity is in an inconsistent state).
    private static SeatResult joinResolved(ServerLevel level, BlockPos position, Player player, @Nullable BlockPos excluded)
    {
        CardTableBlockEntity target = blockEntityAt(level, position);
        if (target == null || position.equals(excluded))
        {
            return SeatResult.TABLE_MISSING;
        }

        GroupView group = resolve(level, position, excluded);
        if (group == null)
        {
            return SeatResult.TABLE_MISSING;
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
        // The new occupant must receive their hand right away.
        HandSyncService.pushAll(level, group);
        return SeatResult.JOINED;
    }

    // Releases this player's seat anywhere in the group containing the block.
    public static boolean leave(Level level, BlockPos position, Player player)
    {
        if (!(level instanceof ServerLevel serverLevel))
        {
            return false;
        }
        ensureGroupIdentity(serverLevel, position);

        GroupView group = resolve(serverLevel, position);
        if (group == null)
        {
            return false;
        }

        boolean left = false;
        for (BlockPos pos : group.positions())
        {
            CardTableBlockEntity section = blockEntityAt(serverLevel, pos);
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
            syncGroup(serverLevel, group);
            // Re-push so the former occupant's client drops the hand mirror.
            HandSyncService.pushAll(serverLevel, group);
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

    // Called from CardTableBlock#onRemove after the block disappeared but
    // before its block entity is detached: survivors re-resolve with the dead
    // position excluded, so a cut vertex correctly splits the group.
    public static void onTableRemoved(Level level, BlockPos position)
    {
        if (!(level instanceof ServerLevel serverLevel))
        {
            return;
        }

        // Adjacent survivors each re-resolve their group. If the removed block
        // was not a cut vertex every neighbour resolves to the same (unchanged)
        // group; otherwise each side stands alone. Split sides keep their own
        // section data; the master copy of the group id may differ between
        // sides (accepted: table id is informational in this phase).
        Set<BlockPos> visited = new HashSet<>();
        for (Direction direction : Direction.Plane.HORIZONTAL)
        {
            BlockPos neighborPos = position.relative(direction);
            if (!visited.add(neighborPos) || blockEntityAt(serverLevel, neighborPos) == null)
            {
                continue;
            }
            GroupView group = resolve(serverLevel, neighborPos, position);
            if (group != null)
            {
                visited.addAll(group.positions());
                syncGroup(serverLevel, group);
            }
        }
    }

    // Converges group identity for groups assembled outside EntityPlaceEvent
    // (commands/setblock): writes the elected master's id onto every section
    // that still differs.
    private static void ensureGroupIdentity(ServerLevel level, BlockPos position)
    {
        propagateGroupIdentity(level, position, null);
    }

    // BFS-resolves the group around {@code position} and lets syncGroup() copy
    // the master's authoritative state onto every member. {@code excluded} marks
    // a position to skip (a block mid-removal).
    private static void propagateGroupIdentity(Level level, BlockPos position, @Nullable BlockPos excluded)
    {
        GroupView group = resolve(level, position, excluded);
        if (group == null)
        {
            return;
        }
        syncGroup(level, group);
    }

    /**
     * Copies the master's authoritative group state onto every section whose
     * cached copy went stale, then marks the whole group changed so the new
     * state reaches clients. Sections otherwise keep whatever they loaded from
     * disk, which is what made non-master copies drift out of sync.
     */
    public static void syncGroup(Level level, GroupView group)
    {
        CardTableBlockEntity master = blockEntityAt(level, group.masterPos());
        if (master != null)
        {
            TableGroupState authoritative = master.getGroupState();
            for (BlockPos pos : group.positions())
            {
                CardTableBlockEntity section = blockEntityAt(level, pos);
                if (section != null && section != master
                        && groupStatesDiffer(authoritative, section.getGroupState()))
                {
                    section.setGroupState(new TableGroupState(authoritative));
                }
            }
        }
        for (BlockPos pos : group.positions())
        {
            CardTableBlockEntity section = blockEntityAt(level, pos);
            if (section != null)
            {
                section.markStateChanged();
            }
        }
    }

    /**
     * Whether a section's cached group copy is stale against the master's
     * authoritative one. The table id catches a section that just merged into
     * this group (its identity is still its own); the version catches every
     * in-group mutation, since all of them bump it before syncing.
     */
    static boolean groupStatesDiffer(TableGroupState master, TableGroupState section)
    {
        return !master.getTableId().equals(section.getTableId())
                || master.getVersion() != section.getVersion();
    }

    // Flood-fills card table blocks connected through their four horizontal
    // sides. {@code excluded} is skipped (a block whose entity is about to be
    // detached mid-removal).
    private static Set<BlockPos> collectTables(Level level, BlockPos start, @Nullable BlockPos excluded)
    {
        Set<BlockPos> tables = new HashSet<>();
        if (start.equals(excluded) || blockEntityAt(level, start) == null)
        {
            return tables;
        }

        Set<BlockPos> queue = new HashSet<>();
        tables.add(start);
        queue.add(start);
        while (!queue.isEmpty())
        {
            var iterator = queue.iterator();
            BlockPos current = iterator.next();
            iterator.remove();
            for (Direction direction : Direction.Plane.HORIZONTAL)
            {
                BlockPos neighborPos = current.relative(direction);
                if (neighborPos.equals(excluded) || tables.contains(neighborPos))
                {
                    continue;
                }
                if (blockEntityAt(level, neighborPos) != null)
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

    // Enumerates the fully-loaded chunks of a server level without triggering
    // any chunk generation/loading: the chunk map only yields holders whose
    // full chunk is already available. Used by the logout seat-reclaim sweep.
    private static List<LevelChunk> loadedChunks(ServerLevel level)
    {
        List<LevelChunk> chunks = new ArrayList<>();
        for (ChunkHolder holder : level.getChunkSource().chunkMap.getChunks())
        {
            LevelChunk chunk = holder.getFullChunk();
            if (chunk != null)
            {
                chunks.add(chunk);
            }
        }
        return chunks;
    }
}
