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
import java.util.HashMap;
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
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(DeckItem.DECK_ID_TAG, CompoundTag.TAG_STRING))
        {
            return CACHE.computeIfAbsent(UNBOUND_KEY, key -> coreIcon());
        }
        return CACHE.computeIfAbsent(tag.getString(DeckItem.DECK_ID_TAG), DeckIconResolver::compute);
    }

    /** Drops the memoised sprites; called from client setup, before anything renders. */
    public static void clearCache()
    {
        CACHE.clear();
    }

    private static DeckIcon compute(String deckIdString)
    {
        CardSetDefinition set = parseSet(deckIdString);
        if (set != null)
        {
            DeckIcon icon = fromTexture(set.iconTexture());
            if (icon == null)
            {
                icon = fromTexture(set.defaultBackTexture());
            }
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
