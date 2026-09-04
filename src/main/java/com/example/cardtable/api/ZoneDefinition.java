package com.example.cardtable.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Immutable description of one table zone: a bounded region of the playfield
 * with a placement rule. Zones are declared by content packs or third-party
 * mods inside a {@link TableLayoutDefinition}; the core places and validates
 * them but never interprets what a zone means for any game's rules.
 *
 * <p>The rect and card coordinates are normalized (0..1) and interpreted in
 * the coordinate space selected by {@link Scope}: SHARED zones cover the whole
 * merged playfield, PER_SEAT zones cover one seat's cell. The origin is the
 * top-left of that space, x grows right and y grows down.</p>
 */
public final class ZoneDefinition
{
    /** Placement rule for cards inside the zone bounds. */
    public enum Kind
    {
        /** Ordered pile without coordinates; the list end is the pile top. */
        STACK,
        /** Fixed slots ({@code capacity} = slot count); drops quantize to slot cells. */
        GRID,
        /** Free placement anywhere inside the rect. */
        FREE
    }

    /** Which coordinate space the rect and card positions live in. */
    public enum Scope
    {
        /** One instance per table group; the rect covers the whole playfield. */
        SHARED,
        /** One instance per seat; the rect covers that seat's own cell. */
        PER_SEAT
    }

    /** Sync visibility hook; the first iteration only ever accepts {@link #PUBLIC}. */
    public enum Visibility
    {
        /** Broadcast to everyone (the only value content may declare today). */
        PUBLIC,
        /** Reserved for future owner-only zones; kept in the model, rejected by content. */
        OWNER_ONLY
    }

    private final ResourceLocation id;
    private final Kind kind;
    private final Scope scope;
    private final float x;
    private final float y;
    private final float w;
    private final float h;
    private final int capacity;
    private final Visibility visibility;
    @Nullable
    private final Component label;

    private ZoneDefinition(Builder builder)
    {
        this.id = builder.id;
        this.kind = builder.kind;
        this.scope = builder.scope;
        this.x = builder.x;
        this.y = builder.y;
        this.w = builder.w;
        this.h = builder.h;
        this.capacity = builder.capacity;
        this.visibility = builder.visibility;
        this.label = builder.label;
    }

    public static Builder builder(ResourceLocation id)
    {
        return new Builder(id);
    }

    /** Unique zone id within its layout, e.g. {@code cardtable:draw_pile}. */
    public ResourceLocation id()
    {
        return this.id;
    }

    public Kind kind()
    {
        return this.kind;
    }

    public Scope scope()
    {
        return this.scope;
    }

    /** Left edge of the rect in scope-space normalized coordinates. */
    public float x()
    {
        return this.x;
    }

    /** Top edge of the rect in scope-space normalized coordinates. */
    public float y()
    {
        return this.y;
    }

    /** Width of the rect in scope-space normalized coordinates. */
    public float w()
    {
        return this.w;
    }

    /** Height of the rect in scope-space normalized coordinates. */
    public float h()
    {
        return this.h;
    }

    /** Max cards the zone holds; {@code 0} means unlimited. */
    public int capacity()
    {
        return this.capacity;
    }

    public Visibility visibility()
    {
        return this.visibility;
    }

    /** Optional zone name hint for the client; {@code null} shows nothing. */
    @Nullable
    public Component label()
    {
        return this.label;
    }

    /**
     * Snaps a zone-local normalized drop point to the center of its GRID slot.
     * A grid is a single row of {@code capacity} columns spanning the zone
     * width; the x coordinate picks the column and the y coordinate is pinned
     * to the row center. Quantization is idempotent — feeding a result back
     * yields the same point — so the client may pre-quantize for instant
     * feedback and the server re-quantizes to the identical slot.
     *
     * @return {@code {x, y}} normalized to the snapped slot center
     */
    public static float[] quantizeGrid(float x, float y, int capacity)
    {
        int columns = Math.max(1, capacity);
        int column = Math.min(columns - 1, Math.max(0, (int) (x * columns)));
        return new float[] {(column + 0.5F) / columns, 0.5F};
    }

    @Override
    public String toString()
    {
        return "ZoneDefinition[" + this.id + " " + this.kind + "/" + this.scope + "]";
    }

    public static final class Builder
    {
        private final ResourceLocation id;
        private Kind kind;
        private Scope scope;
        private float x;
        private float y;
        private float w;
        private float h;
        private int capacity;
        private Visibility visibility = Visibility.PUBLIC;
        @Nullable
        private Component label;

        private Builder(ResourceLocation id)
        {
            this.id = java.util.Objects.requireNonNull(id, "id");
        }

        public Builder kind(Kind kind)
        {
            this.kind = java.util.Objects.requireNonNull(kind, "kind");
            return this;
        }

        public Builder scope(Scope scope)
        {
            this.scope = java.util.Objects.requireNonNull(scope, "scope");
            return this;
        }

        /** Sets the normalized rect; {@code x + w} and {@code y + h} must not exceed 1. */
        public Builder rect(float x, float y, float w, float h)
        {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            return this;
        }

        /** {@code 0} (the default) means unlimited; GRID zones require a positive count. */
        public Builder capacity(int capacity)
        {
            this.capacity = capacity;
            return this;
        }

        public Builder visibility(Visibility visibility)
        {
            this.visibility = java.util.Objects.requireNonNull(visibility, "visibility");
            return this;
        }

        public Builder label(@Nullable Component label)
        {
            this.label = label;
            return this;
        }

        public ZoneDefinition build()
        {
            if (this.kind == null)
            {
                throw new IllegalStateException("Zone " + this.id + " is missing a kind");
            }
            if (this.scope == null)
            {
                throw new IllegalStateException("Zone " + this.id + " is missing a scope");
            }
            if (this.x < 0.0F || this.y < 0.0F || this.w <= 0.0F || this.h <= 0.0F
                    || this.x + this.w > 1.0F || this.y + this.h > 1.0F)
            {
                throw new IllegalStateException("Zone " + this.id + " has an out-of-range rect ["
                        + this.x + ", " + this.y + ", " + this.w + ", " + this.h + "]");
            }
            if (this.capacity < 0)
            {
                throw new IllegalStateException("Zone " + this.id + " has a negative capacity");
            }
            if (this.kind == Kind.GRID && this.capacity <= 0)
            {
                throw new IllegalStateException("Grid zone " + this.id + " requires a positive capacity");
            }
            return new ZoneDefinition(this);
        }
    }
}
