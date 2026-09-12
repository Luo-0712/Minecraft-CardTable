package com.example.cardtable.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * One operation a table layout offers, declared by the owning card pack. The
 * core supplies only generic primitives — take N cards from a stack into the
 * actor's hand, shuffle a stack, flip/rotate the hovered card — and executes
 * them server-side; it never interprets what an action means for any game's
 * rules. "Draw" is literally "take {@code amount} cards from stack
 * {@code sourceZone} into the hand", nothing more. "Reset" is literally
 * "gather every card of the active set back into {@code sourceZone} in set
 * order", nothing more.
 */
public final class TableActionDefinition
{
    /** The generic primitive the core executes for this action. */
    public enum Type
    {
        /** Take {@code amount} cards from {@code sourceZone} into the actor's hand. */
        DRAW,
        /** Randomize the order of {@code sourceZone} server-side. */
        SHUFFLE,
        /**
         * Collect every card of the active set from the whole table (surface,
         * declared zones, hands) back into {@code sourceZone} as the stock
         * pile: set order, face-down, unrotated.
         */
        RESET,
        /** Flip the card under the cursor. */
        FLIP,
        /** Rotate the card under the cursor. */
        ROTATE
    }

    private final ResourceLocation id;
    private final Type type;
    @Nullable
    private final ResourceLocation sourceZone;
    @Nullable
    private final ResourceLocation targetZone;
    private final int amount;
    @Nullable
    private final String key;
    @Nullable
    private final Component label;

    private TableActionDefinition(Builder builder)
    {
        this.id = builder.id;
        this.type = builder.type;
        this.sourceZone = builder.sourceZone;
        this.targetZone = builder.targetZone;
        this.amount = builder.amount;
        this.key = builder.key;
        this.label = builder.label;
    }

    public static Builder builder(ResourceLocation id)
    {
        return new Builder(id);
    }

    /** Unique action id within its layout, e.g. {@code cardtable:standard/draw}. */
    public ResourceLocation id()
    {
        return this.id;
    }

    /** The primitive the core runs when this action is performed. */
    public Type type()
    {
        return this.type;
    }

    /** The STACK zone DRAW takes from / SHUFFLE randomizes / RESET fills; required for all three. */
    @Nullable
    public ResourceLocation sourceZone()
    {
        return this.sourceZone;
    }

    /** Reserved for future primitives; always {@code null} today. */
    @Nullable
    public ResourceLocation targetZone()
    {
        return this.targetZone;
    }

    /** Card count of a DRAW; always {@code >= 1} and ignored by the other types. */
    public int amount()
    {
        return this.amount;
    }

    /**
     * GLFW key name this action binds to inside the table screen, e.g.
     * {@code key.keyboard.d}; {@code null} means the action has no key and can
     * only be triggered by other means in the future.
     */
    @Nullable
    public String key()
    {
        return this.key;
    }

    /** Optional display name hint for the client; {@code null} shows nothing. */
    @Nullable
    public Component label()
    {
        return this.label;
    }

    @Override
    public String toString()
    {
        return "TableActionDefinition[" + this.id + " " + this.type
                + (this.sourceZone != null ? " from " + this.sourceZone : "")
                + (this.key != null ? " key=" + this.key : "") + "]";
    }

    public static final class Builder
    {
        private final ResourceLocation id;
        @Nullable
        private Type type;
        @Nullable
        private ResourceLocation sourceZone;
        @Nullable
        private ResourceLocation targetZone;
        private int amount = 1;
        @Nullable
        private String key;
        @Nullable
        private Component label;

        private Builder(ResourceLocation id)
        {
            this.id = Objects.requireNonNull(id, "id");
        }

        public Builder type(Type type)
        {
            this.type = Objects.requireNonNull(type, "type");
            return this;
        }

        /** The pile DRAW takes from / SHUFFLE randomizes / RESET fills; those types require it. */
        public Builder sourceZone(@Nullable ResourceLocation sourceZone)
        {
            this.sourceZone = sourceZone;
            return this;
        }

        /** Reserved; kept in the model so a future primitive needs no migration. */
        public Builder targetZone(@Nullable ResourceLocation targetZone)
        {
            this.targetZone = targetZone;
            return this;
        }

        /** DRAW card count, default 1; must be positive. */
        public Builder amount(int amount)
        {
            this.amount = amount;
            return this;
        }

        /** GLFW key name (e.g. {@code key.keyboard.d}) or {@code null} for unbound. */
        public Builder key(@Nullable String key)
        {
            this.key = (key == null || key.isBlank()) ? null : key;
            return this;
        }

        public Builder label(@Nullable Component label)
        {
            this.label = label;
            return this;
        }

        public TableActionDefinition build()
        {
            if (this.type == null)
            {
                throw new IllegalStateException("Action " + this.id + " is missing a type");
            }
            if (this.amount < 1)
            {
                throw new IllegalStateException("Action " + this.id + " has a non-positive amount");
            }
            if ((this.type == Type.DRAW || this.type == Type.SHUFFLE || this.type == Type.RESET)
                    && this.sourceZone == null)
            {
                throw new IllegalStateException("Action " + this.id + " of type " + this.type
                        + " requires a source zone");
            }
            return new TableActionDefinition(this);
        }
    }
}
