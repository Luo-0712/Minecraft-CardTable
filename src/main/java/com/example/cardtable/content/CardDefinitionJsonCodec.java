package com.example.cardtable.content;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.api.CardDefinition;
import com.example.cardtable.api.CardSetDefinition;
import com.example.cardtable.api.TableLayoutDefinition;
import com.example.cardtable.api.ZoneDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.net.URLEncoder;
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
 * layout|&lt;setId&gt;|&lt;nameEncoded|-&gt;|&lt;zoneDescriptor&gt;|...   (format 2)
 * </pre>
 */
public final class CardDefinitionJsonCodec
{
    /** Lowest accepted pack format (the frozen builder-ecosystem contract). */
    public static final int FORMAT = 1;
    /** Highest accepted pack format; 2 adds optional {@code layout.json} support. */
    public static final int MAX_FORMAT = 2;

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
        if (format < FORMAT || format > MAX_FORMAT)
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

    // layout.json (format 2) ------------------------------------------------

    /** One parsed {@code layout.json}, ready for event registration. */
    public record ParsedLayout(TableLayoutDefinition definition, String canonicalLine)
    {
    }

    /**
     * Parses a pack's optional {@code layout.json} and registers it as the
     * pack-id layout (the pack set references it implicitly). Per-zone errors
     * are skipped with a warning, mirroring the "one broken card must not
     * sink the pack" policy; a layout without any valid zone returns
     * {@code null} so the set falls back to the built-in default.
     *
     * @return the parsed layout with its canonical line, or {@code null} when
     *         nothing valid remains
     */
    @Nullable
    public static ParsedLayout parseLayout(@Nullable JsonElement root, PackMeta pack, Logger log)
    {
        if (root == null || root.isJsonNull())
        {
            return null;
        }
        if (!root.isJsonObject())
        {
            log.warn("Pack {}: layout.json is not an object, ignored", pack.id());
            return null;
        }
        JsonObject json = root.getAsJsonObject();

        Component displayName = null;
        if (json.has("name") && json.get("name").isJsonPrimitive())
        {
            displayName = Component.literal(json.get("name").getAsString());
        }

        TableLayoutDefinition.Builder builder = TableLayoutDefinition.builder(pack.id())
                .displayName(displayName);
        int accepted = 0;
        if (json.has("zones") && json.get("zones").isJsonArray())
        {
            JsonArray zones = json.getAsJsonArray("zones");
            for (int index = 0; index < zones.size(); index++)
            {
                JsonElement element = zones.get(index);
                if (!element.isJsonObject())
                {
                    log.warn("Pack {}: layout zone #{} is not an object, skipped", pack.id(), index);
                    continue;
                }
                try
                {
                    builder.zone(parseZone(element.getAsJsonObject(), pack));
                    accepted++;
                }
                catch (Exception exception)
                {
                    log.warn("Pack {}: skipping broken layout zone #{}: {}",
                            pack.id(), index, exception.toString());
                }
            }
        }
        else
        {
            log.warn("Pack {}: layout.json is missing the zones array", pack.id());
        }
        if (accepted == 0)
        {
            log.warn("Pack {}: layout has no valid zones, layout not registered", pack.id());
            return null;
        }

        TableLayoutDefinition definition = builder.build();
        return new ParsedLayout(definition, layoutCanonicalLine(definition));
    }

    // Parses one zone object; every failure surfaces as an exception so the
    // caller can skip just this entry.
    private static ZoneDefinition parseZone(JsonObject json, PackMeta pack)
    {
        String rawId = requiredString(json, "id");
        ResourceLocation zoneId = parseZoneId(rawId, pack);

        ZoneDefinition.Kind kind = parseEnum(requiredString(json, "kind"), ZoneDefinition.Kind.values(), "kind");
        ZoneDefinition.Scope scope = parseEnum(requiredString(json, "scope"), ZoneDefinition.Scope.values(), "scope");
        ZoneDefinition.Visibility visibility = ZoneDefinition.Visibility.PUBLIC;
        String rawVisibility = optionalString(json, "visibility");
        if (rawVisibility != null)
        {
            // D3: the first iteration only accepts public; owner_only is a
            // reserved model value that content cannot declare yet.
            visibility = parseEnum(rawVisibility, ZoneDefinition.Visibility.values(), "visibility");
            if (visibility != ZoneDefinition.Visibility.PUBLIC)
            {
                throw new JsonParseException("Zone " + rawId + " declares unsupported visibility: " + rawVisibility);
            }
        }

        if (!json.has("rect") || !json.get("rect").isJsonArray() || json.getAsJsonArray("rect").size() != 4)
        {
            throw new JsonParseException("Zone " + rawId + " needs a rect array of four numbers");
        }
        JsonArray rect = json.getAsJsonArray("rect");
        float[] values = new float[4];
        for (int i = 0; i < 4; i++)
        {
            JsonElement entry = rect.get(i);
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isNumber())
            {
                throw new JsonParseException("Zone " + rawId + " rect entry #" + i + " is not a number");
            }
            values[i] = entry.getAsFloat();
        }

        int capacity = 0;
        if (json.has("capacity") && json.get("capacity").isJsonPrimitive())
        {
            capacity = json.get("capacity").getAsInt();
        }

        Component label = null;
        if (json.has("label") && json.get("label").isJsonObject())
        {
            label = Component.Serializer.fromJson(json.get("label"));
        }

        return ZoneDefinition.builder(zoneId)
                .kind(kind).scope(scope)
                .rect(values[0], values[1], values[2], values[3])
                .capacity(capacity)
                .visibility(visibility)
                .label(label)
                .build();
    }

    // Relative ids resolve into the pack namespace/path; the only legal
    // namespace-bearing id is a full overridable built-in id.
    private static ResourceLocation parseZoneId(String rawId, PackMeta pack)
    {
        if (rawId.indexOf(':') >= 0)
        {
            ResourceLocation full = new ResourceLocation(rawId);
            if (CardTableMod.MODID.equals(full.getNamespace())
                    && (TableLayoutDefinition.ZONE_DRAW_PILE.equals(full)
                        || TableLayoutDefinition.ZONE_DISCARD_PILE.equals(full)
                        || TableLayoutDefinition.ZONE_FREE.equals(full)))
            {
                return full;
            }
            throw new JsonParseException("Zone id '" + rawId + "' must be a pack-relative id"
                    + " (or an overridable cardtable: built-in)");
        }
        String lower = rawId.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lower.length(); i++)
        {
            char c = lower.charAt(i);
            boolean legal = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '.' || c == '_' || c == '-';
            if (!legal)
            {
                throw new JsonParseException("Zone id '" + rawId + "' contains an illegal character: " + c);
            }
        }
        return new ResourceLocation(pack.id().getNamespace(), pack.id().getPath() + "/" + lower);
    }

    private static <E extends Enum<E>> E parseEnum(String raw, E[] values, String field)
    {
        for (E value : values)
        {
            if (value.name().toLowerCase(Locale.ROOT).equals(raw))
            {
                return value;
            }
        }
        throw new JsonParseException("Unknown " + field + ": " + raw);
    }

    /**
     * The {@code layout|} canonical line: layout name plus one descriptor per
     * zone, sorted by full zone id, percent-encoded free text. The float
     * format ({@code Float.toString}) and the encoding are part of the
     * builder-ecosystem contract.
     */
    public static String layoutCanonicalLine(TableLayoutDefinition layout)
    {
        List<ZoneDefinition> zones = new ArrayList<>(layout.zones());
        zones.sort((a, b) -> a.id().toString().compareTo(b.id().toString()));
        List<String> descriptors = new ArrayList<>();
        for (ZoneDefinition zone : zones)
        {
            descriptors.add(String.join("#",
                    zone.id().toString(),
                    zone.kind().name().toLowerCase(Locale.ROOT),
                    zone.scope().name().toLowerCase(Locale.ROOT),
                    Float.toString(zone.x()) + "," + Float.toString(zone.y())
                            + "," + Float.toString(zone.w()) + "," + Float.toString(zone.h()),
                    Integer.toString(zone.capacity()),
                    zone.visibility().name().toLowerCase(Locale.ROOT),
                    zone.label() != null ? percentEncode(zone.label().getString()) : CANONICAL_NULL));
        }
        return String.join(CANONICAL_SEPARATOR, "layout",
                layout.id().toString(),
                layout.displayName() != null ? percentEncode(layout.displayName().getString()) : CANONICAL_NULL,
                String.join(CANONICAL_SEPARATOR, descriptors));
    }

    // RFC 3986 application/x-www-form-urlencoded style with %20 for spaces,
    // so any label survives the '|' and '#' separators of the line format.
    private static String percentEncode(String raw)
    {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
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
