package com.example.cardtable.table;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure topology helpers for horizontally merged card tables. A group is any
 * set of card table blocks connected through their four horizontal sides on
 * the same height; every block contributes exactly one seat, so a group may
 * span at most {@link #MAX_TABLES_PER_GROUP} blocks.
 */
public final class TableGraph
{
    /** Maximum number of table blocks (and therefore seats) in one merged group. */
    public static final int MAX_TABLES_PER_GROUP = 12;

    private TableGraph()
    {
    }

    // Splits an arbitrary set of table positions into 4-connected components.
    public static List<Set<BlockPos>> connectedComponents(Collection<BlockPos> positions)
    {
        Set<BlockPos> unvisited = new HashSet<>(positions);
        List<Set<BlockPos>> components = new ArrayList<>();
        for (BlockPos start : positions)
        {
            if (!unvisited.remove(start))
            {
                continue;
            }

            Set<BlockPos> component = new HashSet<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty())
            {
                BlockPos current = queue.poll();
                component.add(current);
                for (BlockPos neighbor : horizontalNeighbors(current))
                {
                    // Marking visited on enqueue keeps every position in exactly one component.
                    if (unvisited.remove(neighbor))
                    {
                        queue.add(neighbor);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    // Deterministic group anchor: smallest position by (y, x, z), so every
    // member of a component elects the same master. Deliberately not
    // BlockPos#compareTo, whose axis order is an implementation detail; the
    // same comparator also fixes the client-side seat ring order.
    public static int comparePositions(BlockPos first, BlockPos second)
    {
        int byY = Integer.compare(first.getY(), second.getY());
        if (byY != 0)
        {
            return byY;
        }
        int byX = Integer.compare(first.getX(), second.getX());
        return byX != 0 ? byX : Integer.compare(first.getZ(), second.getZ());
    }

    public static BlockPos electMaster(Collection<BlockPos> positions)
    {
        BlockPos master = null;
        for (BlockPos position : positions)
        {
            if (master == null || comparePositions(position, master) < 0)
            {
                master = position;
            }
        }
        if (master == null)
        {
            throw new IllegalArgumentException("Cannot elect a master without table positions");
        }
        return master;
    }

    private static List<BlockPos> horizontalNeighbors(BlockPos position)
    {
        return List.of(position.north(), position.south(), position.east(), position.west());
    }
}
