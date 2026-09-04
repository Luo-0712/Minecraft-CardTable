package com.example.cardtable.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableLayoutDefinitionTest
{
    private static ZoneDefinition zone(String relativeId, ZoneDefinition.Kind kind, ZoneDefinition.Scope scope,
                                       float x, float y, float w, float h)
    {
        return ZoneDefinition.builder(new ResourceLocation("test", relativeId))
                .kind(kind).scope(scope).rect(x, y, w, h)
                .capacity(kind == ZoneDefinition.Kind.GRID ? 4 : 0)
                .build();
    }

    private static TableLayoutDefinition layout(ZoneDefinition... zones)
    {
        TableLayoutDefinition.Builder builder = TableLayoutDefinition.builder(new ResourceLocation("test", "layout"));
        for (ZoneDefinition zone : zones)
        {
            builder.zone(zone);
        }
        return builder.build();
    }

    @Test
    void defaultLayoutContainsTheThreeBuiltinPlayableZones()
    {
        TableLayoutDefinition normalized = TableLayoutDefinition.defaultLayout().normalized();
        assertEquals(3, normalized.zones().size());
        assertEquals(TableLayoutDefinition.ZONE_DRAW_PILE, normalized.zone(TableLayoutDefinition.ZONE_DRAW_PILE).id());
        assertEquals(ZoneDefinition.Kind.STACK, normalized.zone(TableLayoutDefinition.ZONE_DRAW_PILE).kind());
        assertEquals(ZoneDefinition.Scope.SHARED, normalized.zone(TableLayoutDefinition.ZONE_DRAW_PILE).scope());
        assertEquals(0.02F, normalized.zone(TableLayoutDefinition.ZONE_DRAW_PILE).x());
        assertEquals(0.02F, normalized.zone(TableLayoutDefinition.ZONE_DRAW_PILE).y());
        assertEquals(0.05F, normalized.zone(TableLayoutDefinition.ZONE_DRAW_PILE).w());
        assertEquals(0.10F, normalized.zone(TableLayoutDefinition.ZONE_DRAW_PILE).h());
        assertEquals(0.93F, normalized.zone(TableLayoutDefinition.ZONE_DISCARD_PILE).x());
        assertEquals(1.0F, normalized.zone(TableLayoutDefinition.ZONE_FREE).w());
        assertEquals(1.0F, normalized.zone(TableLayoutDefinition.ZONE_FREE).h());
    }

    @Test
    void implicitInheritanceFillsMissingBuiltins()
    {
        TableLayoutDefinition normalized = layout(
                zone("battlefield", ZoneDefinition.Kind.FREE, ZoneDefinition.Scope.SHARED, 0.2F, 0.1F, 0.6F, 0.8F)
        ).normalized();

        // Custom zone kept, all three builtins filled from the default layout.
        assertEquals(4, normalized.zones().size());
        assertEquals(0.2F, normalized.zone(new ResourceLocation("test", "battlefield")).x());
        assertEquals(0.02F, normalized.zone(TableLayoutDefinition.ZONE_DRAW_PILE).x());
        assertEquals(0.93F, normalized.zone(TableLayoutDefinition.ZONE_DISCARD_PILE).x());
        assertEquals(0.0F, normalized.zone(TableLayoutDefinition.ZONE_FREE).x());
    }

    @Test
    void explicitOverrideReplacesBuiltinRectButKeepsScopeAndKind()
    {
        ZoneDefinition override = ZoneDefinition.builder(TableLayoutDefinition.ZONE_DISCARD_PILE)
                .kind(ZoneDefinition.Kind.STACK).scope(ZoneDefinition.Scope.SHARED)
                .rect(0.4F, 0.8F, 0.05F, 0.10F)
                .capacity(30)
                .label(Component.literal("弃牌堆"))
                .build();
        TableLayoutDefinition normalized = layout(
                zone("battlefield", ZoneDefinition.Kind.FREE, ZoneDefinition.Scope.SHARED, 0.2F, 0.1F, 0.6F, 0.8F),
                override
        ).normalized();

        ZoneDefinition discard = normalized.zone(TableLayoutDefinition.ZONE_DISCARD_PILE);
        assertEquals(0.4F, discard.x());
        assertEquals(0.8F, discard.y());
        assertEquals(30, discard.capacity());
        assertEquals("弃牌堆", discard.label().getString());
        assertEquals(ZoneDefinition.Kind.STACK, discard.kind());
        assertEquals(ZoneDefinition.Scope.SHARED, discard.scope());
    }

    @Test
    void tamperedScopeOrKindFallsBackToImplicitDefault()
    {
        TableLayoutDefinition tamperedScope = layout(
                zone("battlefield", ZoneDefinition.Kind.FREE, ZoneDefinition.Scope.SHARED, 0.2F, 0.1F, 0.6F, 0.8F),
                ZoneDefinition.builder(TableLayoutDefinition.ZONE_FREE)
                        .kind(ZoneDefinition.Kind.FREE).scope(ZoneDefinition.Scope.SHARED) // wrong scope
                        .rect(0.86F, 0.62F, 0.12F, 0.30F).build()
        ).normalized();
        assertEquals(0.0F, tamperedScope.zone(TableLayoutDefinition.ZONE_FREE).x()); // implicit default won

        TableLayoutDefinition tamperedKind = layout(
                zone("battlefield", ZoneDefinition.Kind.FREE, ZoneDefinition.Scope.SHARED, 0.2F, 0.1F, 0.6F, 0.8F),
                ZoneDefinition.builder(TableLayoutDefinition.ZONE_DRAW_PILE)
                        .kind(ZoneDefinition.Kind.GRID).scope(ZoneDefinition.Scope.SHARED) // wrong kind
                        .rect(0.02F, 0.02F, 0.05F, 0.10F).capacity(4).build()
        ).normalized();
        assertEquals(ZoneDefinition.Kind.STACK, tamperedKind.zone(TableLayoutDefinition.ZONE_DRAW_PILE).kind());
        assertEquals(0, tamperedKind.zone(TableLayoutDefinition.ZONE_DRAW_PILE).capacity());
    }

    @Test
    void duplicateZoneIdsAreRejectedAtBuildTime()
    {
        assertThrows(IllegalArgumentException.class, () -> layout(
                zone("bench", ZoneDefinition.Kind.GRID, ZoneDefinition.Scope.PER_SEAT, 0.1F, 0.1F, 0.5F, 0.5F),
                zone("bench", ZoneDefinition.Kind.GRID, ZoneDefinition.Scope.PER_SEAT, 0.2F, 0.2F, 0.5F, 0.5F)
        ));
    }

    @Test
    void reservedHandZoneIsRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> layout(
                ZoneDefinition.builder(TableLayoutDefinition.ZONE_HAND)
                        .kind(ZoneDefinition.Kind.STACK).scope(ZoneDefinition.Scope.PER_SEAT)
                        .rect(0.0F, 0.0F, 1.0F, 1.0F).build()
        ));
    }

    @Test
    void normalizationIsIdempotent()
    {
        TableLayoutDefinition layout = layout(
                zone("battlefield", ZoneDefinition.Kind.FREE, ZoneDefinition.Scope.SHARED, 0.2F, 0.1F, 0.6F, 0.8F),
                ZoneDefinition.builder(TableLayoutDefinition.ZONE_DISCARD_PILE)
                        .kind(ZoneDefinition.Kind.STACK).scope(ZoneDefinition.Scope.SHARED)
                        .rect(0.4F, 0.8F, 0.05F, 0.10F).build()
        );
        TableLayoutDefinition once = layout.normalized();
        TableLayoutDefinition twice = once.normalized();
        assertEquals(once.zones().size(), twice.zones().size());
        for (ZoneDefinition zone : once.zones())
        {
            ZoneDefinition other = twice.zone(zone.id());
            assertEquals(zone.kind(), other.kind());
            assertEquals(zone.scope(), other.scope());
            assertEquals(zone.x(), other.x());
            assertEquals(zone.y(), other.y());
            assertEquals(zone.w(), other.w());
            assertEquals(zone.h(), other.h());
            assertEquals(zone.capacity(), other.capacity());
        }
    }

    @Test
    void zoneLookupOnUnknownIdReturnsNull()
    {
        assertNull(layout().zone(new ResourceLocation("test", "nope")));
        assertTrue(layout().zones().isEmpty());
    }
}
