package com.example.cardtable.content;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.network.NetworkHandler;
import com.example.cardtable.network.packet.ContentPackRequestPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content consistency handshake: after a player logs in the server asks the
 * client for its local pack list and compares
 * {@code (packId, version, contentHash)} triples.
 *
 * <p>A missing pack or a hash/version mismatch disconnects the client with a
 * message naming every offending pack, so both sides always agree on what a
 * card id on the table looks like. Extra client-side packs are harmless (the
 * server never references ids it does not know) and are only logged.</p>
 */
public final class ContentPackHandshake
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private ContentPackHandshake()
    {
    }

    /** One client-declared pack; mirrors the server-side {@link ContentPack} identity. */
    public record PackEntry(String packId, String version, String contentHash)
    {
    }

    /** Handshake outcome; {@code failure} is a player-facing disconnect reason. */
    public record CompareResult(@Nullable Component failure)
    {
        public boolean passed()
        {
            return this.failure == null;
        }
    }

    // Compares the authoritative server list against the client declaration.
    // Server packs missing on the client, or with a different version/hash,
    // are collected into one failure message; unknown client packs are ignored.
    // The message is a literal component on purpose: pack ids and digests are
    // language-neutral data, so the same diagnostic renders on every client.
    public static CompareResult compare(List<ContentPack> serverPacks, List<PackEntry> clientEntries)
    {
        Map<String, PackEntry> clientById = new LinkedHashMap<>();
        for (PackEntry entry : clientEntries)
        {
            clientById.put(entry.packId(), entry);
        }

        List<String> problems = new ArrayList<>();
        for (ContentPack pack : serverPacks)
        {
            String packId = pack.id().toString();
            PackEntry client = clientById.get(packId);
            if (client == null)
            {
                problems.add(packId + ": missing (server has " + pack.version() + ")");
            }
            else if (!pack.version().equals(client.version()) || !pack.contentHash().equals(client.contentHash()))
            {
                problems.add(packId + ": mismatch (server " + pack.version() + "/" + pack.contentHash()
                        + ", client " + client.version() + "/" + client.contentHash() + ")");
            }
        }
        if (problems.isEmpty())
        {
            return new CompareResult(null);
        }
        return new CompareResult(Component.literal(String.join("\n", problems)));
    }

    // The request/response exchange happens right after login; by then the
    // protocol version handshake has already guaranteed both sides speak "2".
    @Mod.EventBusSubscriber(modid = CardTableMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
    private static class LoginHook
    {
        @SubscribeEvent
        public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event)
        {
            if (event.getEntity() instanceof ServerPlayer player)
            {
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new ContentPackRequestPacket());
            }
        }
    }
}
