package com.example.cardtable.client.screen;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.menu.CardTableMenu;
import com.example.cardtable.network.NetworkHandler;
import com.example.cardtable.network.packet.CardTableMembershipPacket;
import com.example.cardtable.table.TableGraph;
import com.example.cardtable.table.TableGroupService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fullscreen top-down view of the table surface. Right-clicking the block
 * seats the player at that specific table and opens this view: the playfield
 * fills the whole screen, one seat per table block rings the edge with each
 * seated player's portrait and name, and development debug info stays in the
 * bottom-right corner (F3 toggles it while the view is open).
 */
public class CardTableScreen extends AbstractContainerScreen<CardTableMenu>
{
    /** The same texture used by the table block, so the canvas matches the board style. */
    private static final ResourceLocation TABLE_TEXTURE =
            new ResourceLocation(CardTableMod.MODID, "textures/block/quartz_block_top.png");

    private static final int SEAT_SIZE = 26;
    /** Seat ring inset from the screen edges. */
    private static final int SEAT_INSET = 40;
    /** Side gaps around the board so the surrounding world stays visible on the left/right, like a container menu. */
    private static final int SIDE_MARGIN = 18;
    /** Playfield inset from the screen edges, clamped down for small windows. */
    private static final int PLAYFIELD_INSET_X = 140;
    private static final int PLAYFIELD_INSET_Y = 96;

    // Palette derived from the table texture: warm wood, cream accents.
    private static final int COLOR_WOOD_DARK = 0xFF5A3D26;
    private static final int COLOR_PLAYFIELD = 0x66EDE7D9;
    private static final int COLOR_PLAYFIELD_EDGE = 0xFF6B4A2F;
    private static final int COLOR_TEXT_DARK = 0xFF3A2A1A;
    private static final int COLOR_SEAT = 0xFF6B4A2F;
    private static final int COLOR_SEAT_EMPTY = 0x904A3624;
    private static final int COLOR_SEAT_SELF = 0xFFD4B483;
    private static final int COLOR_HOVER = 0xFFF5F0E6;
    private static final int COLOR_TEXT_DIM = 0xFFC9BFA8;
    private static final int COLOR_ERROR = 0xFF8B2E2E;

    private final Map<UUID, Player> resolvedPlayers = new HashMap<>();
    private long cachedVersion = -1L;

    private boolean showDebugInfo = true;
    private Component status = Component.empty();
    private List<SeatSlot> seats = List.of();
    private int lastMouseX;
    private int lastMouseY;

    public CardTableScreen(CardTableMenu menu, Inventory inventory, Component title)
    {
        super(menu, inventory, title);
    }

    @Override
    protected void init()
    {
        // Near-fullscreen: the board keeps a small margin around the window.
        this.imageWidth = this.width;
        this.imageHeight = this.height;
        this.leftPos = 0;
        this.topPos = 0;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        if (keyCode == GLFW.GLFW_KEY_F3)
        {
            this.showDebugInfo = !this.showDebugInfo;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // The fullscreen table draws no menu labels: the surface itself is the UI.
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY)
    {
    }

    // The merged group containing the clicked table, resolved against the
    // client level's synced block entities (same BFS as the server).
    @Nullable
    private TableGroupService.GroupView clientGroup()
    {
        if (this.minecraft == null || this.minecraft.level == null)
        {
            return null;
        }
        return TableGroupService.resolve(this.minecraft.level, this.menu.getTablePosition());
    }

    // One seat per table block, ringed in the group's deterministic position
    // order; {@code occupantId == null} marks an empty seat.
    private List<SeatSlot> computeSeats(TableGroupService.GroupView group)
    {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        // Elliptical ring sized to the board (which is inset from the window),
        // so seats hug the table edge on wide and tall windows alike.
        double radiusX = Math.max(1.0D, this.width / 2.0D - SIDE_MARGIN - SEAT_INSET);
        double radiusY = Math.max(1.0D, this.height / 2.0D - SEAT_INSET);

        List<BlockPos> positions = new ArrayList<>(group.positions());
        // Same deterministic order as master election, so the ring is stable.
        positions.sort(TableGraph::comparePositions);
        int seatCount = positions.size();
        List<SeatSlot> slots = new ArrayList<>(seatCount);
        for (int index = 0; index < seatCount; index++)
        {
            // First seat at the top, then evenly around the table edge.
            double angle = -Math.PI / 2.0D + (Math.PI * 2.0D * index) / seatCount;
            int seatX = centerX + (int) Math.round(Math.cos(angle) * radiusX);
            int seatY = centerY + (int) Math.round(Math.sin(angle) * radiusY);
            UUID occupantId = this.occupantAt(positions.get(index));
            slots.add(new SeatSlot(index, seatX, seatY, occupantId));
        }
        return slots;
    }

    @Nullable
    private UUID occupantAt(BlockPos position)
    {
        if (this.minecraft == null || this.minecraft.level == null)
        {
            return null;
        }
        BlockEntity blockEntity = this.minecraft.level.getBlockEntity(position);
        return blockEntity instanceof CardTableBlockEntity tableEntity
                ? tableEntity.getSectionState().getOccupantId() : null;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY)
    {
        // Board surface: the block's own texture. It fills the window top to
        // bottom (no vertical gap) while leaving a gap on the left/right so the
        // surrounding world stays visible, and framed with a rim line so the
        // surface reads as a table floating in the world.
        int boardLeft = this.leftPos + SIDE_MARGIN;
        int boardTop = this.topPos;
        int boardWidth = this.width - SIDE_MARGIN * 2;
        int boardHeight = this.height;
        graphics.blit(TABLE_TEXTURE, boardLeft, boardTop, boardWidth, boardHeight,
                0.0F, 0.0F, 16, 16, 16, 16);
        graphics.renderOutline(boardLeft, boardTop, boardWidth, boardHeight, COLOR_WOOD_DARK);

        // Reserved play area in the centre for a later phase.
        int insetX = Math.min(PLAYFIELD_INSET_X, this.width / 6);
        int insetY = Math.min(PLAYFIELD_INSET_Y, this.height / 5);
        int playLeft = boardLeft + insetX;
        int playTop = boardTop + insetY;
        int playWidth = this.width - insetX * 2 - SIDE_MARGIN * 2;
        int playHeight = this.height - insetY * 2;
        graphics.fill(playLeft, playTop, playLeft + playWidth, playTop + playHeight, COLOR_PLAYFIELD);
        graphics.renderOutline(playLeft, playTop, playWidth, playHeight, COLOR_PLAYFIELD_EDGE);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
    {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;

        // No fullscreen menu backdrop on purpose: the surrounding world stays
        // visible around the board, just like a container menu does.
        TableGroupService.GroupView group = this.clientGroup();
        this.seats = group != null ? this.computeSeats(group) : List.of();

        super.render(graphics, mouseX, mouseY, partialTick);

        this.renderSeats(graphics, mouseX, mouseY);
        this.renderStatus(graphics);
        this.renderDebug(graphics, group);
    }

    private void renderSeats(GuiGraphics graphics, int mouseX, int mouseY)
    {
        UUID selfId = this.minecraft.player != null ? this.minecraft.player.getUUID() : null;

        for (SeatSlot seat : this.seats)
        {
            int half = SEAT_SIZE / 2;
            int left = seat.x() - half;
            int top = seat.y() - half;
            boolean hovered = mouseX >= left && mouseX < left + SEAT_SIZE && mouseY >= top && mouseY < top + SEAT_SIZE;
            boolean self = seat.occupantId() != null && seat.occupantId().equals(selfId);
            boolean joinable = seat.occupantId() == null;

            if (seat.occupantId() == null)
            {
                graphics.fill(left, top, left + SEAT_SIZE, top + SEAT_SIZE, COLOR_SEAT_EMPTY);
            }
            else
            {
                graphics.fill(left, top, left + SEAT_SIZE, top + SEAT_SIZE, COLOR_SEAT);
            }

            int outline = self ? COLOR_SEAT_SELF : hovered && joinable ? COLOR_HOVER : COLOR_WOOD_DARK;
            graphics.renderOutline(left, top, SEAT_SIZE, SEAT_SIZE, outline);

            if (seat.occupantId() != null)
            {
                this.renderOccupant(graphics, seat, left, top);
            }
            else
            {
                graphics.drawCenteredString(this.font, Component.translatable("gui.cardtable.seat_empty"),
                        seat.x(), seat.y() - 4, COLOR_TEXT_DIM);
            }
        }
    }

    private void renderOccupant(GuiGraphics graphics, SeatSlot seat, int left, int top)
    {
        Player player = this.resolvePlayer(seat.occupantId());
        if (player != null)
        {
            // 3D portrait anchored at the seat plate's bottom edge; entity extends
            // upward by roughly 2x the scale, so scale 10 fits the 26px plate.
            InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,
                    seat.x, top + SEAT_SIZE - 3, 10,
                    this.lastMouseX - seat.x, this.lastMouseY - seat.y, player);
        }
        else
        {
            // Neutral placeholder figure for a seated player not present in the scene.
            graphics.fill(left + 8, top + 5, left + 18, top + 15, COLOR_TEXT_DIM);
            graphics.fill(left + 5, top + 16, left + 21, top + 23, COLOR_TEXT_DIM);
        }

        String name = player != null ? player.getName().getString() : null;
        if (name != null)
        {
            if (name.length() > 10)
            {
                name = name.substring(0, 10);
            }
            graphics.drawCenteredString(this.font, name, seat.x(), top + SEAT_SIZE + 2, COLOR_TEXT_DARK);
        }
    }

    @Nullable
    private Player resolvePlayer(@Nullable UUID playerId)
    {
        if (playerId == null || this.minecraft == null || this.minecraft.level == null)
        {
            return null;
        }
        // Re-resolve only when the table state changed.
        if (this.cachedVersion != this.menu.getVersion())
        {
            this.cachedVersion = this.menu.getVersion();
            this.resolvedPlayers.clear();
        }
        return this.resolvedPlayers.computeIfAbsent(playerId,
                id -> this.minecraft.level.getPlayerByUUID(id));
    }

    private void renderStatus(GuiGraphics graphics)
    {
        Component message = this.status;
        if (message.getString().isEmpty()
                && !this.menu.isParticipant(this.minecraft.player)
                && this.menu.getSeatCount() > 0
                && this.menu.getSeatedCount() >= this.menu.getSeatCount())
        {
            message = Component.translatable("gui.cardtable.full");
        }
        if (!message.getString().isEmpty())
        {
            graphics.drawCenteredString(this.font, message,
                    this.width / 2, this.height - 34, COLOR_ERROR);
        }
    }

    // Development debug info lives in the bottom-right corner, toggled with F3.
    private void renderDebug(GuiGraphics graphics, @Nullable TableGroupService.GroupView group)
    {
        if (!this.showDebugInfo || group == null || this.minecraft == null || this.minecraft.level == null)
        {
            return;
        }
        BlockEntity master = this.minecraft.level.getBlockEntity(group.masterPos());
        if (!(master instanceof CardTableBlockEntity masterEntity))
        {
            return;
        }
        List<Component> lines = List.of(
                Component.translatable("gui.cardtable.members",
                        this.menu.getSeatedCount(), this.menu.getSeatCount()),
                Component.translatable("gui.cardtable.version",
                        masterEntity.getGroupState().getVersion()),
                Component.translatable("gui.cardtable.table_id",
                        masterEntity.getGroupState().getTableId().toString().substring(0, 8)));
        int y = this.height - 12;
        for (Component line : lines)
        {
            graphics.drawString(this.font, line, this.width - 4 - this.font.width(line), y, COLOR_TEXT_DIM, true);
            y -= 12;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        if (button == 0 && !this.seats.isEmpty())
        {
            for (SeatSlot seat : this.seats)
            {
                int half = SEAT_SIZE / 2;
                boolean hit = mouseX >= seat.x() - half && mouseX < seat.x() + half
                        && mouseY >= seat.y() - half && mouseY < seat.y() + half;
                if (!hit)
                {
                    continue;
                }
                if (seat.occupantId() == null)
                {
                    this.tryJoin();
                }
                else if (this.minecraft.player != null && seat.occupantId().equals(this.minecraft.player.getUUID()))
                {
                    this.tryLeave();
                }
                break;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void tryJoin()
    {
        if (this.menu.isParticipant(this.minecraft.player))
        {
            return;
        }
        if (this.menu.getSeatCount() > 0 && this.menu.getSeatedCount() >= this.menu.getSeatCount())
        {
            this.status = Component.translatable("gui.cardtable.full");
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(new CardTableMembershipPacket(true, this.menu.getTablePosition()));
    }

    private void tryLeave()
    {
        if (!this.menu.isParticipant(this.minecraft.player))
        {
            return;
        }
        NetworkHandler.CHANNEL.sendToServer(new CardTableMembershipPacket(false, this.menu.getTablePosition()));
        this.status = Component.translatable("gui.cardtable.leave_sent");
    }

    /** One seat around the table edge; {@code occupantId == null} marks an empty seat. */
    private record SeatSlot(int index, int x, int y, @Nullable UUID occupantId) {}
}
