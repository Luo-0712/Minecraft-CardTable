package com.example.cardtable.table;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Per-block table section state: one seat plus the block-local zones. The
 * occupant is the player seated at this particular table block; group
 * identity and shared containers live in the block's {@link TableGroupState}
 * copy.
 *
 * <p>Next phase extension points: {@code handZone} (the seated player's
 * hidden hand) and {@code surfaceZone} (cards placed on this block's part of
 * the table surface).</p>
 */
public final class TableSectionState
{
    private static final String OCCUPANT_TAG = "Occupant";
    private static final String VERSION_TAG = "Version";

    @Nullable
    private UUID occupantId;
    private long version;

    public TableSectionState()
    {
    }

    private TableSectionState(@Nullable UUID occupantId, long version)
    {
        this.occupantId = occupantId;
        this.version = Math.max(0L, version);
    }

    public static TableSectionState load(CompoundTag tag)
    {
        UUID occupantId = tag.hasUUID(OCCUPANT_TAG) ? tag.getUUID(OCCUPANT_TAG) : null;
        long version = tag.contains(VERSION_TAG) ? tag.getLong(VERSION_TAG) : 0L;
        return new TableSectionState(occupantId, version);
    }

    /**
     * Migrates a pre-merge save: the old per-table state held up to six
     * participants for a standalone table, but each block now seats exactly
     * one player. The earliest participant keeps the seat, later ones are
     * released; the group identity is migrated separately by
     * {@link TableGroupState#loadLegacy(CompoundTag)}.
     */
    public static TableSectionState loadLegacy(CompoundTag oldTableState)
    {
        UUID firstParticipant = null;
        ListTag participants = oldTableState.getList("Participants", CompoundTag.TAG_COMPOUND);
        for (int index = 0; index < participants.size(); index++)
        {
            CompoundTag participantTag = participants.getCompound(index);
            if (participantTag.hasUUID("PlayerId"))
            {
                firstParticipant = participantTag.getUUID("PlayerId");
                break;
            }
        }
        return new TableSectionState(firstParticipant, 0L);
    }

    public CompoundTag save()
    {
        CompoundTag tag = new CompoundTag();
        if (this.occupantId != null)
        {
            tag.putUUID(OCCUPANT_TAG, this.occupantId);
        }
        tag.putLong(VERSION_TAG, this.version);
        return tag;
    }

    @Nullable
    public UUID getOccupantId()
    {
        return this.occupantId;
    }

    public void setOccupant(@Nullable UUID occupantId)
    {
        this.occupantId = occupantId;
        this.version++;
    }

    public long getVersion()
    {
        return this.version;
    }
}
