package com.example.cardtable.card;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The cards lying face-up/face-down on one table block's part of the surface.
 * Positions are normalized (0..1) block-local coordinates so the layout is
 * independent of window size; the client maps them onto its playfield cells.
 *
 * <p>Draw order is not tracked here — pile semantics (draw/discard) live in
 * {@code TableGroupState}; this zone is the free-form play area.</p>
 */
public final class SurfaceZone
{
    private static final String CARDS_TAG = "Cards";
    private static final String CARD_TAG = "Card";
    private static final String X_TAG = "X";
    private static final String Y_TAG = "Y";

    /** One placed card with its normalized surface position. */
    public record SurfaceCard(CardInstance card, float x, float y)
    {
        public SurfaceCard
        {
            x = clamp(x);
            y = clamp(y);
        }

        public CompoundTag save()
        {
            CompoundTag tag = new CompoundTag();
            tag.put(CARD_TAG, this.card.save());
            tag.putFloat(X_TAG, this.x);
            tag.putFloat(Y_TAG, this.y);
            return tag;
        }

        public static SurfaceCard load(CompoundTag tag)
        {
            CardInstance card = CardInstance.load(tag.getCompound(CARD_TAG));
            if (card == null)
            {
                return null;
            }
            return new SurfaceCard(card, tag.getFloat(X_TAG), tag.getFloat(Y_TAG));
        }

        private static float clamp(float value)
        {
            return Math.max(0.0F, Math.min(1.0F, value));
        }
    }

    private final List<SurfaceCard> cards = new ArrayList<>();

    public List<SurfaceCard> cards()
    {
        return List.copyOf(this.cards);
    }

    public int size()
    {
        return this.cards.size();
    }

    public void add(CardInstance card, float x, float y)
    {
        this.cards.add(new SurfaceCard(card, x, y));
    }

    public Optional<SurfaceCard> find(UUID instanceId)
    {
        return this.cards.stream().filter(entry -> entry.card().instanceId().equals(instanceId)).findFirst();
    }

    /** Moves a card within the zone; returns {@code false} when the id is unknown. */
    public boolean reposition(UUID instanceId, float x, float y)
    {
        Optional<SurfaceCard> found = find(instanceId);
        if (found.isEmpty())
        {
            return false;
        }
        SurfaceCard moved = new SurfaceCard(found.get().card(), x, y);
        this.cards.set(this.cards.indexOf(found.get()), moved);
        return true;
    }

    /** Removes and returns the card with the given instance id, if present. */
    public Optional<CardInstance> remove(UUID instanceId)
    {
        Optional<SurfaceCard> found = find(instanceId);
        if (found.isEmpty())
        {
            return Optional.empty();
        }
        this.cards.remove(found.get());
        return Optional.of(found.get().card());
    }

    public CompoundTag save()
    {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (SurfaceCard entry : this.cards)
        {
            list.add(entry.save());
        }
        tag.put(CARDS_TAG, list);
        return tag;
    }

    public void load(CompoundTag tag)
    {
        this.cards.clear();
        ListTag list = tag.getList(CARDS_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < list.size(); index++)
        {
            SurfaceCard entry = SurfaceCard.load(list.getCompound(index));
            if (entry != null)
            {
                this.cards.add(entry);
            }
        }
    }
}
