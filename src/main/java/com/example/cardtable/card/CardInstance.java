package com.example.cardtable.card;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One physical card lying on a table: an instance id plus a reference to its
 * {@code CardDefinition}, and the only mutable physical properties the
 * rule-neutral core tracks — face orientation and quarter-turn rotation.
 *
 * <p>Saved NBT stores the definition id as a plain string, so tables survive
 * content changes; a definition that went missing renders as a placeholder
 * instead of crashing the save.</p>
 */
public final class CardInstance
{
    private static final String ID_TAG = "Id";
    private static final String DEFINITION_TAG = "Definition";
    private static final String FACE_UP_TAG = "FaceUp";
    private static final String ROTATION_TAG = "Rotation";

    public static final int ROTATION_STEP_DEGREES = 90;

    private final UUID instanceId;
    private final ResourceLocation definitionId;
    private boolean faceUp;
    private int rotation;

    /** A fresh face-down, un-rotated card. */
    public CardInstance(ResourceLocation definitionId)
    {
        this(UUID.randomUUID(), definitionId, false, 0);
    }

    private CardInstance(UUID instanceId, ResourceLocation definitionId, boolean faceUp, int rotation)
    {
        this.instanceId = instanceId;
        this.definitionId = definitionId;
        this.faceUp = faceUp;
        this.rotation = rotation;
    }

    public UUID instanceId()
    {
        return this.instanceId;
    }

    public ResourceLocation definitionId()
    {
        return this.definitionId;
    }

    public boolean isFaceUp()
    {
        return this.faceUp;
    }

    public void setFaceUp(boolean faceUp)
    {
        this.faceUp = faceUp;
    }

    /** Quarter-turn rotation, normalized to {@code 0/90/180/270}. */
    public int rotation()
    {
        return this.rotation;
    }

    public void setRotation(int rotation)
    {
        this.rotation = Math.floorMod(rotation, 360) / ROTATION_STEP_DEGREES * ROTATION_STEP_DEGREES;
    }

    public void rotate()
    {
        setRotation(this.rotation + ROTATION_STEP_DEGREES);
    }

    public void flip()
    {
        this.faceUp = !this.faceUp;
    }

    public CompoundTag save()
    {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(ID_TAG, this.instanceId);
        tag.putString(DEFINITION_TAG, this.definitionId.toString());
        tag.putBoolean(FACE_UP_TAG, this.faceUp);
        tag.putInt(ROTATION_TAG, this.rotation);
        return tag;
    }

    @Nullable
    public static CardInstance load(CompoundTag tag)
    {
        if (!tag.hasUUID(ID_TAG) || !tag.contains(DEFINITION_TAG, Tag.TAG_STRING))
        {
            return null;
        }
        try
        {
            ResourceLocation definitionId = new ResourceLocation(tag.getString(DEFINITION_TAG));
            CardInstance instance = new CardInstance(tag.getUUID(ID_TAG), definitionId,
                    tag.getBoolean(FACE_UP_TAG), tag.getInt(ROTATION_TAG));
            instance.setRotation(instance.rotation);
            return instance;
        }
        catch (Exception exception)
        {
            return null;
        }
    }

    // List helpers ------------------------------------------------------------

    public static ListTag saveAll(List<CardInstance> cards)
    {
        ListTag list = new ListTag();
        for (CardInstance card : cards)
        {
            list.add(card.save());
        }
        return list;
    }

    /** Loads every readable entry; unreadable ones are dropped with no error. */
    public static List<CardInstance> loadAll(ListTag list)
    {
        List<CardInstance> cards = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++)
        {
            CardInstance card = load(list.getCompound(index));
            if (card != null)
            {
                cards.add(card);
            }
        }
        return cards;
    }

    @Override
    public String toString()
    {
        return "CardInstance[" + this.instanceId.toString().substring(0, 8) + " " + this.definitionId
                + (this.faceUp ? " up" : " down") + "@" + this.rotation + "]";
    }
}
