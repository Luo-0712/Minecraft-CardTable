package com.example.cardtable.network.packet;

import com.example.cardtable.card.CardActionService;
import com.example.cardtable.card.ZoneRef;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
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
        /**
         * @param faceDown     client intent for the card's landing face: only
         *                     honoured when the card leaves a hidden hand, and
         *                     {@code false} (face-up) by default, so playing a
         *                     card is always "as shown". Holding shift while
         *                     dropping requests the opposite — a face-down play.
         * @param playRotation table-space quarter-turn the card should land
         *                     with, also only honoured when leaving a hidden
         *                     hand. The client sends the inverse of its own
         *                     view rotation so the played card stays upright
         *                     on the actor's screen.
         */
        record Move(UUID instanceId, ZoneRef target, @Nullable Vec2 surfacePos, boolean faceDown,
                    int playRotation) implements Action
        {
        }

        record Flip(UUID instanceId) implements Action
        {
        }

        record Rotate(UUID instanceId) implements Action
        {
        }

        /**
         * Runs one action of the active layout's action table through its
         * generic primitive. {@code instanceId} is only used by the
         * card-targeted primitives (FLIP/ROTATE) and must be {@code null}
         * for pile-targeted ones (DRAW/SHUFFLE/RESET).
         */
        record Perform(ResourceLocation actionId, @Nullable UUID instanceId) implements Action
        {
        }
    }

    private static final byte KIND_MOVE = 0;
    private static final byte KIND_FLIP = 1;
    private static final byte KIND_ROTATE = 2;
    private static final byte KIND_PERFORM = 3;

    public static void encode(CardActionPacket packet, FriendlyByteBuf buffer)
    {
        buffer.writeBlockPos(packet.tablePosition());
        Action action = packet.action();
        if (action instanceof Action.Move move)
        {
            buffer.writeByte(KIND_MOVE);
            buffer.writeUUID(move.instanceId());
            writeZone(buffer, move.target());
            writeNullableVec(buffer, move.surfacePos());
            buffer.writeBoolean(move.faceDown());
            buffer.writeVarInt(move.playRotation());
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
        else if (action instanceof Action.Perform perform)
        {
            buffer.writeByte(KIND_PERFORM);
            buffer.writeResourceLocation(perform.actionId());
            buffer.writeBoolean(perform.instanceId() != null);
            if (perform.instanceId() != null)
            {
                buffer.writeUUID(perform.instanceId());
            }
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
                ZoneRef target = readZone(buffer);
                Vec2 surfacePos = readNullableVec(buffer);
                yield new Action.Move(instanceId, target, surfacePos, buffer.readBoolean(),
                        buffer.readVarInt());
            }
            case KIND_FLIP -> new Action.Flip(buffer.readUUID());
            case KIND_ROTATE -> new Action.Rotate(buffer.readUUID());
            case KIND_PERFORM ->
            {
                ResourceLocation actionId = buffer.readResourceLocation();
                UUID instanceId = buffer.readBoolean() ? buffer.readUUID() : null;
                yield new Action.Perform(actionId, instanceId);
            }
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

    private static void writeZone(FriendlyByteBuf buffer, ZoneRef zone)
    {
        // Open id addressing: the layout-declared zone id, or one of the
        // reserved ids (blank surface / own hand). The hand has no explicit
        // seat any more — the server always resolves it to the sender's own
        // seat.
        buffer.writeResourceLocation(zone.zoneId());
    }

    private static ZoneRef readZone(FriendlyByteBuf buffer)
    {
        return new ZoneRef(buffer.readResourceLocation());
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
