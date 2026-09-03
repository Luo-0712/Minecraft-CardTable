package com.example.cardtable.content;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.api.CardDefinition;
import com.example.cardtable.api.CardRegistry;
import com.example.cardtable.api.RegisterCardDefinitionsEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads card content once during common setup: built-in packs shipped inside
 * this mod's jar and user packs dropped into {@code config/cardtable/packs/}
 * (a {@code .zip} or a directory, both with the same layout). Every pack
 * goes through the same JSON codec, so the built-in standard deck dogfoods
 * the exact format the external card builder will emit.
 *
 * <p>After the file-backed packs are parsed, a
 * {@link RegisterCardDefinitionsEvent} is posted on the mod bus so third-party
 * mods can register programmatic content, and the final snapshot is frozen
 * into {@link CardRegistry}. A single broken card entry is skipped with a
 * warning; a broken {@code pack.json} skips its whole pack.</p>
 *
 * <p>Pack layout:</p>
 * <pre>
 * pack.json    { "format": 1, "id": "ns:name", "name": "...", "version": "...",
 *                "set": { "name": "...", "back": "back" } }
 * cards.json   [ { "id": "ace_of_spades", "set": "...", "display_name": {...},
 *                "front": "ace_of_spades", "back": "back", "sort": 0 }, ... ]
 * textures/    pack-relative PNGs referenced by front/back paths (file packs only)
 * </pre>
 */
public final class ContentPackLoader
{
    private static final Logger LOGGER = LogUtils.getLogger();

    /** User content packs: {@code config/cardtable/packs/*.zip} or subdirectories. */
    public static final Path PACKS_DIR = FMLPaths.CONFIGDIR.get().resolve(CardTableMod.MODID).resolve("packs");

    /** Dynamic-texture namespace under which file pack textures are registered client-side. */
    public static final String DYNAMIC_NAMESPACE = "cardtable_dyn";

    /** Built-in packs shipped under {@code assets/cardtable/cardpacks/<name>/}. */
    private static final List<String> BUILTIN_PACKS = List.of("standard");

    /** Single texture size cap; larger files are treated as broken content. */
    private static final long MAX_TEXTURE_BYTES = 4L * 1024L * 1024L;

    private static final List<ContentPack> loadedPacks = new ArrayList<>();

    private ContentPackLoader()
    {
    }

    /** Hooks pack loading into the mod lifecycle; call once from the mod constructor. */
    public static void bootstrap(IEventBus modBus)
    {
        modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(() -> loadAll(modBus)));
    }

    /**
     * Loads all content and freezes {@link CardRegistry}. Runs once on the
     * main thread during common setup, before any world or menu can touch it.
     */
    public static void loadAll(IEventBus modBus)
    {
        Objects.requireNonNull(modBus, "modBus");
        loadedPacks.clear();
        RegisterCardDefinitionsEvent registration = new RegisterCardDefinitionsEvent();

        for (String packName : BUILTIN_PACKS)
        {
            loadBuiltinPack(packName, registration);
        }
        loadFileSystemPacks(registration);

        modBus.post(registration);
        CardRegistry.load(registration.cardsSnapshot(), registration.setsSnapshot());
        LOGGER.info("Card content ready: {} pack(s), {} card(s), {} set(s)",
                loadedPacks.size(), CardRegistry.all().size(), CardRegistry.allSets().size());
    }

    /** Packs loaded by this instance, in load order; used by the login handshake. */
    public static List<ContentPack> loadedPacks()
    {
        return List.copyOf(loadedPacks);
    }

    // Builtin (classpath) packs ---------------------------------------------

    private static void loadBuiltinPack(String packName, RegisterCardDefinitionsEvent event)
    {
        String base = "/assets/" + CardTableMod.MODID + "/cardpacks/" + packName + "/";
        try
        {
            byte[] packJson = readClasspath(base + "pack.json");
            if (packJson == null)
            {
                LOGGER.warn("Builtin card pack '{}' is missing pack.json", packName);
                return;
            }
            CardDefinitionJsonCodec.PackMeta meta = CardDefinitionJsonCodec.parsePackMeta(
                    JsonParser.parseString(new String(packJson, StandardCharsets.UTF_8)).getAsJsonObject());
            // Classpath pack textures are plain mod resources, resolved statically.
            CardDefinitionJsonCodec.TextureMapper mapper = relative ->
                    new ResourceLocation(CardTableMod.MODID, "card/" + meta.id().getPath() + "/" + relative.toLowerCase(Locale.ROOT));
            loadPack(meta, new PackSource()
            {
                @Override
                public byte[] read(String relativePath)
                {
                    return readClasspath(base + relativePath);
                }

                @Override
                public Map<String, byte[]> readTextures()
                {
                    return Map.of();
                }
            }, mapper, event);
        }
        catch (Exception exception)
        {
            LOGGER.warn("Failed to load builtin card pack '{}': {}", packName, exception.toString());
        }
    }

    @Nullable
    private static byte[] readClasspath(String resourcePath)
    {
        try (InputStream stream = ContentPackLoader.class.getResourceAsStream(resourcePath))
        {
            return stream == null ? null : stream.readAllBytes();
        }
        catch (IOException exception)
        {
            LOGGER.warn("Failed to read classpath resource {}: {}", resourcePath, exception.toString());
            return null;
        }
    }

    // File-backed packs ------------------------------------------------------

    private static void loadFileSystemPacks(RegisterCardDefinitionsEvent event)
    {
        try
        {
            Files.createDirectories(PACKS_DIR);
            try (Stream<Path> entries = Files.list(PACKS_DIR))
            {
                entries.forEach(entry -> loadFileSystemPack(entry, event));
            }
        }
        catch (IOException exception)
        {
            LOGGER.warn("Failed to scan card pack directory {}: {}", PACKS_DIR, exception.toString());
        }
    }

    private static void loadFileSystemPack(Path entry, RegisterCardDefinitionsEvent event)
    {
        String fileName = entry.getFileName().toString();
        try
        {
            if (Files.isRegularFile(entry) && fileName.toLowerCase(Locale.ROOT).endsWith(".zip"))
            {
                try (ZipFile zip = new ZipFile(entry.toFile()))
                {
                    CardDefinitionJsonCodec.PackMeta meta = zipMeta(zip);
                    loadPack(meta, new ZipPackSource(zip), dynamicMapper(meta), event);
                }
            }
            else if (Files.isDirectory(entry))
            {
                try (DirectoryPackSource source = new DirectoryPackSource(entry))
                {
                    CardDefinitionJsonCodec.PackMeta meta = source.readMeta();
                    loadPack(meta, source, dynamicMapper(meta), event);
                }
            }
        }
        catch (Exception exception)
        {
            LOGGER.warn("Failed to load card pack '{}': {}", fileName, exception.toString());
        }
    }

    // Shared pipeline ----------------------------------------------------------

    private static void loadPack(CardDefinitionJsonCodec.PackMeta meta, PackSource source,
                                 CardDefinitionJsonCodec.TextureMapper textures,
                                 RegisterCardDefinitionsEvent event)
    {
        List<String> canonicalLines = new ArrayList<>();
        try
        {
            String setLine = CardDefinitionJsonCodec.registerSet(meta, textures, event::register);
            if (setLine != null)
            {
                canonicalLines.add(setLine);
            }
        }
        catch (Exception exception)
        {
            LOGGER.warn("Pack {}: skipping broken set declaration: {}", meta.id(), exception.toString());
        }

        int accepted = 0;
        byte[] cardsJson = null;
        try
        {
            cardsJson = source.read("cards.json");
        }
        catch (IOException exception)
        {
            LOGGER.warn("Pack {}: failed to read cards.json: {}", meta.id(), exception.toString());
        }
        if (cardsJson != null)
        {
            JsonArray array = JsonParser.parseString(new String(cardsJson, StandardCharsets.UTF_8)).getAsJsonArray();
            for (int index = 0; index < array.size(); index++)
            {
                JsonElement element = array.get(index);
                if (!element.isJsonObject())
                {
                    LOGGER.warn("Pack {}: card entry #{} is not an object, skipped", meta.id(), index);
                    continue;
                }
                try
                {
                    CardDefinitionJsonCodec.ParsedCard parsed =
                            CardDefinitionJsonCodec.parseCard(element.getAsJsonObject(), meta, textures);
                    event.register(parsed.definition());
                    canonicalLines.add(parsed.canonicalLine());
                    accepted++;
                }
                catch (Exception exception)
                {
                    // One malformed entry must not sink the pack; hash simply
                    // covers the entries that made it in.
                    LOGGER.warn("Pack {}: skipping broken card entry #{}: {}",
                            meta.id(), index, exception.toString());
                }
            }
        }

        Map<String, byte[]> dynamicTextures;
        try
        {
            dynamicTextures = source.readTextures();
        }
        catch (IOException exception)
        {
            LOGGER.warn("Pack {}: failed to read textures: {}", meta.id(), exception.toString());
            dynamicTextures = Map.of();
        }

        String hash = CardDefinitionJsonCodec.contentHash(canonicalLines);
        loadedPacks.add(new ContentPack(meta.id(), meta.name(), meta.version(), hash, dynamicTextures));
        LOGGER.info("Loaded card pack '{}' {} v{} ({} cards, hash {})",
                meta.name(), meta.id(), meta.version(), accepted, hash.substring(0, 12));
    }

    @Nullable
    private static byte[] readZipText(ZipFile zip, String path) throws IOException
    {
        ZipEntry zipEntry = zip.getEntry(path);
        return zipEntry == null ? null : zip.getInputStream(zipEntry).readAllBytes();
    }

    private static CardDefinitionJsonCodec.PackMeta zipMeta(ZipFile zip) throws IOException
    {
        byte[] packJson = readZipText(zip, "pack.json");
        if (packJson == null)
        {
            throw new IOException("pack.json missing");
        }
        return CardDefinitionJsonCodec.parsePackMeta(
                JsonParser.parseString(new String(packJson, StandardCharsets.UTF_8)).getAsJsonObject());
    }

    // Texture ids for file packs always live under cardtable_dyn, so the same
    // cards.json works whether the pack ships as zip or unpacked directory.
    private static CardDefinitionJsonCodec.TextureMapper dynamicMapper(
            CardDefinitionJsonCodec.PackMeta meta)
    {
        return relative -> new ResourceLocation(DYNAMIC_NAMESPACE,
                meta.id().getNamespace() + "/" + meta.id().getPath() + "/" + relative.toLowerCase(Locale.ROOT));
    }

    // Pack sources -------------------------------------------------------------

    /** Uniform byte-level view over a classpath folder, zip or directory pack. */
    private interface PackSource extends AutoCloseable
    {
        @Nullable
        byte[] read(String relativePath) throws IOException;

        /** Pack-relative PNG textures (no extension), keyed by their relative path. */
        Map<String, byte[]> readTextures() throws IOException;

        @Override
        default void close()
        {
        }
    }

    private record ZipPackSource(ZipFile zip) implements PackSource
    {
        @Override
        public byte[] read(String relativePath) throws IOException
        {
            return readZipText(this.zip, relativePath);
        }

        @Override
        public Map<String, byte[]> readTextures() throws IOException
        {
            Map<String, byte[]> textures = new HashMap<>();
            for (Enumeration<? extends ZipEntry> entries = this.zip.entries(); entries.hasMoreElements(); )
            {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("textures/") || !name.endsWith(".png"))
                {
                    continue;
                }
                putTexture(textures, textureRelativePath(name), this.zip.getInputStream(entry).readAllBytes());
            }
            return textures;
        }
    }

    private static final class DirectoryPackSource implements PackSource
    {
        private final Path root;

        private DirectoryPackSource(Path root)
        {
            this.root = root;
        }

        private Path resolve(String relativePath)
        {
            // Pack layout is trusted content, but resolving inside root keeps
            // ".." entries from escaping the pack directory.
            return this.root.resolve(relativePath).normalize();
        }

        @Override
        public byte[] read(String relativePath) throws IOException
        {
            Path path = resolve(relativePath);
            return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
        }

        public CardDefinitionJsonCodec.PackMeta readMeta() throws IOException
        {
            byte[] packJson = read("pack.json");
            if (packJson == null)
            {
                throw new IOException("pack.json missing");
            }
            return CardDefinitionJsonCodec.parsePackMeta(
                    JsonParser.parseString(new String(packJson, StandardCharsets.UTF_8)).getAsJsonObject());
        }

        @Override
        public Map<String, byte[]> readTextures() throws IOException
        {
            Map<String, byte[]> textures = new HashMap<>();
            Path texturesRoot = resolve("textures");
            if (!Files.isDirectory(texturesRoot))
            {
                return textures;
            }
            try (Stream<Path> files = Files.walk(texturesRoot))
            {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator)
                {
                    String name = texturesRoot.relativize(file).toString().replace('\\', '/');
                    if (!name.endsWith(".png"))
                    {
                        continue;
                    }
                    putTexture(textures, textureRelativePath("textures/" + name), Files.readAllBytes(file));
                }
            }
            return textures;
        }
    }

    /** textures/&lt;relative&gt;.png → &lt;relative&gt; (no extension). */
    private static String textureRelativePath(String zipEntryName)
    {
        return zipEntryName.substring("textures/".length(), zipEntryName.length() - ".png".length())
                .toLowerCase(Locale.ROOT);
    }

    private static void putTexture(Map<String, byte[]> textures, String relativePath, byte[] bytes)
    {
        if (bytes.length > MAX_TEXTURE_BYTES)
        {
            LOGGER.warn("Skipping texture '{}': {} bytes exceeds the {} byte limit",
                    relativePath, bytes.length, MAX_TEXTURE_BYTES);
            return;
        }
        textures.put(relativePath, bytes);
    }
}
