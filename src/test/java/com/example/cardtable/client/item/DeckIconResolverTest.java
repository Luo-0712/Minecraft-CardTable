package com.example.cardtable.client.item;

import com.example.cardtable.api.CardSetDefinition;
import com.example.cardtable.CardTableMod;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the fallback ordering of the two sprite chains derived from a deck's
 * set: the item icon chain (icon first) and the world-side pile chain (card
 * back first, because that pile lies face-down). Both must always end on the
 * core sprite so a half-authored pack still draws something.
 */
class DeckIconResolverTest
{
    private static final ResourceLocation ICON = new ResourceLocation("cardtable", "set_test/icon");
    private static final ResourceLocation BACK = new ResourceLocation("cardtable", "set_test/back");

    private static CardSetDefinition set(boolean withIcon, boolean withBack)
    {
        CardSetDefinition.Builder builder = CardSetDefinition.builder(new ResourceLocation("cardtable", "set_test/set"))
                .displayName(Component.literal("Resolver test set"));
        if (withIcon)
        {
            builder.iconTexture(ICON);
        }
        if (withBack)
        {
            builder.defaultBackTexture(BACK);
        }
        return builder.build();
    }

    @Test
    void iconChainPrefersIconOverBack()
    {
        List<ResourceLocation> candidates = DeckIconResolver.iconCandidates(set(true, true));
        assertEquals(List.of(ICON, BACK, DeckIconResolverTest.coreId()), candidates,
                "item sprite chain must run icon -> back -> core");
    }

    @Test
    void backChainPrefersBackOverIcon()
    {
        List<ResourceLocation> candidates = DeckIconResolver.backCandidates(set(true, true));
        assertEquals(List.of(BACK, ICON, DeckIconResolverTest.coreId()), candidates,
                "world pile chain must run back -> icon -> core");
    }

    @Test
    void chainsDropMissingOptionalTextures()
    {
        assertEquals(List.of(BACK, coreId()), DeckIconResolver.iconCandidates(set(false, true)),
                "icon-less set degrades to back -> core for the item sprite");
        assertEquals(List.of(ICON, coreId()), DeckIconResolver.backCandidates(set(true, false)),
                "back-less set degrades to icon -> core for the world pile");
    }

    @Test
    void chainsAlwaysEndAtCoreSprite()
    {
        assertEquals(List.of(coreId()), DeckIconResolver.iconCandidates(null),
                "unknown set must still end at the core sprite (icon chain)");
        assertEquals(List.of(coreId()), DeckIconResolver.backCandidates(set(false, false)),
                "bare set must still end at the core sprite (back chain)");
    }

    private static ResourceLocation coreId()
    {
        return new ResourceLocation(CardTableMod.MODID, "item/deck");
    }
}
