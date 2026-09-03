package com.example.cardtable.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Immutable description of one card type, the stable contract between the
 * card table core and content providers (packs built by the external card
 * builder, or third-party mods registered via
 * {@link RegisterCardDefinitionsEvent}).
 *
 * <p>Instances live on tables by id only; the definition supplies rendering
 * data (display name, face textures) and ordering hints. The core never
 * interprets what a card means for any game's rules.</p>
 */
public final class CardDefinition
{
    private final ResourceLocation id;
    private final Component displayName;
    private final ResourceLocation frontTexture;
    @Nullable
    private final ResourceLocation backTexture;
    @Nullable
    private final ResourceLocation cardSet;
    private final int sortIndex;

    private CardDefinition(Builder builder)
    {
        this.id = builder.id;
        this.displayName = builder.displayName;
        this.frontTexture = builder.frontTexture;
        this.backTexture = builder.backTexture;
        this.cardSet = builder.cardSet;
        this.sortIndex = builder.sortIndex;
    }

    public static Builder builder(ResourceLocation id)
    {
        return new Builder(id);
    }

    /** Unique card id, e.g. {@code cardtable:standard/ace_of_spades}. */
    public ResourceLocation id()
    {
        return this.id;
    }

    public Component displayName()
    {
        return this.displayName;
    }

    /** Face texture id as drawn on the table; see the client texture resolver. */
    public ResourceLocation frontTexture()
    {
        return this.frontTexture;
    }

    /** Face-down texture; {@code null} falls back to the owning set's default back. */
    @Nullable
    public ResourceLocation backTexture()
    {
        return this.backTexture;
    }

    /** Owning set id (e.g. {@code cardtable:standard}); {@code null} for loose cards. */
    @Nullable
    public ResourceLocation cardSet()
    {
        return this.cardSet;
    }

    /** Ordering hint within the set; the core uses it only for deck fill order. */
    public int sortIndex()
    {
        return this.sortIndex;
    }

    @Override
    public String toString()
    {
        return "CardDefinition[" + this.id + "]";
    }

    public static final class Builder
    {
        private final ResourceLocation id;
        private Component displayName;
        private ResourceLocation frontTexture;
        @Nullable
        private ResourceLocation backTexture;
        @Nullable
        private ResourceLocation cardSet;
        private int sortIndex;

        private Builder(ResourceLocation id)
        {
            this.id = java.util.Objects.requireNonNull(id, "id");
        }

        public Builder displayName(Component displayName)
        {
            this.displayName = java.util.Objects.requireNonNull(displayName, "displayName");
            return this;
        }

        public Builder frontTexture(ResourceLocation frontTexture)
        {
            this.frontTexture = java.util.Objects.requireNonNull(frontTexture, "frontTexture");
            return this;
        }

        public Builder backTexture(@Nullable ResourceLocation backTexture)
        {
            this.backTexture = backTexture;
            return this;
        }

        public Builder cardSet(@Nullable ResourceLocation cardSet)
        {
            this.cardSet = cardSet;
            return this;
        }

        public Builder sortIndex(int sortIndex)
        {
            this.sortIndex = sortIndex;
            return this;
        }

        public CardDefinition build()
        {
            if (this.displayName == null)
            {
                throw new IllegalStateException("Card " + this.id + " is missing a display name");
            }
            if (this.frontTexture == null)
            {
                throw new IllegalStateException("Card " + this.id + " is missing a front texture");
            }
            return new CardDefinition(this);
        }
    }
}
