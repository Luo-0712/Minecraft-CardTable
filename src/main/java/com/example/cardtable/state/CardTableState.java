package com.example.cardtable.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;


/**
 * Server-authoritative state for one card table instance.
 */
public final class CardTableState
{
    public static final int DEFAULT_MAX_PARTICIPANTS = 6;

    private static final String TABLE_ID_TAG = "TableId";
    private static final String PARTICIPANTS_TAG = "Participants";
    private static final String PARTICIPANT_ID_TAG = "PlayerId";
    private static final String MAX_PARTICIPANTS_TAG = "MaxParticipants";
    private static final String VERSION_TAG = "Version";

    private final UUID tableId;
    private final LinkedHashSet<UUID> participantIds;
    private final int maxParticipants;
    private long version;

    private CardTableState(UUID tableId, Set<UUID> participantIds, int maxParticipants, long version)
    {
        this.tableId = tableId;
        this.participantIds = new LinkedHashSet<>(participantIds);
        this.maxParticipants = Math.min(DEFAULT_MAX_PARTICIPANTS, Math.max(1, maxParticipants));
        this.version = Math.max(0L, version);
    }

    public static CardTableState create()
    {
        return new CardTableState(UUID.randomUUID(), Collections.emptySet(), DEFAULT_MAX_PARTICIPANTS, 0L);
    }

    public static CardTableState load(CompoundTag tag)
    {
        UUID tableId = tag.hasUUID(TABLE_ID_TAG) ? tag.getUUID(TABLE_ID_TAG) : UUID.randomUUID();
        int maxParticipants = tag.contains(MAX_PARTICIPANTS_TAG) ? tag.getInt(MAX_PARTICIPANTS_TAG) : DEFAULT_MAX_PARTICIPANTS;
        long version = tag.contains(VERSION_TAG) ? tag.getLong(VERSION_TAG) : 0L;
        LinkedHashSet<UUID> participantIds = new LinkedHashSet<>();

        ListTag participants = tag.getList(PARTICIPANTS_TAG, CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < participants.size() && participantIds.size() < Math.max(1, maxParticipants); index++)
        {
            CompoundTag participantTag = participants.getCompound(index);
            if (participantTag.hasUUID(PARTICIPANT_ID_TAG))
            {
                participantIds.add(participantTag.getUUID(PARTICIPANT_ID_TAG));
            }
        }

        return new CardTableState(tableId, participantIds, maxParticipants, version);
    }

    public CompoundTag save()
    {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TABLE_ID_TAG, this.tableId);

        ListTag participants = new ListTag();
        for (UUID participantId : this.participantIds)
        {
            CompoundTag participantTag = new CompoundTag();
            participantTag.putUUID(PARTICIPANT_ID_TAG, participantId);
            participants.add(participantTag);
        }
        tag.put(PARTICIPANTS_TAG, participants);
        tag.putInt(MAX_PARTICIPANTS_TAG, this.maxParticipants);
        tag.putLong(VERSION_TAG, this.version);
        return tag;
    }

    public JoinResult join(UUID playerId)
    {
        if (this.participantIds.contains(playerId))
        {
            return JoinResult.ALREADY_JOINED;
        }
        if (this.participantIds.size() >= this.maxParticipants)
        {
            return JoinResult.TABLE_FULL;
        }

        this.participantIds.add(playerId);
        this.version++;
        return JoinResult.JOINED;
    }

    public boolean leave(UUID playerId)
    {
        if (!this.participantIds.remove(playerId))
        {
            return false;
        }

        this.version++;
        return true;
    }

    public UUID getTableId()
    {
        return this.tableId;
    }

    public Set<UUID> getParticipantIds()
    {
        return Collections.unmodifiableSet(this.participantIds);
    }

    public int getParticipantCount()
    {
        return this.participantIds.size();
    }

    public int getMaxParticipants()
    {
        return this.maxParticipants;
    }

    public long getVersion()
    {
        return this.version;
    }

    public boolean isParticipant(UUID playerId)
    {
        return this.participantIds.contains(playerId);
    }

    public enum JoinResult
    {
        JOINED,
        ALREADY_JOINED,
        TABLE_FULL
    }
}
