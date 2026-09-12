package com.example.cardtable.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the pure colour assignment of remote cursors: the same UUID always
 * yields the same ARGB, different UUIDs land somewhere in the fixed palette,
 * and every colour is a fully opaque sRGB value.
 */
class ClientCursorStoreTest
{
    private static final int[] PALETTE = {
            0xFFE6194B,
            0xFF3CB44B,
            0xFFFFE119,
            0xFF4363D8,
            0xFFF58231,
            0xFF911EB4,
            0xFF42D4F4,
            0xFFF032E6,
    };

    @Test
    void sameUuidAlwaysMapsToSameColor()
    {
        UUID playerId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        int first = ClientCursorStore.colorFor(playerId);
        int second = ClientCursorStore.colorFor(playerId);
        assertEquals(first, second, "colour assignment must be a pure function of the UUID");
    }

    @Test
    void colorLandsInFixedPalette()
    {
        for (int i = 0; i < 64; i++)
        {
            int color = ClientCursorStore.colorFor(new UUID(i, i * 31L + 7L));
            assertTrue(contains(PALETTE, color),
                    "colour " + Integer.toHexString(color) + " must come from the fixed palette");
        }
    }

    @Test
    void paletteColorsAreOpaqueAndDistinct()
    {
        assertEquals(PALETTE.length, (int) java.util.Arrays.stream(PALETTE).distinct().count(),
                "the palette must offer eight distinct colours");
        for (int color : PALETTE)
        {
            assertEquals(0xFF, (color >>> 24) & 0xFF,
                    "palette entry " + Integer.toHexString(color) + " must be fully opaque");
        }
    }

    private static boolean contains(int[] haystack, int needle)
    {
        for (int value : haystack)
        {
            if (value == needle)
            {
                return true;
            }
        }
        return false;
    }
}
