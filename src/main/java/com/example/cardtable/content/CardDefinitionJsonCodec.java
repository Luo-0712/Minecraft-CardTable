package com.example.cardtable.content;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.api.CardDefinition;
import com.example.cardtable.api.CardSetDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Parses and validates content pack JSON, and produces the canonical
 * serialization that feeds the {@code contentHash} used by the consistency
 * handshake.
 *
 * <p>The canonical form is derived from the parsed model, not the raw file:
 * key order, whitespace and numeric spellings in the authored JSON cannot
 * change the hash, while any change to a meaning-bearing field always does.
 * The exact line formats below are a builder-ecosystem contract; changing
 * them is a breaking protocol change.</p>
 *
 * <pre>
 * set|&lt;setId&gt;|&lt;defaultBack|->|&lt;displayJson&gt;
 * card|&lt;cardId&gt;|&lt;cardSet|-&gt;|&lt;front&gt;|&lt;back|-&gt;|&lt;sortIndex&gt;|&lt;displayJson&gt;
 * </pre>
 */
public final class CardDefinitionJsonCodec
{
    /** Content pack format understood by this build; packs with other formats are skipped. */
    public static final int FORMAT = 1;

    /** Relational separator used by the canonical lines above. */
    private static final String CANONICAL_SEPARATOR = "|";
    private static final String CANONICAL_NULL = "-";

    private CardDefinitionJsonCodec()
    {
    }

    /** The {@code pack.json} header of a content pack. */
    public record PackMeta(ResourceLocation id, String name, String version, @Nullable SetMeta set)
    {
        /** Optional set declared by the pack; {@code back} is a pack-relative texture path. */
        public record SetMeta(String name, @Nullable String back)
        {
        }
    }

    /** One parsed {@code cards.json} entry, ready for event registration. */
    public record ParsedCard(CardDefinition definition, String canonicalLine)
    {
    }

    /** Maps a pack-relative texture path (no extension) to the final texture id. */
    public interface TextureMapper
    {
        ResourceLocation textureId(String relativePath);
    }

    // pack.json ------------------------------------------------------------

    public static PackMeta parsePackMeta(JsonObject json)
    {
        int format = json.get("format").getAsInt();
        if (format != FORMAT)
        {
            throw new JsonParseException("Unsupported content pack format: " + format);
        }
        String rawId = requiredString(json, "id");
        ResourceLocation id = parsePackId(rawId);
        String name = requiredString(json, "name");
        String version = requiredString(json, "version");

        PackMeta.SetMeta setMeta = null;
        if (json.has("set") && json.get("set").isJsonObject())
        {
            JsonObject setJson = json.getAsJsonObject("set");
            setMeta = new PackMeta.SetMeta(requiredString(setJson, "name"),
                    optionalString(setJson, "back"));
        }
        return new PackMeta(id, name, version, setMeta);
    }

    // A pack id without a namespace defaults to the core mod id, so a builder
    // can emit "my_deck" and get "cardtable:my_deck" rather than minecraft:.
    private static ResourceLocation parsePackId(String rawId)
    {
        return rawId.indexOf(':') >= 0
                ? new ResourceLocation(rawId)
                : new ResourceLocation(CardTableMod.MODID, rawId);
    }

    // cards.json -----------------------------------------------------------

    public static ParsedCard parseCard(JsonObject json, PackMeta pack, TextureMapper textures)
    {
        String relativeId = requiredString(json, "id");
        ResourceLocation cardId = childId(pack.id(), relativeId);

        Component displayName = Component.Serializer.fromJson(json.get("display_name"));
        if (displayName == null)
        {
            throw new JsonParseException("Card " + relativeId + " is missing display_name");
        }

        String frontPath = requiredString(json, "front");
        String backPath = optionalString(json, "back");
        String relativeSet = optionalString(json, "set");
        int sortIndex = json.has("sort") && json.get("sort").isJsonPrimitive()
                ? json.get("sort").getAsInt() : 0;

        ResourceLocation cardSet = relativeSet != null ? childId(pack.id(), relativeSet) : pack.id();
        ResourceLocation front = textures.textureId(frontPath);
        ResourceLocation back = backPath != null ? textures.textureId(backPath) : null;

        CardDefinition definition = CardDefinition.builder(cardId)
                .displayName(displayName)
                .frontTexture(front)
                .backTexture(back)
                .cardSet(cardSet)
                .sortIndex(sortIndex)
                .build();

        String displayJson = Component.Serializer.toJson(displayName).toString();
        String line = String.join(CANONICAL_SEPARATOR, "card",
                cardId.toString(),
                cardSet != null ? cardSet.toString() : CANONICAL_NULL,
                front.toString(),
                back != null ? back.toString() : CANONICAL_NULL,
                Integer.toString(sortIndex),
                displayJson);
        return new ParsedCard(definition, line);
    }

    /** Registers the pack's optional set and returns its canonical line. */
    @Nullable
    public static String registerSet(PackMeta pack, TextureMapper textures,
                                     java.util.function.Consumer<CardSetDefinition> sink)
    {
        PackMeta.SetMeta setMeta = pack.set();
        if (setMeta == null)
        {
            return null;
        }
        ResourceLocation back = setMeta.back() != null ? textures.textureId(setMeta.back()) : null;
        CardSetDefinition set = CardSetDefinition.builder(pack.id())
                .displayName(Component.literal(setMeta.name()))
                .defaultBackTexture(back)
                .build();
        sink.accept(set);
        return String.join(CANONICAL_SEPARATOR, "set",
                set.id().toString(),
                back != null ? back.toString() : CANONICAL_NULL,
                Component.Serializer.toJson(set.displayName()).toString());
    }

    // Canonical hashing ----------------------------------------------------

    /**
     * Deterministic pack digest: canonical lines (set + cards), sorted,
     * joined, SHA-256. Both handshake peers must derive the same hash from
     * semantically identical packs.
     */
    public static String contentHash(List<String> canonicalLines)
    {
        List<String> sorted = new ArrayList<>(canonicalLines);
        sorted.sort(String::compareTo);
        return sha256Hex(String.join("\n", sorted));
    }

    public static String sha256Hex(String data)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception)
        {
            // SHA-256 is mandatory on every Java 17 platform.
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    // Helpers ----------------------------------------------------------------

    private static ResourceLocation childId(ResourceLocation packId, String relative)
    {
        Objects.requireNonNull(relative, "relative id");
        if (relative.indexOf(':') >= 0)
        {
            throw new JsonParseException("Relative id must not carry a namespace: " + relative);
        }
        return new ResourceLocation(packId.getNamespace(), packId.getPath() + "/" + relative.toLowerCase(Locale.ROOT));
    }

    private static String requiredString(JsonObject json, String key)
    {
        if (!json.has(key) || !json.get(key).isJsonPrimitive())
        {
            throw new JsonParseException("Missing required string field: " + key);
        }
        return json.get(key).getAsString();
    }

    @Nullable
    private static String optionalString(JsonObject json, String key)
    {
        if (!json.has(key) || json.get(key).isJsonNull())
        {
            return null;
        }
        return requiredString(json, key);
    }
}
