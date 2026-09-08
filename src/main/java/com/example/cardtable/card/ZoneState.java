package com.example.cardtable.card;

import com.example.cardtable.api.ZoneDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Generic state container for a layout-declared zone instance. This is the
 * layout-driven counterpart of {@link SurfaceZone}: a STACK zone stores an
 * ordered card list (the last entry is the pile top, matching the built-in
 * piles), a FREE or GRID zone stores placed cards with normalized (0..1)
 * positions inside the zone rect (matching {@link SurfaceZone.SurfaceCard}).
 *
 * <p>Capacity is a layout rule, not state: the layout lookup happens at the
 * validation layer, so nothing here needs the registry.</p>
 */
public final class ZoneState
{
    private static final String KIND_TAG = "Kind";
    private static final String CARDS_TAG = "Cards";
    private static final String CARD_TAG = "Card";
    private static final String X_TAG = "X";
    private static final String Y_TAG = "Y";

    /** Which placement rule this zone instance follows. */
    public enum Storage
    {
        /** Ordered pile without coordinates; the list end is the top. */
        STACK,
        /** Free placement or fixed slots; entries carry normalized coordinates. */
        PLACED
    }

    /** One placed card with its normalized zone-local position. */
    public record PlacedCard(CardInstance card, float x, float y)
    {
        public PlacedCard
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

        @Nullable
        public static PlacedCard load(CompoundTag tag)
        {
            CardInstance card = CardInstance.load(tag.getCompound(CARD_TAG));
            if (card == null)
            {
                return null;
            }
            return new PlacedCard(card, tag.getFloat(X_TAG), tag.getFloat(Y_TAG));
        }

        private static float clamp(float value)
        {
            return Math.max(0.0F, Math.min(1.0F, value));
        }
    }

    private final Storage storage;
    private final List<CardInstance> stack = new ArrayList<>();
    private final List<PlacedCard> placed = new ArrayList<>();

    private ZoneState(Storage storage)
    {
        this.storage = storage;
    }

    /** A new empty STACK zone instance. */
    public static ZoneState stack()
    {
        return new ZoneState(Storage.STACK);
    }

    /** A new empty FREE/GRID zone instance. */
    public static ZoneState placed()
    {
        return new ZoneState(Storage.PLACED);
    }

    /** Picks the storage from a layout zone kind: STACK keeps order, GRID/FREE place cards. */
    public static ZoneState stackFor(ZoneDefinition.Kind kind)
    {
        return kind == ZoneDefinition.Kind.STACK ? stack() : placed();
    }

    /** Deep copy: cards are immutable, so the container lists are what matters. */
    public ZoneState(ZoneState source)
    {
        this.storage = source.storage;
        this.stack.addAll(source.stack);
        this.placed.addAll(source.placed);
    }

    public Storage storage()
    {
        return this.storage;
    }

    // STACK storage ---------------------------------------------------------

    /** Bottom-to-top order: the last entry is the top of the pile. */
    public List<CardInstance> stackCards()
    {
        return this.stack;
    }

    /** Bottom-to-top order: the added card becomes the top of the pile. */
    public void addToStackTop(CardInstance card)
    {
        this.stack.add(card);
    }

    @Nullable
    public CardInstance takeFromStackTop()
    {
        return this.stack.isEmpty() ? null : this.stack.remove(this.stack.size() - 1);
    }

    // PLACED storage ----------------------------------------------------------

    public List<PlacedCard> placedCards()
    {
        return this.placed;
    }

    public void addPlaced(CardInstance card, float x, float y)
    {
        this.placed.add(new PlacedCard(card, x, y));
    }

    public Optional<PlacedCard> find(UUID instanceId)
    {
        return this.placed.stream().filter(entry -> entry.card().instanceId().equals(instanceId)).findFirst();
    }

    /** Moves a card within the zone; returns {@code false} when the id is unknown. */
    public boolean reposition(UUID instanceId, float x, float y)
    {
        Optional<PlacedCard> found = find(instanceId);
        if (found.isEmpty())
        {
            return false;
        }
        PlacedCard moved = new PlacedCard(found.get().card(), x, y);
        this.placed.set(this.placed.indexOf(found.get()), moved);
        return true;
    }

    /** Removes and returns the card with the given instance id, if present. */
    public Optional<CardInstance> remove(UUID instanceId)
    {
        Optional<PlacedCard> found = find(instanceId);
        if (found.isEmpty())
        {
            return Optional.empty();
        }
        this.placed.remove(found.get());
        return Optional.of(found.get().card());
    }

    // Common -----------------------------------------------------------------

    public int size()
    {
        return this.storage == Storage.STACK ? this.stack.size() : this.placed.size();
    }

    public boolean isEmpty()
    {
        return this.size() == 0;
    }

    /** Removes every card of the given definitions (deck reclaim path). */
    public void removeIfDefinition(Set<ResourceLocation> definitionIds)
    {
        this.stack.removeIf(card -> definitionIds.contains(card.definitionId()));
        this.placed.removeIf(entry -> definitionIds.contains(entry.card().definitionId()));
    }

    public CompoundTag save()
    {
        CompoundTag tag = new CompoundTag();
        tag.putString(KIND_TAG, this.storage.name());
        if (this.storage == Storage.STACK)
        {
            tag.put(CARDS_TAG, CardInstance.saveAll(this.stack));
        }
        else
        {
            ListTag list = new ListTag();
            for (PlacedCard entry : this.placed)
            {
                list.add(entry.save());
            }
            tag.put(CARDS_TAG, list);
        }
        return tag;
    }

    /** Loads a saved zone; {@code null} when the tag is malformed. */
    @Nullable
    public static ZoneState load(CompoundTag tag)
    {
        Storage storage;
        try
        {
            storage = Storage.valueOf(tag.getString(KIND_TAG));
        }
        catch (IllegalArgumentException exception)
        {
            return null;
        }
        ZoneState state = new ZoneState(storage);
        if (storage == Storage.STACK)
        {
            state.stack.addAll(CardInstance.loadAll(tag.getList(CARDS_TAG, Tag.TAG_COMPOUND)));
        }
        else
        {
            ListTag list = tag.getList(CARDS_TAG, Tag.TAG_COMPOUND);
            for (int index = 0; index < list.size(); index++)
            {
                PlacedCard entry = PlacedCard.load(list.getCompound(index));
                if (entry != null)
                {
                    state.placed.add(entry);
                }
            }
        }
        return state;
    }
}
