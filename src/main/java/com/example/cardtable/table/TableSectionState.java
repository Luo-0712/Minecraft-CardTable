package com.example.cardtable.table;

import com.example.cardtable.card.CardInstance;
import com.example.cardtable.card.SurfaceZone;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-block table section state: one seat plus the block-local zones. The
 * occupant is the player seated at this particular table block; group
 * identity and shared containers live in the block's {@link TableGroupState}
 * copy.
 *
 * <p>The {@code surface} zone holds the cards placed on this block's part of
 * the table and is synced to everyone via the block entity update tag. The
 * {@code hand} zone is the seated player's hidden hand: it is saved with the
 * world but never written to the update tag (only a count is) — the real
 * contents travel through a directed packet addressed to the occupant.</p>
 */
public final class TableSectionState
{
    /** Tag name of this state inside the block entity's full tag; shared with the sync strip. */
    public static final String SECTION_STATE_TAG = "SectionState";

    private static final String OCCUPANT_TAG = "Occupant";
    private static final String VERSION_TAG = "Version";
    private static final String SURFACE_TAG = "Surface";
    private static final String HAND_TAG = "Hand";

    @Nullable
    private UUID occupantId;
    private long version;
    private final SurfaceZone surface = new SurfaceZone();
    private final List<CardInstance> hand = new ArrayList<>();

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
        TableSectionState state = new TableSectionState(occupantId, version);
        if (tag.contains(SURFACE_TAG, Tag.TAG_COMPOUND))
        {
            state.surface.load(tag.getCompound(SURFACE_TAG));
        }
        state.hand.addAll(CardInstance.loadAll(tag.getList(HAND_TAG, Tag.TAG_COMPOUND)));
        return state;
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
        tag.put(SURFACE_TAG, this.surface.save());
        tag.put(HAND_TAG, CardInstance.saveAll(this.hand));
        return tag;
    }

    /**
     * World-sync view of one saved section-state tag (the layer this class
     * saves/loads, inside the block entity's "SectionState" entry): hand
     * contents are stripped and replaced by a count. The hand is the
     * occupant's hidden information and must never travel through the
     * broadcast path — every tracking client receives this tag, only the
     * occupant receives the real hand via the directed {@code HandSyncPacket}.
     */
    public static CompoundTag stripHandForSync(CompoundTag savedState)
    {
        CompoundTag tag = savedState.copy();
        tag.putInt("HandCount", tag.getList(HAND_TAG, Tag.TAG_COMPOUND).size());
        tag.remove(HAND_TAG);
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

    // Zones ---------------------------------------------------------------

    /** Cards placed on this block's part of the surface; synced to everyone. */
    public SurfaceZone getSurface()
    {
        return this.surface;
    }

    /** The occupant's hidden hand; never leaves the server except as a directed packet. */
    public List<CardInstance> getHand()
    {
        return this.hand;
    }

    public int getHandCount()
    {
        return this.hand.size();
    }

    /** Takes {@code count} cards from the end of the hand (rightmost); used for the hand packet. */
    public List<CardInstance> takeHandSnapshot()
    {
        return List.copyOf(this.hand);
    }

    public void addHandCard(CardInstance card)
    {
        this.hand.add(card);
    }

    public void addHandCards(List<CardInstance> cards)
    {
        this.hand.addAll(cards);
    }

    @Nullable
    public CardInstance removeHandCard(UUID instanceId)
    {
        for (int index = 0; index < this.hand.size(); index++)
        {
            if (this.hand.get(index).instanceId().equals(instanceId))
            {
                return this.hand.remove(index);
            }
        }
        return null;
    }
}
