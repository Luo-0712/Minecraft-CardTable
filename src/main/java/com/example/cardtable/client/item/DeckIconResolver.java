package com.example.cardtable.client.item;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.api.CardRegistry;
import com.example.cardtable.api.CardSetDefinition;
import com.example.cardtable.client.card.CardTextureResolver;
import com.example.cardtable.item.DeckItem;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a deck stack's {@code DeckId} into the sprite used to draw its item,
 * following the same fallback chain the pack format documents:
 *
 * <ol>
 *   <li>the set's declared {@code set.icon} texture;</li>
 *   <li>otherwise that set's card back ({@code set.back}) thumbnail;</li>
 *   <li>otherwise the core {@code cardtable:item/deck} sprite.</li>
 * </ol>
 *
 * <p>A missing file just drops to the next step, so packs authored before the
 * {@code icon} field existed — or half-authored ones — always draw something
 * sensible instead of erroring. The chain never throws and always yields an
 * icon.</p>
 *
 * <p>A second, back-first chain ({@link #backForStack}) serves the world-side
 * deck pile: identical fallbacks, but the card back leads because that pile
 * lies face-down on the table.</p>
 *
 * <p>Resolution is memoised per deck id. The chain touches NBT, the
 * {@link CardRegistry} and {@link RenderType} construction, none of which may
 * run once per frame, so the result is cached as immutable data only (the
 * texture id, the letterboxed quad size, and the render type built from that
 * id). The data cannot go stale across resource reloads: a reload re-registers
 * dynamic textures under exactly the same ids, and a pack's set definitions are
 * frozen before the client ever renders.</p>
 */
public final class DeckIconResolver
{
    /** Core fallback: the generic deck sprite shipped by the mod itself. */
    private static final ResourceLocation CORE_TEXTURE_ID = new ResourceLocation(CardTableMod.MODID, "item/deck");

    /** Cache key standing in for "this stack carries no DeckId at all". */
    private static final String UNBOUND_KEY = "";

    private static final Map<String, DeckIcon> CACHE = new HashMap<>();
    /** Separate memo for the back-first chain; same keys, different fallback order. */
    private static final Map<String, DeckIcon> BACK_CACHE = new HashMap<>();

    private DeckIconResolver()
    {
    }

    /** A resolved, ready-to-draw deck sprite and how much of the item quad it fills. */
    public record DeckIcon(ResourceLocation texture, RenderType renderType, float quadWidth, float quadHeight)
    {
    }

    /** Resolves the sprite for a deck stack; unbound stacks fall through to the core icon. */
    public static DeckIcon forStack(ItemStack stack)
    {
        return forStack(stack, CACHE, DeckIconResolver::compute);
    }

    /**
     * Resolves the sprite for the world-side deck pile: the card back leads
     * the chain because the pile lies face-down, with the set icon and the
     * core sprite as fallbacks. Unbound stacks fall through to the core icon.
     */
    public static DeckIcon backForStack(ItemStack stack)
    {
        return forStack(stack, BACK_CACHE, DeckIconResolver::computeBack);
    }

    private static DeckIcon forStack(ItemStack stack, Map<String, DeckIcon> cache,
            java.util.function.Function<String, DeckIcon> loader)
    {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(DeckItem.DECK_ID_TAG, CompoundTag.TAG_STRING))
        {
            return CACHE.computeIfAbsent(UNBOUND_KEY, key -> coreIcon());
        }
        return cache.computeIfAbsent(tag.getString(DeckItem.DECK_ID_TAG), loader);
    }

    /** Drops the memoised sprites; called from client setup, before anything renders. */
    public static void clearCache()
    {
        CACHE.clear();
        BACK_CACHE.clear();
    }

    private static DeckIcon compute(String deckIdString)
    {
        return firstAvailable(iconCandidates(parseSet(deckIdString)));
    }

    private static DeckIcon computeBack(String deckIdString)
    {
        return firstAvailable(backCandidates(parseSet(deckIdString)));
    }

    /**
     * First drawable sprite of a candidate chain; the core icon is the
     * terminal candidate, so this always yields something.
     */
    private static DeckIcon firstAvailable(List<ResourceLocation> candidates)
    {
        for (ResourceLocation textureId : candidates)
        {
            DeckIcon icon = fromTexture(textureId);
            if (icon != null)
            {
                return icon;
            }
        }
        return coreIcon();
    }

    @Nullable
    private static CardSetDefinition parseSet(String deckIdString)
    {
        if (deckIdString.isEmpty())
        {
            return null;
        }
        try
        {
            return CardRegistry.getSet(new ResourceLocation(deckIdString));
        }
        catch (Exception exception)
        {
            // A malformed DeckId draws as an unbound deck; the item tooltip
            // already reports the same problem to the player.
            return null;
        }
    }

    /**
     * Ordered texture candidates for the deck item sprite: the set icon, the
     * set back, then the core sprite. Pure function of the set definition —
     * no texture lookup — so tests can lock the fallback order headless.
     */
    static List<ResourceLocation> iconCandidates(@Nullable CardSetDefinition set)
    {
        List<ResourceLocation> candidates = new ArrayList<>(3);
        if (set != null)
        {
            if (set.iconTexture() != null)
            {
                candidates.add(set.iconTexture());
            }
            if (set.defaultBackTexture() != null)
            {
                candidates.add(set.defaultBackTexture());
            }
        }
        candidates.add(CORE_TEXTURE_ID);
        return candidates;
    }

    /**
     * Ordered texture candidates for the world-side deck pile: the set back
     * (the pile lies face-down), the set icon, then the core sprite. Pure
     * function of the set definition, mirroring {@link #iconCandidates}.
     */
    static List<ResourceLocation> backCandidates(@Nullable CardSetDefinition set)
    {
        List<ResourceLocation> candidates = new ArrayList<>(3);
        if (set != null)
        {
            if (set.defaultBackTexture() != null)
            {
                candidates.add(set.defaultBackTexture());
            }
            if (set.iconTexture() != null)
            {
                candidates.add(set.iconTexture());
            }
        }
        candidates.add(CORE_TEXTURE_ID);
        return candidates;
    }

    /**
     * Builds the icon for a declared texture, or {@code null} when there is no
     * file behind that id so the caller can try the next fallback step.
     */
    @Nullable
    private static DeckIcon fromTexture(@Nullable ResourceLocation textureId)
    {
        if (textureId == null || !CardTextureResolver.isAvailable(textureId))
        {
            return null;
        }
        CardTextureResolver.Binding binding = CardTextureResolver.resolve(textureId).orElse(null);
        return binding == null ? null : toIcon(textureId, binding.width(), binding.height());
    }

    private static DeckIcon coreIcon()
    {
        CardTextureResolver.Binding binding = CardTextureResolver.resolve(CORE_TEXTURE_ID).orElse(null);
        if (binding == null)
        {
            return toIcon(CORE_TEXTURE_ID, 1, 1);
        }
        return toIcon(CORE_TEXTURE_ID, binding.width(), binding.height());
    }

    /**
     * Picks the letterboxed quad size for a texture: square (or landscape)
     * textures fill the item, portrait ones keep their aspect ratio and end up
     * pillarboxed, so the 2:3 card backs routinely used as icons stay
     * undistorted instead of being stretched into a square.
     *
     * <p>The id is used verbatim rather than the binding's location: that
     * location is a resource path for static textures ({@code textures/x.png})
     * but a TextureManager key for dynamic ones, and only the id is correct for
     * both.</p>
     */
    private static DeckIcon toIcon(ResourceLocation textureId, int pixelWidth, int pixelHeight)
    {
        float width = pixelWidth > 0 ? pixelWidth : 1.0F;
        float height = pixelHeight > 0 ? pixelHeight : 1.0F;
        float quadWidth = 1.0F;
        float quadHeight = 1.0F;
        if (width > height)
        {
            quadHeight = height / width;
        }
        else if (height > width)
        {
            quadWidth = width / height;
        }
        // Render types are memoised by the vanilla factories, but building one
        // is still work this per-frame hot path must not repeat.
        return new DeckIcon(textureId, RenderType.entityCutoutNoCull(textureId), quadWidth, quadHeight);
    }
}
