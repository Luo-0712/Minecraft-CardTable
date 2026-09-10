package com.example.cardtable.api;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZoneDefinitionTest
{
    private static ZoneDefinition.Builder builder()
    {
        return ZoneDefinition.builder(new ResourceLocation("test", "zone"))
                .kind(ZoneDefinition.Kind.FREE);
    }

    @Test
    void buildsWithDefaults()
    {
        ZoneDefinition zone = builder().rect(0.1F, 0.2F, 0.3F, 0.4F).build();
        assertEquals(new ResourceLocation("test", "zone"), zone.id());
        assertEquals(ZoneDefinition.Visibility.PUBLIC, zone.visibility());
        assertEquals(0, zone.capacity());
        assertEquals(0.1F, zone.x());
        assertEquals(0.4F, zone.h());
    }

    @Test
    void rejectsOutOfRangeRects()
    {
        assertThrows(IllegalStateException.class, () -> builder().rect(-0.1F, 0.0F, 0.5F, 0.5F).build());
        assertThrows(IllegalStateException.class, () -> builder().rect(0.0F, 0.0F, 0.0F, 0.5F).build());
        assertThrows(IllegalStateException.class, () -> builder().rect(0.0F, 0.0F, 0.5F, -0.5F).build());
        assertThrows(IllegalStateException.class, () -> builder().rect(0.6F, 0.0F, 0.5F, 0.5F).build());
        assertThrows(IllegalStateException.class, () -> builder().rect(0.0F, 0.6F, 0.5F, 0.5F).build());
    }

    @Test
    void gridRequiresPositiveCapacity()
    {
        assertThrows(IllegalStateException.class, () ->
                builder().kind(ZoneDefinition.Kind.GRID).capacity(0).rect(0.1F, 0.1F, 0.5F, 0.5F).build());
        assertEquals(0, builder().kind(ZoneDefinition.Kind.STACK).capacity(0).rect(0.1F, 0.1F, 0.5F, 0.5F).build().capacity());
        assertEquals(5, builder().kind(ZoneDefinition.Kind.GRID).capacity(5).rect(0.1F, 0.1F, 0.5F, 0.5F).build().capacity());
    }

    @Test
    void negativeCapacityIsRejected()
    {
        assertThrows(IllegalStateException.class, () ->
                builder().capacity(-1).rect(0.1F, 0.1F, 0.5F, 0.5F).build());
    }

    @Test
    void missingKindIsRejected()
    {
        assertThrows(IllegalStateException.class, () ->
                ZoneDefinition.builder(new ResourceLocation("test", "z")).rect(0.1F, 0.1F, 0.5F, 0.5F).build());
    }

    @Test
    void gridQuantizationSnapsToSlotCenterAndIsIdempotent()
    {
        // A 4-column grid: any x inside a column snaps to that column's center,
        // y pins to the row center. Re-quantizing a snapped point is a no-op.
        float[] snapped = ZoneDefinition.quantizeGrid(0.30F, 0.82F, 4);
        assertEquals(0.375F, snapped[0]); // column 1 center = (1 + 0.5) / 4
        assertEquals(0.5F, snapped[1]);
        float[] again = ZoneDefinition.quantizeGrid(snapped[0], snapped[1], 4);
        assertEquals(snapped[0], again[0]);
        assertEquals(snapped[1], again[1]);
    }

    @Test
    void gridQuantizationClampsEdges()
    {
        assertEquals(0.125F, ZoneDefinition.quantizeGrid(0.0F, 0.0F, 4)[0]); // first column
        assertEquals(0.875F, ZoneDefinition.quantizeGrid(1.0F, 1.0F, 4)[0]); // last column
        // A single-column grid always centers regardless of input.
        assertEquals(0.5F, ZoneDefinition.quantizeGrid(0.9F, 0.1F, 1)[0]);
    }
}
