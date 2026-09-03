package com.example.cardtable.network.packet;

import com.example.cardtable.card.CardActionService;
import com.example.cardtable.card.ZoneRef;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec2;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One player action on the table, sent client-to-server and executed
 * authoritatively by {@link CardActionService}. Every action is validated
 * server-side (seat, ownership); clients only predict visuals.
 */
public record CardActionPacket(BlockPos tablePosition, Action action)
{
    /** Sealed set of table actions; records carry only what the server needs. */
    public sealed interface Action
    {
        record Move(UUID instanceId, ZoneRef target, @Nullable Vec2 surfacePos) implements Action
        {
        }

        record Flip(UUID instanceId) implements Action
        {
        }

        record Rotate(UUID instanceId) implements Action
        {
        }

        /** Draws {@code count} cards from the draw pile top into the actor's hand. */
        record Draw(int count) implements Action
        {
        }

        record Shuffle(ZoneRef zone) implements Action
        {
        }
    }

    private static final byte KIND_MOVE = 0;
    private static final byte KIND_FLIP = 1;
    private static final byte KIND_ROTATE = 2;
    private static final byte KIND_DRAW = 3;
    private static final byte KIND_SHUFFLE = 4;

    public static void encode(CardActionPacket packet, FriendlyByteBuf buffer)
    {
        buffer.writeBlockPos(packet.tablePosition());
        Action action = packet.action();
        if (action instanceof Action.Move move)
        {
            buffer.writeByte(KIND_MOVE);
            buffer.writeUUID(move.instanceId());
            writeZone(buffer, move.target(), true);
            writeNullableVec(buffer, move.surfacePos());
        }
        else if (action instanceof Action.Flip flip)
        {
            buffer.writeByte(KIND_FLIP);
            buffer.writeUUID(flip.instanceId());
        }
        else if (action instanceof Action.Rotate rotate)
        {
            buffer.writeByte(KIND_ROTATE);
            buffer.writeUUID(rotate.instanceId());
        }
        else if (action instanceof Action.Draw draw)
        {
            buffer.writeByte(KIND_DRAW);
            buffer.writeVarInt(draw.count());
        }
        else if (action instanceof Action.Shuffle shuffle)
        {
            buffer.writeByte(KIND_SHUFFLE);
            writeZone(buffer, shuffle.zone(), false);
        }
    }

    public static CardActionPacket decode(FriendlyByteBuf buffer)
    {
        BlockPos tablePosition = buffer.readBlockPos();
        byte kind = buffer.readByte();
        Action action = switch (kind)
        {
            case KIND_MOVE ->
            {
                UUID instanceId = buffer.readUUID();
                ZoneRef target = readZone(buffer, true);
                yield new Action.Move(instanceId, target, readNullableVec(buffer));
            }
            case KIND_FLIP -> new Action.Flip(buffer.readUUID());
            case KIND_ROTATE -> new Action.Rotate(buffer.readUUID());
            case KIND_DRAW -> new Action.Draw(Math.max(1, Math.min(10, buffer.readVarInt())));
            case KIND_SHUFFLE -> new Action.Shuffle(readZone(buffer, false));
            default -> throw new IllegalStateException("Unknown card action kind: " + kind);
        };
        return new CardActionPacket(tablePosition, action);
    }

    public static void handle(CardActionPacket packet, Supplier<NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> CardActionService.handle(contextSupplier.get().getSender(), packet));
        context.setPacketHandled(true);
    }

    private static void writeZone(FriendlyByteBuf buffer, ZoneRef zone, boolean withSectionPos)
    {
        buffer.writeByte(zone.zone().ordinal());
        // Group-level zones (the two shared piles) carry no section position at
        // all, so their presence flag must not be written either: readZone()
        // skips it for them via isGroupLevel(). Writing it unconditionally left
        // one unconsumed byte per pile-targeted Move.
        if (withSectionPos && !zone.zone().isGroupLevel())
        {
            writeNullablePos(buffer, zone.sectionPos());
        }
    }

    private static ZoneRef readZone(FriendlyByteBuf buffer, boolean withSectionPos)
    {
        ZoneRef.Zone[] zones = ZoneRef.Zone.values();
        byte ordinal = buffer.readByte();
        if (ordinal < 0 || ordinal >= zones.length)
        {
            throw new IllegalStateException("Unknown zone ordinal: " + ordinal);
        }
        ZoneRef.Zone zone = zones[ordinal];
        // Mirrors writeZone(): the presence flag only exists for section zones.
        BlockPos sectionPos = withSectionPos && !zone.isGroupLevel() && buffer.readBoolean()
                ? buffer.readBlockPos() : null;
        return new ZoneRef(zone, sectionPos);
    }

    private static void writeNullablePos(FriendlyByteBuf buffer, @Nullable BlockPos pos)
    {
        buffer.writeBoolean(pos != null);
        if (pos != null)
        {
            buffer.writeBlockPos(pos);
        }
    }

    private static void writeNullableVec(FriendlyByteBuf buffer, @Nullable Vec2 vec)
    {
        buffer.writeBoolean(vec != null);
        if (vec != null)
        {
            buffer.writeFloat(vec.x);
            buffer.writeFloat(vec.y);
        }
    }

    @Nullable
    private static Vec2 readNullableVec(FriendlyByteBuf buffer)
    {
        return buffer.readBoolean() ? new Vec2(buffer.readFloat(), buffer.readFloat()) : null;
    }
}
