package com.example.cardtable.content;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * One loaded content pack: identity for the consistency handshake plus, for
 * file-backed packs, the texture bytes that the client registers as dynamic
 * textures under the {@code cardtable_dyn} namespace.
 *
 * <p>{@code contentHash} is a SHA-256 over the pack's canonicalized content,
 * so two clients agree only when both the declared version and every parsed
 * card/set line match. See {@link CardDefinitionJsonCodec} for the canonical
 * form.</p>
 */
public record ContentPack(ResourceLocation id, String name, String version, String contentHash,
                          Map<String, byte[]> dynamicTextures)
{
    public ContentPack
    {
        dynamicTextures = Map.copyOf(dynamicTextures);
    }

    /** Pack id {@code ns:path} for a texture-less (classpath or mod) pack. */
    public static ContentPack textureless(ResourceLocation id, String name, String version, String contentHash)
    {
        return new ContentPack(id, name, version, contentHash, Map.of());
    }
}
