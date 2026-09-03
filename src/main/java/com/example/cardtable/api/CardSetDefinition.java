package com.example.cardtable.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Optional grouping of cards (one deck family, e.g. "standard poker"). A set
 * supplies the default card back used by every member card that does not
 * override {@link CardDefinition#backTexture()}, and is also what a deck item
 * references when a player loads a whole deck onto a table.
 */
public final class CardSetDefinition
{
    private final ResourceLocation id;
    private final Component displayName;
    @Nullable
    private final ResourceLocation defaultBackTexture;

    private CardSetDefinition(Builder builder)
    {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.defaultBackTexture = builder.defaultBackTexture;
    }

    public static Builder builder(ResourceLocation id)
    {
        return new Builder(id);
    }

    /** Unique set id, e.g. {@code cardtable:standard}. */
    public ResourceLocation id()
    {
        return this.id;
    }

    public Component displayName()
    {
        return this.displayName;
    }

    /** Card back drawn for face-down cards without their own back; {@code null} uses the core fallback. */
    @Nullable
    public ResourceLocation defaultBackTexture()
    {
        return this.defaultBackTexture;
    }

    @Override
    public String toString()
    {
        return "CardSetDefinition[" + this.id + "]";
    }

    public static final class Builder
    {
        private final ResourceLocation id;
        private Component displayName;
        @Nullable
        private ResourceLocation defaultBackTexture;

        private Builder(ResourceLocation id)
        {
            this.id = java.util.Objects.requireNonNull(id, "id");
        }

        public Builder displayName(Component displayName)
        {
            this.displayName = java.util.Objects.requireNonNull(displayName, "displayName");
            return this;
        }

        public Builder defaultBackTexture(@Nullable ResourceLocation defaultBackTexture)
        {
            this.defaultBackTexture = defaultBackTexture;
            return this;
        }

        public CardSetDefinition build()
        {
            if (this.displayName == null)
            {
                throw new IllegalStateException("Card set " + this.id + " is missing a display name");
            }
            return new CardSetDefinition(this);
        }
    }
}
