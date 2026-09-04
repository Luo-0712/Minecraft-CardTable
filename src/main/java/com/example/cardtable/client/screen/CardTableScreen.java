package com.example.cardtable.client.screen;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.api.CardDefinition;
import com.example.cardtable.api.CardRegistry;
import com.example.cardtable.api.CardSetDefinition;
import com.example.cardtable.api.TableLayoutDefinition;
import com.example.cardtable.api.ZoneDefinition;
import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.card.CardInstance;
import com.example.cardtable.card.SurfaceZone;
import com.example.cardtable.card.ZoneState;
import com.example.cardtable.client.ClientHandStore;
import com.example.cardtable.client.card.CardTextureResolver;
import com.example.cardtable.menu.CardTableMenu;
import com.example.cardtable.network.NetworkHandler;
import com.example.cardtable.network.packet.CardActionPacket;
import com.example.cardtable.network.packet.CardTableMembershipPacket;
import com.example.cardtable.card.ZoneRef;
import com.example.cardtable.table.TableGraph;
import com.example.cardtable.table.TableGroupService;
import com.example.cardtable.table.TableGroupState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec2;
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
 *
 * <p>The playfield is subdivided into one cell per table block (deterministic
 * order, same comparator as master election). Each cell renders its block's
 * surface cards; the group piles render at the playfield's top corners; the
 * occupant's hand renders as a fanned strip above the player inventory, and
 * the deck slot lives in the top-right corner. Surface and pile data come
 * from the synced block entities, the hand only from {@link ClientHandStore}.</p>
 */
public class CardTableScreen extends AbstractContainerScreen<CardTableMenu>
{
    /** The same texture used by the table block, so the canvas matches the board style. */
    private static final ResourceLocation TABLE_TEXTURE =
            new ResourceLocation(CardTableMod.MODID, "textures/block/quartz_block_top.png");

    /** Vanilla "generic_54" container panel, reused as the backpack background. */
    private static final ResourceLocation INVENTORY_PANEL =
            new ResourceLocation(CardTableMod.MODID, "textures/gui/generic_54.png");
    // The panel artwork occupies only the top-left of the 256x256 atlas; the
    // rest is transparent. Sizing the nine-slice to the full atlas made the
    // right/bottom borders sample transparent pixels, so the visible panel
    // fell short of the slot grid.
    private static final int INVENTORY_PANEL_TEX_WIDTH = 176;
    private static final int INVENTORY_PANEL_TEX_HEIGHT = 222;
    /** Nine-slice border of the panel artwork, in source pixels (1px outline + 2px highlight). */
    private static final int INVENTORY_PANEL_BORDER = 3;
    /** Flat interior strip (title area above the slot grid) stretched into the nine-slice centre. */
    private static final int INVENTORY_PANEL_FILL_U = 64;
    private static final int INVENTORY_PANEL_FILL_V = 8;
    private static final int INVENTORY_PANEL_FILL_WIDTH = 32;
    private static final int INVENTORY_PANEL_FILL_HEIGHT = 4;
    /** Gap between the slot grid and the panel edge. */
    private static final int INVENTORY_PANEL_PADDING = 8;

    private static final int SEAT_SIZE = 26;
    /** Seat ring inset from the screen edges. */
    private static final int SEAT_INSET = 40;
    /** Side gaps around the board so the surrounding world stays visible on the left/right, like a container menu. */
    private static final int SIDE_MARGIN = 18;
    /** Playfield inset from the screen edges, clamped down for small windows. */
    private static final int PLAYFIELD_INSET_X = 140;
    private static final int PLAYFIELD_INSET_Y = 96;

    // Card rendering: surface cards are bigger than hand cards; both keep the
    // same 34:48 (roughly poker) aspect ratio.
    private static final int CARD_WIDTH = 34;
    private static final int CARD_HEIGHT = 48;
    private static final int HAND_CARD_WIDTH = 30;
    private static final int HAND_CARD_HEIGHT = 42;
    private static final int SLOT_SIZE = 18;
    private static final int CELL_GAP = 4;
    private static final int PILE_STACK_OFFSET = 2;
    private static final int PILE_MAX_LAYERS = 5;

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
    private static final int COLOR_CARD_BORDER = 0xFF2E2620;
    private static final int COLOR_CARD_MISSING = 0xFF555555;
    private static final int COLOR_PANEL = 0x90241C14;
    private static final int COLOR_PANEL_EDGE = 0xFF6B4A2F;

    private final Map<UUID, Player> resolvedPlayers = new HashMap<>();
    private long cachedVersion = -1L;

    private boolean showDebugInfo = true;
    private Component status = Component.empty();
    private List<SeatSlot> seats = List.of();
    private List<Cell> cells = List.of();
    private int lastMouseX;
    private int lastMouseY;

    /** Card currently held by the mouse; only a client preview, the server owns the real move. */
    @Nullable
    private Drag drag;
    @Nullable
    private Rect handStripRect;

    // Layout-driven render state, rebuilt every frame by renderZones():
    // drop/hover targets in render order (last entry = topmost) and the cards
    // currently visible on the playfield with their pixel rects.
    private List<ZoneHit> zoneHits = List.of();
    private List<RenderedCard> renderedCards = List.of();
    // True while the bound layout vanished from the local registry: the view
    // degrades to the default layout and the status line warns about it.
    private boolean layoutMissing;

    // Inventory layout, written by layoutSlots() and reused by the hand strip.
    private int inventoryLeft;
    private int inventoryMainTop;
    private int inventoryHotbarTop;

    // Player inventory is hidden by default so the fullscreen table view does
    // not show the item-bar UI; a button next to the deck slot toggles it.
    // Purely a client display preference, never synced to the server.
    private boolean showInventory = false;
    @Nullable
    private Rect inventoryToggleRect;

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
        this.layoutSlots();
    }

    // Slot positions are irrelevant server-side; only the client lays them
    // out (deck slot top-right, player inventory bottom-centre). Slot.x/y are
    // mutable through the project's access transformer.
    private void layoutSlots()
    {
        this.menu.getDeckSlot().x = this.width - SLOT_SIZE - 12;
        this.menu.getDeckSlot().y = 12;

        int inventoryWidth = 9 * SLOT_SIZE;
        this.inventoryLeft = this.width / 2 - inventoryWidth / 2;
        this.inventoryHotbarTop = this.height - SLOT_SIZE - 6;
        this.inventoryMainTop = this.inventoryHotbarTop - 3 * SLOT_SIZE;

        // When the inventory is hidden (default) its slots are pushed far
        // off-screen: Slot.x/y are mutable via the project's access transformer,
        // so neither the item-bar renders nor does it intercept clicks. The
        // layout above still feeds the hand strip's on-screen position.
        List<Slot> inventorySlots = this.menu.getInventorySlots();
        int offset = this.showInventory ? 0 : -10000;
        for (int index = 0; index < 27; index++)
        {
            Slot slot = inventorySlots.get(index);
            slot.x = this.inventoryLeft + (index % 9) * SLOT_SIZE + offset;
            slot.y = this.inventoryMainTop + (index / 9) * SLOT_SIZE;
        }
        for (int index = 27; index < 36; index++)
        {
            Slot slot = inventorySlots.get(index);
            slot.x = this.inventoryLeft + (index - 27) * SLOT_SIZE + offset;
            slot.y = this.inventoryHotbarTop;
        }
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

    // One grid cell per table block inside the playfield; deterministic order
    // keeps every client's layout identical.
    private List<Cell> computeCells(TableGroupService.GroupView group)
    {
        int insetX = Math.min(PLAYFIELD_INSET_X, this.width / 6);
        int insetY = Math.min(PLAYFIELD_INSET_Y, this.height / 5);
        int playLeft = SIDE_MARGIN + insetX;
        int playTop = insetY;
        int playWidth = this.width - insetX * 2 - SIDE_MARGIN * 2;
        int playHeight = this.height - insetY * 2;

        List<BlockPos> positions = new ArrayList<>(group.positions());
        positions.sort(TableGraph::comparePositions);
        int count = Math.max(1, positions.size());
        int columns = (int) Math.ceil(Math.sqrt(count));
        int rows = (count + columns - 1) / columns;
        float cellWidth = (float) playWidth / columns;
        float cellHeight = (float) playHeight / rows;

        List<Cell> cells = new ArrayList<>(positions.size());
        for (int index = 0; index < positions.size(); index++)
        {
            int column = index % columns;
            int row = index / columns;
            cells.add(new Cell(positions.get(index),
                    playLeft + (int) (column * cellWidth) + CELL_GAP,
                    playTop + (int) (row * cellHeight) + CELL_GAP,
                    (int) cellWidth - CELL_GAP * 2,
                    (int) cellHeight - CELL_GAP * 2));
        }
        return cells;
    }

    private int playfieldLeft()
    {
        int insetX = Math.min(PLAYFIELD_INSET_X, this.width / 6);
        return SIDE_MARGIN + insetX;
    }

    private int playfieldTop()
    {
        return Math.min(PLAYFIELD_INSET_Y, this.height / 5);
    }

    private int playfieldWidth()
    {
        int insetX = Math.min(PLAYFIELD_INSET_X, this.width / 6);
        return this.width - insetX * 2 - SIDE_MARGIN * 2;
    }

    private int playfieldHeight()
    {
        int insetY = Math.min(PLAYFIELD_INSET_Y, this.height / 5);
        return this.height - insetY * 2;
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

        // Reserved play area in the centre with one cell per table block.
        int insetX = Math.min(PLAYFIELD_INSET_X, this.width / 6);
        int insetY = Math.min(PLAYFIELD_INSET_Y, this.height / 5);
        int playLeft = boardLeft + insetX;
        int playTop = boardTop + insetY;
        int playWidth = this.width - insetX * 2 - SIDE_MARGIN * 2;
        int playHeight = this.height - insetY * 2;
        graphics.fill(playLeft, playTop, playLeft + playWidth, playTop + playHeight, COLOR_PLAYFIELD);
        graphics.renderOutline(playLeft, playTop, playWidth, playHeight, COLOR_PLAYFIELD_EDGE);

        this.renderInventoryPanel(graphics);
        this.renderDeckSlotBackground(graphics);
        this.renderZones(graphics, mouseX, mouseY);
    }

    private void renderDeckSlotBackground(GuiGraphics graphics)
    {
        Slot deckSlot = this.menu.getDeckSlot();
        int left = deckSlot.x - 1;
        int top = deckSlot.y - 1;
        graphics.fill(left, top, left + SLOT_SIZE, top + SLOT_SIZE, COLOR_PANEL);
        graphics.renderOutline(left, top, SLOT_SIZE, SLOT_SIZE, COLOR_PANEL_EDGE);
        Component label = Component.translatable("gui.cardtable.deck_slot");
        graphics.drawString(this.font, label, left + SLOT_SIZE + 4, top + 5, COLOR_TEXT_DIM, true);

        // Backpack toggle button directly under the deck slot. Drawn every frame
        // so its hit rect stays in sync with the (resize-aware) deck slot position.
        Component toggleLabel = Component.translatable(this.showInventory
                ? "gui.cardtable.inv_hide" : "gui.cardtable.inv_show");
        int toggleW = this.font.width(toggleLabel) + 10;
        int toggleH = 14;
        int toggleX = deckSlot.x;
        int toggleY = deckSlot.y + SLOT_SIZE + 4;
        this.inventoryToggleRect = new Rect(toggleX, toggleY, toggleW, toggleH);
        graphics.fill(toggleX, toggleY, toggleX + toggleW, toggleY + toggleH, COLOR_PANEL);
        graphics.renderOutline(toggleX, toggleY, toggleW, toggleH,
                this.showInventory ? COLOR_SEAT_SELF : COLOR_PANEL_EDGE);
        graphics.drawString(this.font, toggleLabel, toggleX + (toggleW - this.font.width(toggleLabel)) / 2,
                toggleY + (toggleH - this.font.lineHeight) / 2, COLOR_TEXT_DARK, false);
    }

    // The backpack panel wraps the slot grid (its layout vars stay on-screen even
    // while the slots themselves are pushed off-screen when hidden). Only drawn
    // while the inventory toggle is on, so it disappears with the slots.
    private void renderInventoryPanel(GuiGraphics graphics)
    {
        if (!this.showInventory)
        {
            return;
        }
        int panelLeft = this.inventoryLeft - INVENTORY_PANEL_PADDING;
        int panelTop = this.inventoryMainTop - INVENTORY_PANEL_PADDING;
        int panelRight = this.inventoryLeft + 9 * SLOT_SIZE + INVENTORY_PANEL_PADDING;
        int panelBottom = this.inventoryHotbarTop + SLOT_SIZE + INVENTORY_PANEL_PADDING;
        this.blitNineSliced(graphics, INVENTORY_PANEL, panelLeft, panelTop,
                panelRight - panelLeft, panelBottom - panelTop);
    }

    // Manual nine-slice: stretch the panel artwork to any rect while keeping
    // its border crisp. 1.20.1's GuiGraphics has no public blitNineSliced, so
    // the 9 regions are drawn with plain blits (corners, edges, centre). The
    // centre is sampled from the flat title strip, not the slot-grid interior,
    // so stretching it never leaks the vanilla chest grid into the panel.
    private void blitNineSliced(GuiGraphics graphics, ResourceLocation texture, int x, int y, int w, int h)
    {
        int b = INVENTORY_PANEL_BORDER;
        int texW = INVENTORY_PANEL_TEX_WIDTH;
        int texH = INVENTORY_PANEL_TEX_HEIGHT;
        int midW = texW - b * 2;
        int midH = texH - b * 2;
        int innerW = Math.max(0, w - b * 2);
        int innerH = Math.max(0, h - b * 2);

        // Corners.
        graphics.blit(texture, x, y, b, b, 0.0F, 0.0F, b, b, texW, texH);
        graphics.blit(texture, x + w - b, y, b, b, (float) (texW - b), 0.0F, b, b, texW, texH);
        graphics.blit(texture, x, y + h - b, b, b, 0.0F, (float) (texH - b), b, b, texW, texH);
        graphics.blit(texture, x + w - b, y + h - b, b, b, (float) (texW - b), (float) (texH - b), b, b, texW, texH);

        // Edges (stretch along their length).
        graphics.blit(texture, x + b, y, innerW, b, (float) b, 0.0F, midW, b, texW, texH);
        graphics.blit(texture, x + b, y + h - b, innerW, b, (float) b, (float) (texH - b), midW, b, texW, texH);
        graphics.blit(texture, x, y + b, b, innerH, 0.0F, (float) b, b, midH, texW, texH);
        graphics.blit(texture, x + w - b, y + b, b, innerH, (float) (texW - b), (float) b, b, midH, texW, texH);

        // Centre (stretch the flat strip both ways).
        graphics.blit(texture, x + b, y + b, innerW, innerH,
                (float) INVENTORY_PANEL_FILL_U, (float) INVENTORY_PANEL_FILL_V,
                INVENTORY_PANEL_FILL_WIDTH, INVENTORY_PANEL_FILL_HEIGHT, texW, texH);
    }

    // Layout-driven playfield rendering -------------------------------------

    /**
     * Resolves the layout the table currently runs against the local registry.
     * A null binding is the classic default; a binding that vanished from the
     * registry degrades to the default and flags {@link #layoutMissing} so the
     * status line can warn (bad drops are then rejected server-side, so this
     * only ever affects appearance).
     */
    private TableLayoutDefinition resolveActiveLayout(TableGroupState groupState)
    {
        this.layoutMissing = false;
        ResourceLocation layoutId = groupState.getActiveLayoutId();
        if (layoutId == null)
        {
            return TableLayoutDefinition.defaultLayout();
        }
        TableLayoutDefinition layout = CardRegistry.getLayout(layoutId);
        if (layout == null)
        {
            this.layoutMissing = true;
            return TableLayoutDefinition.defaultLayout();
        }
        return layout.normalized();
    }

    /**
     * Rebuilds the frame's zone render/hit data from the active layout and
     * draws every zone by kind. Cell outlines are drawn first (board
     * structure); zones render in layout declaration order, so the last zone
     * is visually topmost and hit-tested first.
     */
    private void renderZones(GuiGraphics graphics, int mouseX, int mouseY)
    {
        List<RenderedCard> cards = new ArrayList<>();
        List<ZoneHit> hits = new ArrayList<>();
        this.renderedCards = cards;
        this.zoneHits = hits;
        this.layoutMissing = false;
        if (this.minecraft == null || this.minecraft.level == null)
        {
            return;
        }
        TableGroupService.GroupView group = this.clientGroup();
        TableGroupState groupState = group != null ? clientGroupState(group) : null;
        if (groupState == null)
        {
            return;
        }
        TableLayoutDefinition layout = resolveActiveLayout(groupState);

        for (Cell cell : this.cells)
        {
            graphics.renderOutline(cell.x(), cell.y(), cell.width(), cell.height(), COLOR_PLAYFIELD_EDGE);
        }

        int playLeft = playfieldLeft();
        int playTop = playfieldTop();
        int playWidth = playfieldWidth();
        int playHeight = playfieldHeight();
        for (ZoneDefinition zone : layout.zones())
        {
            if (TableLayoutDefinition.ZONE_HAND.equals(zone.id()))
            {
                continue; // the hand renders as the fixed strip, never on the playfield
            }
            if (zone.scope() == ZoneDefinition.Scope.SHARED)
            {
                renderZone(graphics, zone, scaledRect(playLeft, playTop, playWidth, playHeight, zone),
                        null, groupState, cards, hits, mouseX, mouseY);
            }
            else
            {
                for (Cell cell : this.cells)
                {
                    if (!(this.minecraft.level.getBlockEntity(cell.position()) instanceof CardTableBlockEntity section))
                    {
                        continue;
                    }
                    renderZone(graphics, zone, scaledRect(cell.x(), cell.y(), cell.width(), cell.height(), zone),
                            section, groupState, cards, hits, mouseX, mouseY);
                }
            }
        }
    }

    private void renderZone(GuiGraphics graphics, ZoneDefinition zone, Rect rect,
                            @Nullable CardTableBlockEntity section, TableGroupState groupState,
                            List<RenderedCard> cards, List<ZoneHit> hits, int mouseX, int mouseY)
    {
        BlockPos sectionPos = section != null ? section.getBlockPos() : null;
        if (zone.kind() == ZoneDefinition.Kind.STACK)
        {
            List<CardInstance> pile = stackOf(zone, section, groupState);
            int count = pile.size();
            if (this.drag != null && containsInstance(pile, this.drag.instanceId()))
            {
                count--; // pile top is on the mouse
            }
            Rect hit = pileRect(rect.x(), rect.y());
            hits.add(new ZoneHit(zone, sectionPos, rect, hit));
            this.renderPile(graphics, count, rect.x(), rect.y());
            this.drawZoneLabel(graphics, zone, rect);
            if (!pile.isEmpty())
            {
                // The whole stack is grabbable; hover resolves to its top card.
                cards.add(new RenderedCard(pile.get(pile.size() - 1), hit.x(), hit.y(), hit.width(), hit.height()));
            }
            return;
        }
        hits.add(new ZoneHit(zone, sectionPos, rect, rect));
        if (zone.kind() == ZoneDefinition.Kind.GRID)
        {
            this.renderGridSlots(graphics, zone, rect);
        }
        List<ZoneState.PlacedCard> placed = placedOf(zone, section, groupState);
        for (ZoneState.PlacedCard entry : placed)
        {
            if (this.isDragging(entry.card()))
            {
                continue; // held by the mouse; the zone already shows it gone
            }
            int[] position = placedCardPosition(rect, entry);
            boolean hovered = hitTest(mouseX, mouseY, position[0], position[1], CARD_WIDTH, CARD_HEIGHT);
            this.drawCard(graphics, entry.card(), entry.card().isFaceUp(),
                    position[0], position[1], CARD_WIDTH, CARD_HEIGHT, hovered);
            cards.add(new RenderedCard(entry.card(), position[0], position[1], CARD_WIDTH, CARD_HEIGHT));
        }
        this.drawZoneLabel(graphics, zone, rect);
    }

    /** GRID zones show their slot cells so empty slots stay discoverable. */
    private void renderGridSlots(GuiGraphics graphics, ZoneDefinition zone, Rect rect)
    {
        int slots = Math.max(1, zone.capacity());
        float slotWidth = (float) rect.width() / slots;
        for (int index = 0; index < slots; index++)
        {
            int slotLeft = rect.x() + (int) (index * slotWidth);
            int slotRight = rect.x() + (int) ((index + 1) * slotWidth);
            graphics.renderOutline(slotLeft + 1, rect.y() + 1,
                    Math.max(1, slotRight - slotLeft - 2), Math.max(1, rect.height() - 2), COLOR_PLAYFIELD_EDGE);
        }
    }

    /** Optional zone name hint, drawn only when the zone carries a label. */
    private void drawZoneLabel(GuiGraphics graphics, ZoneDefinition zone, Rect rect)
    {
        Component label = zone.label();
        if (label == null)
        {
            return;
        }
        graphics.drawString(this.font, label, rect.x() + 2, rect.y() - 10, COLOR_TEXT_DIM, true);
    }

    // Zone data accessors ----------------------------------------------------

    /** STACK cards of a zone: built-in piles read their dedicated fields. */
    private static List<CardInstance> stackOf(ZoneDefinition zone, @Nullable CardTableBlockEntity section,
                                              TableGroupState groupState)
    {
        ResourceLocation id = zone.id();
        if (TableLayoutDefinition.ZONE_DRAW_PILE.equals(id))
        {
            return groupState.getDrawPile();
        }
        if (TableLayoutDefinition.ZONE_DISCARD_PILE.equals(id))
        {
            return groupState.getDiscardPile();
        }
        ZoneState state = genericZoneState(id, section, groupState);
        return state == null ? List.of() : state.stackCards();
    }

    /** FREE/GRID placements of a zone: the built-in free zone reads the surface. */
    private static List<ZoneState.PlacedCard> placedOf(ZoneDefinition zone, @Nullable CardTableBlockEntity section,
                                                       TableGroupState groupState)
    {
        ResourceLocation id = zone.id();
        if (TableLayoutDefinition.ZONE_FREE.equals(id))
        {
            List<ZoneState.PlacedCard> out = new ArrayList<>();
            if (section != null)
            {
                for (SurfaceZone.SurfaceCard card : section.getSectionState().getSurface().cards())
                {
                    out.add(new ZoneState.PlacedCard(card.card(), card.x(), card.y()));
                }
            }
            return out;
        }
        ZoneState state = genericZoneState(id, section, groupState);
        return state == null ? List.of() : state.placedCards();
    }

    @Nullable
    private static ZoneState genericZoneState(ResourceLocation id, @Nullable CardTableBlockEntity section,
                                              TableGroupState groupState)
    {
        return section == null
                ? groupState.getSharedZones().get(id)
                : section.getSectionState().getSeatZones().get(id);
    }

    /** Normalized zone rect → pixel rect inside a base (playfield or cell) rect. */
    private static Rect scaledRect(int baseX, int baseY, int baseW, int baseH, ZoneDefinition zone)
    {
        int x = baseX + (int) (zone.x() * baseW);
        int y = baseY + (int) (zone.y() * baseH);
        int w = Math.max(1, (int) (zone.w() * baseW));
        int h = Math.max(1, (int) (zone.h() * baseH));
        return new Rect(x, y, w, h);
    }

    /** Zone-local normalized position → pixel top-left of the card inside the zone rect. */
    private static int[] placedCardPosition(Rect rect, ZoneState.PlacedCard entry)
    {
        int cardX = rect.x() + 2 + (int) (entry.x() * Math.max(0, rect.width() - CARD_WIDTH - 4));
        int cardY = rect.y() + 2 + (int) (entry.y() * Math.max(0, rect.height() - CARD_HEIGHT - 4));
        return new int[] {cardX, cardY};
    }

    @Nullable
    private TableGroupState clientGroupState(TableGroupService.GroupView group)
    {
        if (this.minecraft == null || this.minecraft.level == null)
        {
            return null;
        }
        return this.minecraft.level.getBlockEntity(group.masterPos()) instanceof CardTableBlockEntity master
                ? master.getGroupState() : null;
    }

    private static boolean containsInstance(List<CardInstance> cards, UUID instanceId)
    {
        return cards.stream().anyMatch(card -> card.instanceId().equals(instanceId));
    }

    private boolean isDragging(CardInstance card)
    {
        return this.drag != null && this.drag.instanceId().equals(card.instanceId());
    }

    private void renderPile(GuiGraphics graphics, int count, int x, int y)
    {
        int layers = Math.min(PILE_MAX_LAYERS, count);
        for (int layer = 0; layer < layers; layer++)
        {
            this.drawCard(graphics, null, false, x + layer * PILE_STACK_OFFSET,
                    y - layer * PILE_STACK_OFFSET, CARD_WIDTH, CARD_HEIGHT, false);
        }
        if (layers == 0)
        {
            // Empty pile outline so the slot is still discoverable. It must sit
            // where a filled pile sits (y is the top-left of the bottom card),
            // otherwise the slot and its hit rect drift a card height apart.
            graphics.renderOutline(x, y, CARD_WIDTH, CARD_HEIGHT, COLOR_PLAYFIELD_EDGE);
        }
        graphics.drawCenteredString(this.font, Component.literal(String.valueOf(count)),
                x + CARD_WIDTH / 2 + PILE_STACK_OFFSET * layers / 2, y + 4, COLOR_TEXT_DARK);
    }

    /**
     * Hit area of one pile, anchored the same way {@link #renderPile} draws it:
     * {@code (x, y)} is the top-left of the bottom card, and every further layer
     * steps up-right by {@link #PILE_STACK_OFFSET}, so the rect grows in those
     * two directions to keep the whole visible stack clickable.
     */
    private static Rect pileRect(int x, int y)
    {
        int spread = PILE_STACK_OFFSET * (PILE_MAX_LAYERS - 1);
        return new Rect(x, y - spread, CARD_WIDTH + spread, CARD_HEIGHT + spread);
    }

    // Own hand strip: only this client's cards (delivered by HandSyncPacket),
    // rendered above the player inventory with a hover highlight.
    private void renderHand(GuiGraphics graphics, int mouseX, int mouseY)
    {
        BlockPos ownSection = ownSeatPosition();
        if (ownSection == null)
        {
            this.handStripRect = null;
            return;
        }
        List<CardInstance> hand = ClientHandStore.hand(ownSection).stream()
                .filter(card -> !this.isDragging(card))
                .toList();
        if (hand.isEmpty())
        {
            this.handStripRect = null;
            return;
        }
        int overlap = HAND_CARD_WIDTH / 3;
        int stripWidth = HAND_CARD_WIDTH + (hand.size() - 1) * (HAND_CARD_WIDTH - overlap);
        int stripLeft = this.width / 2 - stripWidth / 2;
        int stripTop = this.inventoryMainTop - HAND_CARD_HEIGHT - 10;
        this.handStripRect = new Rect(stripLeft - 4, stripTop - 4, stripWidth + 8, HAND_CARD_HEIGHT + 8);

        graphics.fill(this.handStripRect.x(), this.handStripRect.y(),
                this.handStripRect.x() + this.handStripRect.width(),
                this.handStripRect.y() + this.handStripRect.height(), COLOR_PANEL);

        Component hoveredName = null;
        for (int index = 0; index < hand.size(); index++)
        {
            CardInstance card = hand.get(index);
            int cardX = stripLeft + index * (HAND_CARD_WIDTH - overlap);
            boolean hovered = mouseX >= cardX && mouseX < cardX + HAND_CARD_WIDTH
                    && mouseY >= stripTop && mouseY < stripTop + HAND_CARD_HEIGHT;
            this.drawCard(graphics, card, card.isFaceUp(), cardX, stripTop,
                    HAND_CARD_WIDTH, HAND_CARD_HEIGHT, hovered);
            if (hovered)
            {
                hoveredName = displayNameOf(card);
            }
        }
        if (hoveredName != null)
        {
            graphics.drawCenteredString(this.font, hoveredName, this.width / 2,
                    stripTop - 12, COLOR_TEXT_DARK);
        }
    }

    @Nullable
    private BlockPos ownSeatPosition()
    {
        UUID selfId = this.minecraft != null && this.minecraft.player != null
                ? this.minecraft.player.getUUID() : null;
        if (selfId == null)
        {
            return null;
        }
        for (Cell cell : this.cells)
        {
            if (selfId.equals(this.occupantAt(cell.position())))
            {
                return cell.position();
            }
        }
        return null;
    }

    // Card drawing ------------------------------------------------------------

    // One card: resolved textures with a graceful placeholder when the
    // definition or texture is missing; quarter-turn rotation via pose; hover ring.
    // {@code card == null} draws a face-down stack layer (pile rendering).
    private void drawCard(GuiGraphics graphics, @Nullable CardInstance card, boolean faceUp,
                          int x, int y, int width, int height, boolean hovered)
    {
        CardDefinition definition = card != null ? CardRegistry.get(card.definitionId()) : null;
        ResourceLocation texture = resolveTexture(definition, faceUp);
        int rotation = card != null ? card.rotation() : 0;

        if (texture == null)
        {
            this.drawPlaceholder(graphics, x, y, width, height);
            if (hovered)
            {
                graphics.renderOutline(x - 1, y - 1, width + 2, height + 2, COLOR_HOVER);
            }
            return;
        }

        CardTextureResolver.Binding binding = CardTextureResolver.resolve(texture).orElse(null);
        if (binding == null)
        {
            this.drawPlaceholder(graphics, x, y, width, height);
            if (hovered)
            {
                graphics.renderOutline(x - 1, y - 1, width + 2, height + 2, COLOR_HOVER);
            }
            return;
        }

        PoseStack pose = graphics.pose();
        pose.pushPose();
        if (rotation != 0)
        {
            pose.translate(x + width / 2.0D, y + height / 2.0D, 0);
            pose.mulPose(Axis.ZP.rotationDegrees(rotation));
            pose.translate(-width / 2.0D, -height / 2.0D, 0);
            graphics.blit(binding.location(), 0, 0, width, height,
                    0.0F, 0.0F, binding.width(), binding.height(), binding.width(), binding.height());
        }
        else
        {
            graphics.blit(binding.location(), x, y, width, height,
                    0.0F, 0.0F, binding.width(), binding.height(), binding.width(), binding.height());
        }
        pose.popPose();
        graphics.renderOutline(x, y, width, height, COLOR_CARD_BORDER);
        if (hovered)
        {
            graphics.renderOutline(x - 1, y - 1, width + 2, height + 2, COLOR_HOVER);
        }
    }

    // Face-down cards fall back: card override → set default → missing placeholder.
    @Nullable
    private static ResourceLocation resolveTexture(@Nullable CardDefinition definition, boolean faceUp)
    {
        if (definition == null)
        {
            return null;
        }
        if (faceUp)
        {
            return definition.frontTexture();
        }
        if (definition.backTexture() != null)
        {
            return definition.backTexture();
        }
        ResourceLocation setId = definition.cardSet();
        CardSetDefinition set = setId != null ? CardRegistry.getSet(setId) : null;
        return set != null ? set.defaultBackTexture() : null;
    }

    private void drawPlaceholder(GuiGraphics graphics, int x, int y, int width, int height)
    {
        graphics.fill(x, y, x + width, y + height, COLOR_CARD_MISSING);
        graphics.renderOutline(x, y, width, height, COLOR_CARD_BORDER);
        graphics.drawCenteredString(this.font, "?", x + width / 2, y + height / 2 - 4, COLOR_TEXT_DIM);
    }

    @Nullable
    private static Component displayNameOf(CardInstance card)
    {
        CardDefinition definition = CardRegistry.get(card.definitionId());
        return definition != null ? definition.displayName() : null;
    }

    // Frame orchestration ------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
    {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;

        // No fullscreen menu backdrop on purpose: the surrounding world stays
        // visible around the board, just like a container menu does.
        TableGroupService.GroupView group = this.clientGroup();
        this.seats = group != null ? this.computeSeats(group) : List.of();
        this.cells = group != null ? this.computeCells(group) : List.of();

        super.render(graphics, mouseX, mouseY, partialTick);

        this.renderSeats(graphics, mouseX, mouseY);
        this.renderHand(graphics, mouseX, mouseY);
        this.renderStatus(graphics);
        this.renderDebug(graphics, group);

        // The held card follows the mouse on top of everything; the server's
        // authoritative reply will discard this preview on the next sync.
        if (this.drag != null)
        {
            this.drawCard(graphics, this.drag.card(), true,
                    mouseX - CARD_WIDTH / 2, mouseY - CARD_HEIGHT / 2, CARD_WIDTH, CARD_HEIGHT, true);
        }
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
        if (message.getString().isEmpty() && this.layoutMissing)
        {
            // Bound layout vanished from the local registry: rendering degraded
            // to the default. Only cosmetic — bad drops get rejected server-side.
            message = Component.translatable("gui.cardtable.layout_missing");
        }
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
        List<Component> lines = new ArrayList<>(List.of(
                Component.translatable("gui.cardtable.members",
                        this.menu.getSeatedCount(), this.menu.getSeatCount()),
                Component.translatable("gui.cardtable.version",
                        masterEntity.getGroupState().getVersion()),
                Component.translatable("gui.cardtable.table_id",
                        masterEntity.getGroupState().getTableId().toString().substring(0, 8)),
                Component.translatable("gui.cardtable.draw_count",
                        masterEntity.getGroupState().getDrawPile().size()),
                Component.translatable("gui.cardtable.discard_count",
                        masterEntity.getGroupState().getDiscardPile().size())));
        TableGroupState debugState = masterEntity.getGroupState();
        lines.add(Component.translatable("gui.cardtable.active_set",
                debugState.getActiveSetId() != null ? debugState.getActiveSetId().toString() : "-"));
        lines.add(Component.translatable("gui.cardtable.active_layout",
                debugState.getActiveLayoutId() != null ? debugState.getActiveLayoutId().toString() : "-"));
        int y = this.height - 12;
        for (Component line : lines)
        {
            graphics.drawString(this.font, line, this.width - 4 - this.font.width(line), y, COLOR_TEXT_DIM, true);
            y -= 12;
        }
    }

    // Interaction ------------------------------------------------------------

    /**
     * The card under the mouse, if any. Uses the frame's rendered card list
     * (built by {@link #renderZones}), scanning topmost-first so overlapping
     * cards pick what the player sees on top; then the own hand strip.
     */
    @Nullable
    private CardInstance hoveredCard(double mouseX, double mouseY)
    {
        for (int index = this.renderedCards.size() - 1; index >= 0; index--)
        {
            RenderedCard rendered = this.renderedCards.get(index);
            if (this.isDragging(rendered.card()))
            {
                continue;
            }
            if (hitTest(mouseX, mouseY, rendered.x(), rendered.y(), rendered.width(), rendered.height()))
            {
                return rendered.card();
            }
        }

        if (this.handStripRect != null && this.handStripRect.contains(mouseX, mouseY))
        {
            BlockPos ownSection = ownSeatPosition();
            List<CardInstance> hand = ownSection != null
                    ? ClientHandStore.hand(ownSection).stream().filter(card -> !this.isDragging(card)).toList()
                    : List.of();
            int overlap = HAND_CARD_WIDTH / 3;
            for (int index = hand.size() - 1; index >= 0; index--)
            {
                int cardX = this.handStripRect.x() + 4 + index * (HAND_CARD_WIDTH - overlap);
                if (hitTest(mouseX, mouseY, cardX, this.handStripRect.y() + 4, HAND_CARD_WIDTH, HAND_CARD_HEIGHT))
                {
                    return hand.get(index);
                }
            }
        }
        return null;
    }

    private static boolean hitTest(double mouseX, double mouseY, int x, int y, int width, int height)
    {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    // Resolves the drop target under the mouse and asks the server to move
    // the dragged card; invalid drops simply cancel the preview.
    private void finishDrag(double mouseX, double mouseY)
    {
        if (this.drag == null)
        {
            return;
        }
        UUID instanceId = this.drag.instanceId();

        // The hand strip keeps the highest priority (unchanged behavior).
        if (this.handStripRect != null && this.handStripRect.contains(mouseX, mouseY))
        {
            BlockPos ownSection = ownSeatPosition();
            if (ownSection != null)
            {
                this.sendAction(new CardActionPacket.Action.Move(instanceId,
                        new ZoneRef(TableLayoutDefinition.ZONE_HAND, ownSection), null));
            }
            return;
        }
        // Layout zones in reverse render order: the visually topmost zone wins.
        for (int index = this.zoneHits.size() - 1; index >= 0; index--)
        {
            ZoneHit hit = this.zoneHits.get(index);
            if (!hit.hitRect().contains(mouseX, mouseY))
            {
                continue;
            }
            ZoneDefinition zone = hit.zone();
            if (zone.kind() == ZoneDefinition.Kind.STACK)
            {
                this.sendAction(new CardActionPacket.Action.Move(instanceId,
                        new ZoneRef(zone.id(), hit.sectionPos()), null));
            }
            else
            {
                float x = normalizeDrop(mouseX, hit.rect().x(), hit.rect().width());
                float y = normalizeDrop(mouseY, hit.rect().y(), hit.rect().height());
                if (zone.kind() == ZoneDefinition.Kind.GRID)
                {
                    // Client-side quantization for instant feedback; the
                    // server re-quantizes idempotently (same slot wins).
                    float[] snapped = ZoneDefinition.quantizeGrid(x, y, zone.capacity());
                    x = snapped[0];
                    y = snapped[1];
                }
                this.sendAction(new CardActionPacket.Action.Move(instanceId,
                        new ZoneRef(zone.id(), hit.sectionPos()), new Vec2(x, y)));
            }
            return;
        }
        // No valid target: drop cancels, the authoritative state is unchanged.
    }

    private static float normalizeDrop(double value, int zoneStart, int zoneSize)
    {
        float usable = Math.max(1, zoneSize - CARD_WIDTH - 4);
        float normalized = (float) (value - zoneStart - 2) / usable;
        return Math.max(0.0F, Math.min(1.0F, normalized));
    }

    private void sendAction(CardActionPacket.Action action)
    {
        NetworkHandler.CHANNEL.sendToServer(new CardActionPacket(this.menu.getTablePosition(), action));
    }

    // Keyboard actions: F flip, R rotate, D draw, S shuffle.
    // All validated server-side.
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        if (keyCode == GLFW.GLFW_KEY_F3)
        {
            this.showDebugInfo = !this.showDebugInfo;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F && this.drag == null)
        {
            CardInstance hovered = hoveredCard(this.lastMouseX, this.lastMouseY);
            if (hovered != null)
            {
                this.sendAction(new CardActionPacket.Action.Flip(hovered.instanceId()));
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_R && this.drag == null)
        {
            CardInstance hovered = hoveredCard(this.lastMouseX, this.lastMouseY);
            if (hovered != null)
            {
                this.sendAction(new CardActionPacket.Action.Rotate(hovered.instanceId()));
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_D)
        {
            this.sendAction(new CardActionPacket.Action.Draw(1));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_S)
        {
            this.sendAction(new CardActionPacket.Action.Shuffle(new ZoneRef(TableLayoutDefinition.ZONE_DRAW_PILE, null)));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        if (button == 0)
        {
            // The backpack toggle intercepts the click (and must not also trigger
            // the deck slot sitting right above it). Relayout moves the inventory
            // slots back on-screen (or off-screen) to match the new state.
            if (this.inventoryToggleRect != null && this.inventoryToggleRect.contains(mouseX, mouseY))
            {
                this.showInventory = !this.showInventory;
                this.layoutSlots();
                return true;
            }
            CardInstance hovered = hoveredCard(mouseX, mouseY);
            if (hovered != null)
            {
                this.drag = new Drag(hovered);
                return true;
            }
            if (!this.seats.isEmpty())
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
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button)
    {
        if (button == 0 && this.drag != null)
        {
            this.finishDrag(mouseX, mouseY);
            this.drag = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // While a card is held the mouse drag belongs to it, not to vanilla's
    // slot quick-craft logic.
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY)
    {
        if (this.drag != null)
        {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /** One seat around the table edge; {@code occupantId == null} marks an empty seat. */

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

    /** One playfield cell mapped from a table block. */
    private record Cell(BlockPos position, int x, int y, int width, int height) {}

    /** The card currently held by the mouse (client-side preview only). */
    private record Drag(CardInstance card)
    {
        UUID instanceId()
        {
            return this.card.instanceId();
        }
    }

    /** One card as drawn this frame, with its pixel rect (drives hover hit-test). */
    private record RenderedCard(CardInstance card, int x, int y, int width, int height)
    {
    }

    /** One rendered zone instance: its definition, seat (null for SHARED),
     *  the pixel rect drops normalize against, and the (possibly pile-spread)
     *  rect that captures hover/drop. */
    private record ZoneHit(ZoneDefinition zone, @Nullable BlockPos sectionPos, Rect rect, Rect hitRect)
    {
    }

    /** Simple screen-space rectangle for hover/drop tests. */
    private record Rect(int x, int y, int width, int height)
    {
        boolean contains(double mouseX, double mouseY)
        {
            return mouseX >= this.x && mouseX < this.x + this.width
                    && mouseY >= this.y && mouseY < this.y + this.height;
        }
    }
}
