package com.example.cardtable.table;

import com.example.cardtable.card.CardInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-block table section state: one seat plus the occupant's hidden hand.
 * The occupant is the player seated at this particular table block; group
 * identity and every shared container (the blank surface and the layout
 * zones) live in the block's {@link TableGroupState} copy.
 *
 * <p>The {@code hand} zone is the seated player's hidden hand: it is saved
 * with the world but never written to the update tag (only a count is) —
 * the real contents travel through a directed packet addressed to the
 * occupant.</p>
 */
public final class TableSectionState
{
    /** Tag name of this state inside the block entity's full tag; shared with the sync strip. */
    public static final String SECTION_STATE_TAG = "SectionState";

    private static final String OCCUPANT_TAG = "Occupant";
    private static final String VERSION_TAG = "Version";
    private static final String HAND_TAG = "Hand";
    /** Public count written by {@link #stripHandForSync}; read back on the client. */
    private static final String HAND_COUNT_TAG = "HandCount";

    @Nullable
    private UUID occupantId;
    private long version;
    private final List<CardInstance> hand = new ArrayList<>();
    /**
     * Count advertised by the stripped world-sync tag. Meaningful on the
     * client after a broadcast load, where {@link #hand} is always empty;
     * the server keeps it at 0 and reports {@code hand.size()} instead.
     */
    private int publicHandCount;

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
        state.hand.addAll(CardInstance.loadAll(tag.getList(HAND_TAG, Tag.TAG_COMPOUND)));
        // Stripped world-sync tags carry only the public count; full saves
        // never write it and keep the real hand list instead.
        if (tag.contains(HAND_COUNT_TAG))
        {
            state.publicHandCount = Math.max(0, tag.getInt(HAND_COUNT_TAG));
        }
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
        tag.putInt(HAND_COUNT_TAG, tag.getList(HAND_TAG, Tag.TAG_COMPOUND).size());
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

    // Hand -----------------------------------------------------------------

    /** The occupant's hidden hand; never leaves the server except as a directed packet. */
    public List<CardInstance> getHand()
    {
        return this.hand;
    }

    /**
     * Public hand size: the live list on the server, or the count the
     * stripped update tag advertised on the client (where {@link #hand}
     * never loads). Never the hand's contents.
     */
    public int getHandCount()
    {
        return this.hand.isEmpty() ? this.publicHandCount : this.hand.size();
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

    /**
     * Moves a hand card to {@code toIndex} (insert position after removal).
     * Returns whether the order actually changed — a no-op or missing card
     * leaves the hand untouched so the caller can skip a version bump.
     */
    public boolean reorderHand(UUID instanceId, int toIndex)
    {
        return com.example.cardtable.card.HandOrder.reorder(this.hand, instanceId, toIndex);
    }
}
