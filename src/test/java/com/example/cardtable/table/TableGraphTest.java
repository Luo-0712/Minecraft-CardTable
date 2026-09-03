package com.example.cardtable.table;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure-logic tests for the table merge topology (no Minecraft level needed:
 * {@link TableGraph} only manipulates positions).
 */
class TableGraphTest
{
    @Test
    void singleTableIsOneComponent()
    {
        Set<BlockPos> tables = Set.of(new BlockPos(10, 64, 20));
        List<Set<BlockPos>> components = TableGraph.connectedComponents(tables);
        assertEquals(1, components.size());
        assertEquals(tables, components.get(0));
    }

    @Test
    void straightLineIsOneComponent()
    {
        Set<BlockPos> tables = Set.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0));
        List<Set<BlockPos>> components = TableGraph.connectedComponents(tables);
        assertEquals(1, components.size());
        assertEquals(3, components.get(0).size());
    }

    @Test
    void lShapeIsOneComponent()
    {
        Set<BlockPos> tables = Set.of(
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 1),
                new BlockPos(1, 64, 1));
        List<Set<BlockPos>> components = TableGraph.connectedComponents(tables);
        assertEquals(1, components.size());
    }

    // Diagonal contact does not connect: tables merge only through their
    // four horizontal sides.
    @Test
    void diagonalTablesStaySeparate()
    {
        Set<BlockPos> tables = Set.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 1));
        assertEquals(2, TableGraph.connectedComponents(tables).size());
    }

    @Test
    void disconnectedSetsSplit()
    {
        Set<BlockPos> tables = Set.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(5, 64, 5),
                new BlockPos(5, 64, 6));
        List<Set<BlockPos>> components = TableGraph.connectedComponents(tables);
        assertEquals(2, components.size());
    }

    // Vertical neighbours are separate tables; merging is horizontal only.
    @Test
    void stackedTablesStaySeparate()
    {
        Set<BlockPos> tables = Set.of(
                new BlockPos(0, 64, 0),
                new BlockPos(0, 65, 0));
        assertEquals(2, TableGraph.connectedComponents(tables).size());
    }

    @Test
    void emptyInputHasNoComponents()
    {
        assertEquals(0, TableGraph.connectedComponents(Set.of()).size());
    }

    // Master election is deterministic: smallest position in (y, x, z) order.
    @Test
    void masterIsSmallestPosition()
    {
        assertEquals(new BlockPos(1, 64, 3),
                TableGraph.electMaster(Set.of(
                        new BlockPos(3, 64, 1),
                        new BlockPos(1, 64, 3),
                        new BlockPos(2, 64, 2))));
        assertEquals(new BlockPos(0, 63, 9),
                TableGraph.electMaster(Set.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(0, 63, 9))));
    }

    @Test
    void electingWithoutPositionsFails()
    {
        assertThrows(IllegalArgumentException.class, () -> TableGraph.electMaster(Set.of()));
    }

    @Test
    void groupCapMatchesDesign()
    {
        assertEquals(12, TableGraph.MAX_TABLES_PER_GROUP);
    }
}
