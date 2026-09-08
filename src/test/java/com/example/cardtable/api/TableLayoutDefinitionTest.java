package com.example.cardtable.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableLayoutDefinitionTest
{
    private static final ResourceLocation DECK_ID =
            new ResourceLocation("test", "deck");
    private static final ResourceLocation DISCARD_ID =
            new ResourceLocation("test", "discard");

    private static ZoneDefinition zone(String relativeId, ZoneDefinition.Kind kind, ZoneDefinition.Scope scope,
                                       float x, float y, float w, float h)
    {
        return ZoneDefinition.builder(new ResourceLocation("test", relativeId))
                .kind(kind).scope(scope).rect(x, y, w, h)
                .capacity(kind == ZoneDefinition.Kind.GRID ? 4 : 0)
                .build();
    }

    private static TableActionDefinition action(String relativeId, TableActionDefinition.Type type,
                                                @Nullable ResourceLocation source, String key)
    {
        TableActionDefinition.Builder builder = TableActionDefinition.builder(new ResourceLocation("test", relativeId))
                .type(type).key(key);
        if (source != null)
        {
            builder.sourceZone(source);
        }
        return builder.build();
    }

    private static TableLayoutDefinition.Builder layoutBuilder()
    {
        TableLayoutDefinition.Builder builder = TableLayoutDefinition.builder(new ResourceLocation("test", "layout"));
        builder.zone(zone("deck", ZoneDefinition.Kind.STACK, ZoneDefinition.Scope.SHARED, 0.02F, 0.02F, 0.06F, 0.12F));
        builder.initial(TableLayoutDefinition.INITIAL_DEFAULT_KEY, DECK_ID);
        return builder;
    }

    @Test
    void normalizedLayoutAlwaysCarriesTheImplicitFreeSurface()
    {
        TableLayoutDefinition normalized = TableLayoutDefinition.builder(new ResourceLocation("test", "empty"))
                .build().normalized();

        // A layout without any declaration is the bare table: just the surface.
        assertEquals(1, normalized.zones().size());
        assertEquals(TableLayoutDefinition.ZONE_FREE, normalized.zone(TableLayoutDefinition.ZONE_FREE).id());
        assertEquals(ZoneDefinition.Kind.FREE, normalized.zone(TableLayoutDefinition.ZONE_FREE).kind());
        assertEquals(ZoneDefinition.Scope.PER_SEAT, normalized.zone(TableLayoutDefinition.ZONE_FREE).scope());
        assertEquals(0.5F, normalized.zone(TableLayoutDefinition.ZONE_FREE).y());
        assertEquals(1.0F, normalized.zone(TableLayoutDefinition.ZONE_FREE).w());
        assertEquals(0.5F, normalized.zone(TableLayoutDefinition.ZONE_FREE).h());
    }

    @Test
    void declaredZonesAreKeptInTheFreeSurfaceCanBeOverridden()
    {
        TableLayoutDefinition normalized = layoutBuilder()
                .zone(ZoneDefinition.builder(TableLayoutDefinition.ZONE_FREE)
                        .kind(ZoneDefinition.Kind.FREE).scope(ZoneDefinition.Scope.PER_SEAT)
                        .rect(0.0F, 0.6F, 1.0F, 0.4F)
                        .label(Component.literal("手边"))
                        .build())
                .build().normalized();

        assertEquals(2, normalized.zones().size());
        ZoneDefinition free = normalized.zone(TableLayoutDefinition.ZONE_FREE);
        assertEquals(0.6F, free.y());
        assertEquals(0.4F, free.h());
        assertEquals("手边", free.label().getString());
        // scope/kind stay locked to the builtin values.
        assertEquals(ZoneDefinition.Kind.FREE, free.kind());
        assertEquals(ZoneDefinition.Scope.PER_SEAT, free.scope());
    }

    @Test
    void tamperedFreeScopeOrKindFallsBackToImplicitDefault()
    {
        TableLayoutDefinition tamperedScope = layoutBuilder()
                .zone(ZoneDefinition.builder(TableLayoutDefinition.ZONE_FREE)
                        .kind(ZoneDefinition.Kind.FREE).scope(ZoneDefinition.Scope.SHARED) // wrong scope
                        .rect(0.86F, 0.62F, 0.12F, 0.30F).build())
                .build().normalized();
        assertEquals(0.5F, tamperedScope.zone(TableLayoutDefinition.ZONE_FREE).y()); // implicit default won

        TableLayoutDefinition tamperedKind = layoutBuilder()
                .zone(ZoneDefinition.builder(TableLayoutDefinition.ZONE_FREE)
                        .kind(ZoneDefinition.Kind.STACK).scope(ZoneDefinition.Scope.PER_SEAT) // wrong kind
                        .rect(0.86F, 0.62F, 0.12F, 0.30F).build())
                .build().normalized();
        assertEquals(ZoneDefinition.Kind.FREE, tamperedKind.zone(TableLayoutDefinition.ZONE_FREE).kind());
    }

    @Test
    void reservedHandZoneIsRejectedAtBuildTime()
    {
        // The hand is a system zone: the builder refuses any declaration, so
        // normalization can never see one through the public API.
        assertThrows(IllegalArgumentException.class, () -> layoutBuilder()
                .zone(ZoneDefinition.builder(TableLayoutDefinition.ZONE_HAND)
                        .kind(ZoneDefinition.Kind.STACK).scope(ZoneDefinition.Scope.PER_SEAT)
                        .rect(0.0F, 0.0F, 1.0F, 1.0F).build()));
    }

    @Test
    void duplicateZoneAndActionIdsAreRejectedAtBuildTime()
    {
        assertThrows(IllegalArgumentException.class, () -> layoutBuilder()
                .zone(zone("bench", ZoneDefinition.Kind.GRID, ZoneDefinition.Scope.PER_SEAT, 0.1F, 0.1F, 0.5F, 0.5F))
                .zone(zone("bench", ZoneDefinition.Kind.GRID, ZoneDefinition.Scope.PER_SEAT, 0.2F, 0.2F, 0.5F, 0.5F)));
        assertThrows(IllegalArgumentException.class, () -> layoutBuilder()
                .action(action("draw", TableActionDefinition.Type.DRAW, DECK_ID, "key.keyboard.d"))
                .action(action("draw", TableActionDefinition.Type.DRAW, DECK_ID, "key.keyboard.d")));
    }

    @Test
    void pileZonesFollowTheStackConvention()
    {
        TableLayoutDefinition normalized = layoutBuilder()
                .zone(zone("bench", ZoneDefinition.Kind.GRID, ZoneDefinition.Scope.PER_SEAT, 0.1F, 0.1F, 0.5F, 0.5F))
                .zone(zone("market", ZoneDefinition.Kind.FREE, ZoneDefinition.Scope.SHARED, 0.2F, 0.4F, 0.5F, 0.3F))
                .build().normalized();

        // Only the stack zones count as piles; grid/free zones are plain areas.
        assertEquals(1, normalized.pileZones().size());
        assertEquals(DECK_ID, normalized.pileZones().get(0).id());
    }

    @Test
    void initialZoneForResolvesTheDefaultStock()
    {
        TableLayoutDefinition normalized = layoutBuilder().build().normalized();

        ZoneDefinition stock = normalized.initialZoneFor(new ResourceLocation("cardtable", "standard"));
        assertSame(stock, normalized.zone(DECK_ID));
        // The wildcard also answers when the set id is unknown (single-deck era).
        assertSame(stock, normalized.initialZoneFor(new ResourceLocation("other", "set")));
    }

    @Test
    void initialPointingOutsideTheLayoutIsDroppedByNormalization()
    {
        TableLayoutDefinition.Builder builder = TableLayoutDefinition.builder(new ResourceLocation("test", "layout"));
        builder.zone(zone("deck", ZoneDefinition.Kind.STACK, ZoneDefinition.Scope.SHARED, 0.02F, 0.02F, 0.06F, 0.12F));
        builder.initial("cardtable:standard", DISCARD_ID); // declared nowhere
        builder.initial(TableLayoutDefinition.INITIAL_DEFAULT_KEY, DECK_ID);

        TableLayoutDefinition normalized = builder.build().normalized();
        assertEquals(1, normalized.initialZones().size());
        assertEquals(DECK_ID, normalized.initialZoneFor(null).id());
    }

    @Test
    void perSeatInitialIsDroppedByNormalization()
    {
        TableLayoutDefinition.Builder builder = TableLayoutDefinition.builder(new ResourceLocation("test", "layout"));
        builder.zone(zone("deck", ZoneDefinition.Kind.STACK, ZoneDefinition.Scope.PER_SEAT, 0.02F, 0.02F, 0.06F, 0.12F));
        builder.initial(TableLayoutDefinition.INITIAL_DEFAULT_KEY,
                new ResourceLocation("test", "deck"));

        assertTrue(builder.build().normalized().initialZones().isEmpty());
        assertNull(builder.build().normalized().initialZoneFor(new ResourceLocation("test", "set")));
    }

    @Test
    void actionTableSurvivesNormalizationWhenSourcesResolve()
    {
        TableLayoutDefinition normalized = layoutBuilder()
                .action(action("draw", TableActionDefinition.Type.DRAW, DECK_ID, "key.keyboard.d"))
                .action(action("shuffle", TableActionDefinition.Type.SHUFFLE, DECK_ID, "key.keyboard.s"))
                .action(action("flip", TableActionDefinition.Type.FLIP, null, "key.keyboard.f"))
                .build().normalized();

        assertEquals(3, normalized.actions().size());
        assertEquals(TableActionDefinition.Type.DRAW, normalized.actions().get(0).type());
        assertEquals("key.keyboard.d", normalized.actions().get(0).key());
        assertNull(normalized.actions().get(2).sourceZone());
    }

    @Test
    void danglingActionSourcesAreDroppedByNormalization()
    {
        TableLayoutDefinition normalized = layoutBuilder()
                .action(action("draw", TableActionDefinition.Type.DRAW, DISCARD_ID, "key.keyboard.d"))
                .build().normalized();

        assertTrue(normalized.actions().isEmpty());
    }

    @Test
    void actionBuildersValidateTheirFields()
    {
        assertThrows(IllegalStateException.class,
                () -> TableActionDefinition.builder(new ResourceLocation("test", "x")).build());
        assertThrows(IllegalStateException.class, () -> TableActionDefinition
                .builder(new ResourceLocation("test", "x")).type(TableActionDefinition.Type.DRAW).amount(0).build());
        assertThrows(IllegalStateException.class, () -> TableActionDefinition
                .builder(new ResourceLocation("test", "x")).type(TableActionDefinition.Type.DRAW).build());
        assertThrows(IllegalStateException.class, () -> TableActionDefinition
                .builder(new ResourceLocation("test", "x")).type(TableActionDefinition.Type.SHUFFLE).build());
    }

    @Test
    void normalizationIsIdempotent()
    {
        TableLayoutDefinition layout = layoutBuilder()
                .zone(ZoneDefinition.builder(TableLayoutDefinition.ZONE_FREE)
                        .kind(ZoneDefinition.Kind.FREE).scope(ZoneDefinition.Scope.PER_SEAT)
                        .rect(0.4F, 0.8F, 0.05F, 0.10F).build())
                .action(action("draw", TableActionDefinition.Type.DRAW, DECK_ID, "key.keyboard.d"))
                .build();
        TableLayoutDefinition once = layout.normalized();
        TableLayoutDefinition twice = once.normalized();
        assertEquals(once.zones().size(), twice.zones().size());
        assertEquals(once.actions().size(), twice.actions().size());
        assertEquals(once.initialZones(), twice.initialZones());
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
        TableLayoutDefinition layout = TableLayoutDefinition.builder(new ResourceLocation("test", "layout")).build();
        assertNull(layout.zone(new ResourceLocation("test", "nope")));
        assertTrue(layout.zones().isEmpty());
        assertFalse(layout.pileZones().iterator().hasNext());
    }
}
