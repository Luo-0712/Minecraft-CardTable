package com.example.cardtable.client.card;

import com.example.cardtable.content.ContentPack;
import com.example.cardtable.content.ContentPackLoader;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Single entry point turning a {@code CardDefinition} texture id into
 * something {@code GuiGraphics.blit} can draw, with the PNG's true pixel
 * size. Two sources feed it:
 *
 * <ul>
 *   <li>static mod resources ({@code cardtable:...} ids), resolved through
 *       the normal resource manager with a PNG-header size probe;</li>
 *   <li>file content packs ({@code cardtable_dyn:...} ids), whose PNG bytes
 *       are registered as {@link DynamicTexture}s once during client setup.</li>
 * </ul>
 *
 * <p>Dynamic registration is repeatable: resource reloads (F3+T) and
 * reconnects re-run client setup registration, and the resolver never caches
 * stale texture objects (only their immutable bindings).</p>
 */
public final class CardTextureResolver
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String DYNAMIC_NAMESPACE = ContentPackLoader.DYNAMIC_NAMESPACE;

    /** A drawable texture reference with its pixel size. */
    public record Binding(ResourceLocation location, int width, int height)
    {
    }

    private static final int FALLBACK_SIZE = 64;
    private static final Map<ResourceLocation, Binding> DYNAMIC_BINDINGS = new HashMap<>();
    private static final Map<ResourceLocation, Binding> STATIC_BINDINGS = new HashMap<>();

    private CardTextureResolver()
    {
    }

    /** Registers every file-pack texture as a dynamic texture; runs once per client setup. */
    public static void registerDynamicTextures()
    {
        for (ContentPack pack : ContentPackLoader.loadedPacks())
        {
            pack.dynamicTextures().forEach((path, bytes) -> {
                ResourceLocation id = new ResourceLocation(DYNAMIC_NAMESPACE, path);
                try
                {
                    NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
                    DynamicTexture texture = new DynamicTexture(image);
                    Minecraft.getInstance().getTextureManager().register(id, texture);
                    DYNAMIC_BINDINGS.put(id, new Binding(id, image.getWidth(), image.getHeight()));
                }
                catch (Exception exception)
                {
                    LOGGER.warn("Failed to register dynamic card texture {}: {}", id, exception.toString());
                }
            });
        }
    }

    /** Resolves a texture id to a drawable binding, or empty when unknown. */
    public static Optional<Binding> resolve(ResourceLocation textureId)
    {
        if (DYNAMIC_NAMESPACE.equals(textureId.getNamespace()))
        {
            return Optional.ofNullable(DYNAMIC_BINDINGS.get(textureId));
        }
        return Optional.ofNullable(STATIC_BINDINGS.computeIfAbsent(textureId, CardTextureResolver::readStaticBinding));
    }

    /** Drops every cached binding (client teardown / reload hygiene). */
    public static void clearCaches()
    {
        DYNAMIC_BINDINGS.clear();
        STATIC_BINDINGS.clear();
    }

    // Reads the PNG IHDR chunk for the true pixel size; texture ids map to
    // resources under textures/<path>.png, and the returned binding carries
    // that full resource path because GuiGraphics.blit loads ids literally.
    @Nullable
    private static Binding readStaticBinding(ResourceLocation textureId)
    {
        ResourceLocation resourcePath = new ResourceLocation(textureId.getNamespace(),
                "textures/" + textureId.getPath() + ".png");
        try
        {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(resourcePath);
            if (resource.isEmpty())
            {
                return new Binding(resourcePath, FALLBACK_SIZE, FALLBACK_SIZE);
            }
            try (InputStream stream = resource.get().open())
            {
                byte[] header = stream.readNBytes(24);
                if (header.length >= 24 && header[12] == 'I' && header[13] == 'H')
                {
                    int width = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                            | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
                    int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                            | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);
                    if (width > 0 && height > 0)
                    {
                        return new Binding(resourcePath, width, height);
                    }
                }
            }
        }
        catch (Exception ignored)
        {
            // Missing texture: fall through to the square fallback so the
            // card still draws (as a missing-texture checker) instead of vanishing.
        }
        return new Binding(resourcePath, FALLBACK_SIZE, FALLBACK_SIZE);
    }
}
