package com.example.cardtable.client.screen;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.api.CardDefinition;
import com.example.cardtable.api.CardRegistry;
import com.example.cardtable.api.CardSetDefinition;
import com.example.cardtable.api.TableActionDefinition;
import com.example.cardtable.api.TableLayoutDefinition;
import com.example.cardtable.api.ZoneDefinition;
import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.card.CardInstance;
import com.example.cardtable.card.ZoneState;
import com.example.cardtable.client.ClientHandStore;
import com.example.cardtable.client.ModKeyBindings;
import com.example.cardtable.client.card.CardTextureResolver;
import com.example.cardtable.menu.CardTableMenu;
import com.example.cardtable.network.NetworkHandler;
import com.example.cardtable.network.packet.CardActionPacket;
import com.example.cardtable.network.packet.CardTableMembershipPacket;
import com.example.cardtable.card.ZoneRef;
import com.example.cardtable.table.TableGraph;
import com.example.cardtable.table.TableGroupService;
import com.example.cardtable.table.TableGroupState;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
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
 * <p>The table itself is one blank, shared surface: a single group-level
 * free placement area covering the whole playfield, with no per-seat cells
 * and no fixed sub-regions of its own. "Blank" means it draws no chrome —
 * no frame, no slot grid — not that it is empty: it is a coordinate space
 * that carries whatever is laid on it (cards played straight onto the bare
 * table), drawn as the table's bottom layer. Layout-declared zones (piles,
 * grids) render on top of it, and the surface is the catch-all drop target.
 * Cards are stored in group-level normalized coordinates, so every
 * client agrees on their relative positions; {@link TableView} rotates the
 * whole content by 0°/90°/180°/270° so each player sees "their own side" at
 * the bottom, and drops are mapped back through the inverse transform. The
 * seat ring turns with that rotation too, so the viewer's own seat is always
 * the bottom-most one, and it is the only seat pulled out of the ring: it is
 * pinned to the bottom-left, just outside the table's left edge, so the
 * board itself stays clear. The bottom band of the screen, below the
 * playfield, holds exactly two things the viewer needs in front of them:
 * that own seat plate on the left and the hand, fanned in the bottom centre.
 * The deck slot and backpack toggle stay in the top-right corner; none of
 * this fixed UI rotates. Table and pile data come from the synced block
 * entities, the hand only from {@link ClientHandStore}.</p>
 */
public class CardTableScreen extends AbstractContainerScreen<CardTableMenu>
{
    /** The same texture used by the table block, so the canvas matches the board style. */
    private static final ResourceLocation TABLE_TEXTURE =
            new ResourceLocation(CardTableMod.MODID, "textures/block/quartz_block_top.png");

    /** Core card back: the last resort for any face-down card whose pack declares none. */
    private static final ResourceLocation DEFAULT_BACK =
            new ResourceLocation(CardTableMod.MODID, "card/default_back");

    // Vanilla inventory atlas; the backpack rows (3 main rows + hotbar) are the
    // bottom band of the 176x166 container panel, so one blit of the region
    // (0, 83, 176, 83) reproduces the exact vanilla look without the player
    // model, armour slots or crafting grid.
    private static final ResourceLocation VANILLA_INVENTORY_ATLAS =
            new ResourceLocation("textures/gui/container/inventory.png");
    /** Atlas rect of the backpack band: from the row separator down to the panel bottom border. */
    private static final int INVENTORY_BAND_U = 0;
    private static final int INVENTORY_BAND_V = 83;
    private static final int INVENTORY_BAND_WIDTH = 176;
    private static final int INVENTORY_BAND_HEIGHT = 83;
    /** First main-inventory slot offset inside the band (vanilla grid origin). */
    private static final int INVENTORY_MAIN_SLOT_OFFSET = 8;
    /**
     * Atlas rows above the band that still belong to the panel frame: the
     * vanilla top border (rounded corners included) plus a few px of plain
     * background. V=0..6 is border + empty space; the armour slot recesses
     * only start at V=8, so nothing unrelated leaks into the cap.
     */
    private static final int INVENTORY_CAP_HEIGHT = 7;
    /** Safety gap kept above the panel's top border when the panel is clamped to the screen edge. */
    private static final int PANEL_TOP_MARGIN = 2;
    /**
     * Shift applied to the backpack's slot grid to park it off-screen: far
     * enough out that the vanilla slot pass neither draws nor hit-tests it.
     * Used while the panel is hidden and, for the length of that pass, while it
     * is on screen as well (see {@link #positionInventorySlots}).
     */
    private static final int INVENTORY_HIDDEN_OFFSET = -10000;

    private static final int SEAT_SIZE = 26;
    /** Seat ring inset from the screen edges. */
    private static final int SEAT_INSET = 40;
    /**
     * Height of the band the playfield leaves free along the bottom of the
     * screen, holding the own seat plate and the hand strip. Both are UI the
     * player reads constantly, so they belong in front of them — but off the
     * board, which is why the playfield stops above this band instead of
     * running to the screen edge.
     */
    private static final int BOTTOM_BAND_HEIGHT = 58;
    /** Gap kept between the hand strip and the bottom screen edge. */
    private static final int HAND_BOTTOM_MARGIN = 8;
    /** Gap between the own seat plate's bottom edge and the screen edge (room for the name line). */
    private static final int OWN_SEAT_BOTTOM_MARGIN = 14;
    /** Gap between the own seat plate and the table's left edge. */
    private static final int OWN_SEAT_EDGE_GAP = 6;
    /** Most a seat name may occupy on screen before it is trimmed with an ellipsis. */
    private static final int SEAT_NAME_MAX_WIDTH = 96;
    /** Horizontal padding between label text and the edge of its backing plate. */
    private static final int LABEL_PAD_X = 2;
    /** Vertical padding between label text and its backing plate. */
    private static final int LABEL_PAD_Y = 1;
    /** Side gaps around the board so the surrounding world stays visible on the left/right, like a container menu. */
    private static final int SIDE_MARGIN = 18;
    /** Playfield inset from the screen edges, clamped down for small windows. */
    private static final int PLAYFIELD_INSET_X = 140;
    private static final int PLAYFIELD_INSET_Y = 96;

    // Card rendering: surface cards are bigger than hand cards; both keep the
    // same 34:48 (roughly poker) aspect ratio.
    private static final int CARD_WIDTH = 34;
    private static final int CARD_HEIGHT = 48;
    private static final int HAND_CARD_WIDTH = HandStripLayout.CARD_WIDTH;
    private static final int HAND_CARD_HEIGHT = HandStripLayout.CARD_HEIGHT;
    private static final int SLOT_SIZE = 18;
    private static final int CELL_GAP = 4;
    private static final int PILE_STACK_OFFSET = 2;
    private static final int PILE_MAX_LAYERS = 5;

    // Palette derived from the table texture: warm wood, cream accents.
    private static final int COLOR_WOOD_DARK = 0xFF5A3D26;
    private static final int COLOR_PLAYFIELD = 0x66EDE7D9;
    private static final int COLOR_PLAYFIELD_EDGE = 0xFF6B4A2F;
    private static final int COLOR_SEAT = 0xFF6B4A2F;
    private static final int COLOR_SEAT_EMPTY = 0x904A3624;
    private static final int COLOR_SEAT_SELF = 0xFFD4B483;
    private static final int COLOR_HOVER = 0xFFF5F0E6;
    private static final int COLOR_TEXT_DIM = 0xFFC9BFA8;
    /**
     * Status and error text. Bright on purpose: it is drawn on
     * {@link #COLOR_LABEL_BG}, and the old dark red was only legible against the
     * light wood it used to sit on directly — on a dark plate it collapsed into
     * the background.
     */
    private static final int COLOR_ERROR = 0xFFFF8A73;
    private static final int COLOR_CARD_BORDER = 0xFF2E2620;
    private static final int COLOR_CARD_MISSING = 0xFF555555;
    private static final int COLOR_PANEL = 0x90241C14;
    private static final int COLOR_PANEL_EDGE = 0xFF6B4A2F;
    /**
     * Backing plate for every piece of screen text. Text on this screen sits
     * over the wood texture, the translucent playfield, card backs, seat plates
     * and bare world, so it is never drawn loose: a dark plate under each label
     * makes the text readable regardless of what it lands on. Warm-toned to sit
     * with the board, and translucent enough that content underneath still
     * reads through it.
     */
    private static final int COLOR_LABEL_BG = 0xC0241C14;

    /** Height of the soft fade band blending board content into the screen edge. */
    private static final int EDGE_FADE_HEIGHT = 36;
    /** Semi-transparent dark-wood tint used by the edge fade (matches the rim). */
    private static final int COLOR_EDGE_FADE = 0xB23A2A1A;

    private final Map<UUID, Player> resolvedPlayers = new HashMap<>();
    private long cachedVersion = -1L;

    private boolean showDebugInfo = true;
    private Component status = Component.empty();
    private List<SeatSlot> seats = List.of();
    private List<Cell> cells = List.of();
    // Per-frame view transform: rotates the shared table content by this
    // player's seating direction (0 for spectators). Null until render().
    @Nullable
    private TableView view;
    private int lastMouseX;
    private int lastMouseY;

    /** Card currently held by the mouse; only a client preview, the server owns the real move. */
    @Nullable
    private Drag drag;
    @Nullable
    private Rect handStripRect;
    /**
     * Horizontal pan of the own-hand strip when the fan is longer than the
     * window. Purely a client view offset; the server never sees it. Reset to
     * 0 whenever the hand fits without scrolling.
     */
    private int handScrollOffset;
    /** True while the middle mouse button is panning the hand strip. */
    private boolean handPanning;
    /** Mouse x at the moment the current pan started, for drag-to-scroll. */
    private double handPanLastMouseX;

    // Layout-driven render state, rebuilt every frame by renderZones():
    // drop/hover targets in render order (last entry = topmost) and the cards
    // currently visible on the playfield with their pixel rects.
    private List<ZoneHit> zoneHits = List.of();
    private List<RenderedCard> renderedCards = List.of();
    // True while the bound layout vanished from the local registry: the view
    // degrades to the default layout and the status line warns about it.
    private boolean layoutMissing;

    // Inventory layout, written by layoutSlots() and reused by the hand strip
    // and the vanilla-style backpack panel.
    private int inventoryPanelLeft;
    private int inventoryPanelTop;
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
    // mutable through the project's access transformer. The inventory grid is
    // anchored to a vanilla backpack panel (see renderInventoryPanel), so the
    // panel origin and the 18px slot grid share one source of truth.
    private void layoutSlots()
    {
        this.menu.getDeckSlot().x = this.width - SLOT_SIZE - 12;
        this.menu.getDeckSlot().y = 12;

        // Bottom-centre the vanilla backpack band; the slot grid then aligns to
        // the atlas offsets (main row at +84, hotbar at +142, band starts at +83).
        // The panel stack needs 96px (cap 7 + band 83 + bottom margin 6); on
        // windows shorter than that the fixed bottom anchor pushed the cap past
        // the top edge and sliced the rounded border off, so the clamp floats the
        // whole panel (slots included, same field) over the board instead.
        // While the hand strip is on screen the panel is lifted by the strip's
        // height: the hand owns the bottom band, and the two must not overlap.
        int handRise = ownHandCards().isEmpty() ? 0 : HAND_CARD_HEIGHT + HAND_BOTTOM_MARGIN;
        this.inventoryPanelLeft = this.width / 2 - INVENTORY_BAND_WIDTH / 2;
        this.inventoryPanelTop = Math.max(INVENTORY_CAP_HEIGHT + PANEL_TOP_MARGIN,
                this.height - INVENTORY_BAND_HEIGHT - 6 - handRise);
        this.inventoryLeft = this.inventoryPanelLeft + INVENTORY_MAIN_SLOT_OFFSET;
        this.inventoryMainTop = this.inventoryPanelTop + (84 - INVENTORY_BAND_V);
        this.inventoryHotbarTop = this.inventoryPanelTop + (142 - INVENTORY_BAND_V);

        // When the inventory is hidden (default) its slots are pushed far
        // off-screen: Slot.x/y are mutable via the project's access transformer,
        // so neither the item-bar renders nor does it intercept clicks. The
        // layout above still feeds the hand strip's on-screen position.
        this.positionInventorySlots(this.showInventory ? 0 : INVENTORY_HIDDEN_OFFSET);
    }

    /**
     * Places the 27 main + 9 hotbar slots on the panel grid, shifted by
     * {@code offset}. The backpack is the table's topmost layer and is painted
     * by {@link #renderInventoryOverlay} rather than by the vanilla slot pass,
     * so {@link #render} parks this grid at {@link #INVENTORY_HIDDEN_OFFSET}
     * while that pass runs (it draws every slot before the board content) and
     * restores it right after. Slot.x/y are read again at event time
     * ({@code findSlot}), so hit-testing and clicking are unaffected.
     */
    private void positionInventorySlots(int offset)
    {
        List<Slot> inventorySlots = this.menu.getInventorySlots();
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

    // Single toggle entry shared by the backpack button and the configurable
    // key. Flipping the flag then relaying keeps the panel, the slot grid and
    // the hand strip in sync; purely a client view state, never synced.
    private void toggleInventory()
    {
        this.showInventory = !this.showInventory;
        this.layoutSlots();
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
    // order; {@code occupantId == null} marks an empty seat. The ring is
    // turned by the own seat's index so the local player always lands on the
    // ring's bottom slot — the content rotation already shows them their own
    // side of the table, and the seats have to agree with it. The own plate
    // then leaves the ring for a fixed corner anchor (see ownSeatX/ownSeatY).
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
        int ownIndex = ownSeatIndex(positions);
        int bottomSlot = TableView.seatRingBottomSlot(seatCount);
        List<SeatSlot> slots = new ArrayList<>(seatCount);
        for (int index = 0; index < seatCount; index++)
        {
            // First seat at the top, then evenly around the table edge; the
            // ring is shifted by the own seat's index so the own seat takes
            // the bottom slot (spectators keep the unshifted ring).
            int slot = TableView.seatRingSlot(index, ownIndex, seatCount, bottomSlot);
            double angle = -Math.PI / 2.0D + (Math.PI * 2.0D * slot) / seatCount;
            int seatX = centerX + (int) Math.round(Math.cos(angle) * radiusX);
            int seatY = centerY + (int) Math.round(Math.sin(angle) * radiusY);
            if (index == ownIndex)
            {
                seatX = ownSeatX();
                seatY = ownSeatY();
            }
            UUID occupantId = this.occupantAt(positions.get(index));
            slots.add(new SeatSlot(index, seatX, seatY, occupantId));
        }
        return slots;
    }

    /** Index of the local player's own block in the sorted group order, or -1 while unseated. */
    private int ownSeatIndex(List<BlockPos> positions)
    {
        BlockPos ownSeat = ownSeatPosition();
        return ownSeat == null ? -1 : positions.indexOf(ownSeat);
    }

    /**
     * Own seat plate anchor: bottom-left of the reserved band, just outside the
     * table's left edge and biased toward the centre rather than jammed into
     * the window corner. The plate deliberately sits off the playfield — the
     * one seat the player always has on screen must not cover the board —
     * which also leaves the bottom centre to the hand strip.
     */
    private int ownSeatX()
    {
        return Math.max(SEAT_SIZE / 2 + 2, playfieldLeft() - SEAT_SIZE / 2 - OWN_SEAT_EDGE_GAP);
    }

    /** Own seat plate anchor: bottom-aligned in the reserved band, clearing the name line below it. */
    private int ownSeatY()
    {
        return this.height - OWN_SEAT_BOTTOM_MARGIN - SEAT_SIZE / 2;
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
        int playLeft = playfieldLeft();
        int playTop = playfieldTop();
        int playWidth = playfieldWidth();
        int playHeight = playfieldHeight();

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

    /**
     * Bottom edge of the playfield: the symmetric vertical inset, stretched by
     * the reserved bottom band ({@link #BOTTOM_BAND_HEIGHT}) when that band is
     * the larger of the two. On tall windows the plain inset already leaves
     * more room than the band needs; on short ones the band wins, and the
     * board gives up the space rather than let the hand strip sit on it.
     */
    private int playfieldBottom()
    {
        int insetY = Math.min(PLAYFIELD_INSET_Y, this.height / 5);
        int band = Math.min(BOTTOM_BAND_HEIGHT, this.height / 4);
        return this.height - Math.max(insetY, band);
    }

    private int playfieldHeight()
    {
        return Math.max(1, playfieldBottom() - playfieldTop());
    }

    /**
     * Builds this frame's view transform. The rotation is derived from the
     * own seat cell's position inside the playfield so the seat lands in the
     * screen's bottom half ("in front of" the player); spectators and
     * unseated players watch the table un-rotated.
     */
    private TableView computeView()
    {
        int playLeft = playfieldLeft();
        int playTop = playfieldTop();
        int playWidth = Math.max(1, playfieldWidth());
        int playHeight = Math.max(1, playfieldHeight());
        int quarters = 0;
        BlockPos ownSeat = ownSeatPosition();
        if (ownSeat != null)
        {
            for (Cell cell : this.cells)
            {
                if (!cell.position().equals(ownSeat))
                {
                    continue;
                }
                double ownX = (cell.x() + cell.width() / 2.0D - playLeft) / playWidth;
                double ownY = (cell.y() + cell.height() / 2.0D - playTop) / playHeight;
                quarters = TableView.quartersFor(ownX, ownY);
                break;
            }
        }
        return new TableView(playLeft, playTop, playWidth, playHeight, quarters);
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

        // Reserved play area in the centre with one cell per table block. It
        // ends above the bottom band so the hand strip and the own seat plate
        // never print over the board.
        int playLeft = playfieldLeft();
        int playTop = playfieldTop();
        int playWidth = playfieldWidth();
        int playHeight = playfieldHeight();
        graphics.fill(playLeft, playTop, playLeft + playWidth, playTop + playHeight, COLOR_PLAYFIELD);
        graphics.renderOutline(playLeft, playTop, playWidth, playHeight, COLOR_PLAYFIELD_EDGE);

        // The backpack is deliberately not painted here: it belongs to the
        // frame's topmost layer (renderInventoryOverlay), above every card.
        this.renderDeckSlotBackground(graphics);
        this.renderZones(graphics, mouseX, mouseY);
        this.renderTopFade(graphics);
    }

    /**
     * Soft fade band across the top of the playfield. The topmost card rows
     * run into the window edge on short screens and the hard cut looks abrupt;
     * melting them into a dark wood tint reads as a deliberate vignette. The
     * band stops at the playfield's side edges so the deck slot and backpack
     * toggle (outside it, top-right) stay crisp. Drawn as the last playfield
     * pass so every card participates, while seats and the hand strip
     * (rendered after {@code renderBg}) are untouched.
     */
    private void renderTopFade(GuiGraphics graphics)
    {
        int fadeHeight = Math.min(EDGE_FADE_HEIGHT, this.height / 5);
        if (fadeHeight <= 1)
        {
            return;
        }
        int playLeft = playfieldLeft();
        graphics.fillGradient(playLeft, this.topPos, playLeft + playfieldWidth(), this.topPos + fadeHeight,
                COLOR_EDGE_FADE, 0x00000000);
    }

    private void renderDeckSlotBackground(GuiGraphics graphics)
    {
        Slot deckSlot = this.menu.getDeckSlot();
        int left = deckSlot.x - 1;
        int top = deckSlot.y - 1;
        graphics.fill(left, top, left + SLOT_SIZE, top + SLOT_SIZE, COLOR_PANEL);
        graphics.renderOutline(left, top, SLOT_SIZE, SLOT_SIZE, COLOR_PANEL_EDGE);
        Component label = Component.translatable("gui.cardtable.deck_slot");
        this.drawLabel(graphics, label, left + SLOT_SIZE + 4, top + 5, COLOR_TEXT_DIM);

        // Backpack toggle button directly under the deck slot. Drawn every frame
        // so its hit rect stays in sync with the (resize-aware) deck slot position.
        // The label shows the current (rebindable) key so players see the shortcut.
        Component toggleLabel = Component.translatable(this.showInventory
                ? "gui.cardtable.inv_hide" : "gui.cardtable.inv_show")
                .append(" [")
                .append(ModKeyBindings.TOGGLE_INVENTORY.getTranslatedKeyMessage())
                .append("]");
        int toggleW = this.font.width(toggleLabel) + 10;
        int toggleH = 14;
        int toggleX = deckSlot.x;
        int toggleY = deckSlot.y + SLOT_SIZE + 4;
        this.inventoryToggleRect = new Rect(toggleX, toggleY, toggleW, toggleH);
        graphics.fill(toggleX, toggleY, toggleX + toggleW, toggleY + toggleH, COLOR_PANEL);
        graphics.renderOutline(toggleX, toggleY, toggleW, toggleH,
                this.showInventory ? COLOR_SEAT_SELF : COLOR_PANEL_EDGE);
        graphics.drawString(this.font, toggleLabel, toggleX + (toggleW - this.font.width(toggleLabel)) / 2,
                toggleY + (toggleH - this.font.lineHeight) / 2, COLOR_HOVER, false);
    }

    // Vanilla-style backpack panel: one blit of the inventory atlas's bottom
    // band reproduces the exact vanilla border and slot recesses, minus the
    // player model / armour / crafting grid (which live above row V=83). The
    // band alone is a hard horizontal cut at its top edge, so a second blit of
    // the atlas's top border rows (V=0..6) is stacked directly above it to
    // supply the rounded-corner frame and shadow the band lacks. Only drawn
    // while the toggle is on, so it disappears together with the slots.
    private void renderInventoryPanel(GuiGraphics graphics)
    {
        if (!this.showInventory)
        {
            return;
        }
        // Top border cap: rounded corners + frame + drop shadow, sitting flush
        // above the band so the two read as one complete vanilla container.
        graphics.blit(VANILLA_INVENTORY_ATLAS,
                this.inventoryPanelLeft, this.inventoryPanelTop - INVENTORY_CAP_HEIGHT,
                INVENTORY_BAND_WIDTH, INVENTORY_CAP_HEIGHT,
                (float) INVENTORY_BAND_U, 0.0F,
                INVENTORY_BAND_WIDTH, INVENTORY_CAP_HEIGHT, 256, 256);
        graphics.blit(VANILLA_INVENTORY_ATLAS,
                this.inventoryPanelLeft, this.inventoryPanelTop,
                INVENTORY_BAND_WIDTH, INVENTORY_BAND_HEIGHT,
                (float) INVENTORY_BAND_U, (float) INVENTORY_BAND_V,
                INVENTORY_BAND_WIDTH, INVENTORY_BAND_HEIGHT, 256, 256);
    }

    /**
     * Topmost board layer: the vanilla backpack panel, its item grid, the hover
     * ring and the cursor's payload. Painted after every other pass of the frame
     * so a card, a pile, a seat plate, the hand strip or the status/debug
     * readouts can never cover the player's inventory. The grid is drawn here
     * rather than by the vanilla slot pass, which runs before the board content
     * (see {@link #positionInventorySlots}); that same reason makes this method
     * reproduce the vanilla hover ring by hand.
     */
    private void renderInventoryOverlay(GuiGraphics graphics, int mouseX, int mouseY)
    {
        if (!this.showInventory)
        {
            return;
        }

        // Same depth-test state the vanilla slot pass runs under (the parent
        // re-enables depth testing when it returns): nothing painted here can be
        // sorted behind board content, whatever depth the earlier passes left.
        RenderSystem.disableDepthTest();
        this.renderInventoryPanel(graphics);

        // Contents sit SLOT_ITEM_BLIT_OFFSET in front of the panel and the ring
        // goes over the item, exactly as the vanilla pass draws them.
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0F, 0.0F, AbstractContainerScreen.SLOT_ITEM_BLIT_OFFSET);
        for (Slot slot : this.menu.getInventorySlots())
        {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty())
            {
                graphics.renderItem(stack, slot.x, slot.y);
                graphics.renderItemDecorations(this.font, stack, slot.x, slot.y);
            }
        }
        pose.popPose();

        Slot hovered = this.hoveredInventorySlot(mouseX, mouseY);
        if (hovered != null)
        {
            AbstractContainerScreen.renderSlotHighlight(graphics, hovered.x, hovered.y, 0);
        }

        // The item on the cursor keeps vanilla's top spot: it is the payload
        // being moved, not board content, and the vanilla pass that draws it
        // (inside super.render) now sits underneath this panel. Painted again
        // only where the panel actually hides it, so it is never composited
        // twice.
        ItemStack carried = this.menu.getCarried();
        if (!carried.isEmpty() && this.inventoryPanelOverlaps(mouseX - 8, mouseY - 8, 16, 16))
        {
            int carriedX = mouseX - 8;
            int carriedY = mouseY - 8;
            pose.pushPose();
            pose.translate(0.0F, 0.0F, 232.0F);
            graphics.renderItem(carried, carriedX, carriedY);
            graphics.renderItemDecorations(this.font, carried, carriedX, carriedY);
            pose.popPose();
        }
        RenderSystem.enableDepthTest();
    }

    /**
     * Vanilla's own slot pick, over every slot of the menu (deck slot included)
     * rather than the backpack grid alone. The base class resolves its
     * {@code hoveredSlot} field inside the slot pass, which {@link #render}
     * runs against the parked grid, so this reproduces the pick afterwards to
     * keep the tooltips and the keyboard slot actions working.
     */
    @Nullable
    private Slot slotUnderCursor(int mouseX, int mouseY)
    {
        Slot found = null;
        for (Slot slot : this.menu.slots)
        {
            if (slot.isActive() && this.isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY))
            {
                found = slot; // vanilla keeps the last match, so do we
            }
        }
        return found;
    }

    /** Backpack slot under the cursor, if any (vanilla's 16px pick radius). */
    @Nullable
    private Slot hoveredInventorySlot(int mouseX, int mouseY)
    {
        for (Slot slot : this.menu.getInventorySlots())
        {
            if (slot.isActive() && this.isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY))
            {
                return slot;
            }
        }
        return null;
    }

    /** True when the backpack panel (top border cap included) overlaps the given screen rect. */
    private boolean inventoryPanelOverlaps(int x, int y, int width, int height)
    {
        int panelLeft = this.inventoryPanelLeft;
        int panelTop = this.inventoryPanelTop - INVENTORY_CAP_HEIGHT;
        return x < panelLeft + INVENTORY_BAND_WIDTH && x + width > panelLeft
                && y < this.inventoryPanelTop + INVENTORY_BAND_HEIGHT && y + height > panelTop;
    }

    // Layout-driven playfield rendering -------------------------------------

    /**
     * Resolves the layout the table currently runs against the local registry.
     * A null binding is the bare table (no deck); a binding that vanished from
     * the registry yields {@code null} and flags {@link #layoutMissing} so the
     * status line can warn (bad drops are then rejected server-side, so this
     * only ever affects appearance).
     */
    @Nullable
    private TableLayoutDefinition resolveActiveLayout(TableGroupState groupState)
    {
        this.layoutMissing = false;
        ResourceLocation layoutId = groupState.getActiveLayoutId();
        if (layoutId == null)
        {
            return null; // no deck bound: the bare table
        }
        TableLayoutDefinition layout = CardRegistry.getLayout(layoutId);
        if (layout == null)
        {
            this.layoutMissing = true;
            return null;
        }
        return layout.normalized();
    }

    /**
     * Rebuilds the frame's zone render/hit data from the active layout and
     * draws every zone by kind, all through the {@link TableView} rotation.
     * The blank surface renders first — its placed cards form the bottom
     * layer and its whole-playfield rect is the catch-all drop target;
     * layout zones render in declaration order afterwards, so the last zone
     * is visually topmost and hit-tested first.
     */
    private void renderZones(GuiGraphics graphics, int mouseX, int mouseY)
    {
        List<RenderedCard> cards = new ArrayList<>();
        List<ZoneHit> hits = new ArrayList<>();
        this.renderedCards = cards;
        this.zoneHits = hits;
        this.layoutMissing = false;
        if (this.minecraft == null || this.minecraft.level == null || this.view == null)
        {
            return;
        }
        TableGroupService.GroupView group = this.clientGroup();
        TableGroupState groupState = group != null ? clientGroupState(group) : null;
        if (groupState == null)
        {
            return;
        }
        TableView tableView = this.view;
        int playLeft = playfieldLeft();
        int playTop = playfieldTop();
        int playWidth = playfieldWidth();
        int playHeight = playfieldHeight();

        // The blank surface: one group-level free area covering the whole
        // playfield. It has no sub-regions and draws no chrome of its own,
        // but it carries the cards played onto the bare table in the same
        // group-level normalized coordinates the layout zones use — so its
        // content is the table's bottom layer, below the layout zones, and
        // its whole-playfield rect catches every drop no zone claims.
        Rect surfaceTableRect = new Rect(playLeft, playTop, playWidth, playHeight);
        hits.add(new ZoneHit(TableLayoutDefinition.ZONE_FREE, null, surfaceTableRect, surfaceTableRect));
        renderPlacedCards(graphics, groupState.getSurface().placedCards(), surfaceTableRect,
                cards, mouseX, mouseY, tableView);

        TableLayoutDefinition layout = resolveActiveLayout(groupState);
        if (layout == null)
        {
            return; // no deck bound or the layout is missing: just the bare table
        }
        for (ZoneDefinition zone : layout.zones())
        {
            if (TableLayoutDefinition.ZONE_HAND.equals(zone.id()))
            {
                continue; // the hand renders as the fixed strip, never on the playfield
            }
            // Zone rects are computed in table pixel space (unrotated) and
            // mapped to the screen through the view transform, so every
            // client sees the same table from its own side.
            Rect tableRect = scaledRect(playLeft, playTop, playWidth, playHeight, zone);
            Rect screenRect = toScreenRect(tableView, tableRect);
            renderZone(graphics, zone, tableRect, screenRect, groupState, cards, hits, mouseX, mouseY, tableView);
        }
    }

    /** Table pixel rect → screen-space AABB rect (rounded once, shared by render and hit-test). */
    private static Rect toScreenRect(TableView tableView, Rect tableRect)
    {
        int[] bounds = tableView.transformRect(tableRect.x(), tableRect.y(), tableRect.width(), tableRect.height());
        return new Rect(bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    private void renderZone(GuiGraphics graphics, ZoneDefinition zone, Rect tableRect, Rect rect,
                            TableGroupState groupState,
                            List<RenderedCard> cards, List<ZoneHit> hits, int mouseX, int mouseY,
                            TableView tableView)
    {
        if (zone.kind() == ZoneDefinition.Kind.STACK)
        {
            List<CardInstance> pile = stackOf(zone, groupState);
            int count = pile.size();
            if (this.drag != null && containsInstance(pile, this.drag.instanceId()))
            {
                count--; // pile top is on the mouse
            }
            // A pile is anchored at the centre of its zone rect: for a corner
            // pile (draw) that is nearly the corner itself, while a zone that
            // covers the whole playfield (the default discard pile) lands in
            // the middle of the table. The anchor is derived in table space
            // so the rotation carries it to the right screen spot; the pile's
            // cards keep their pixel size (swap for odd quarters).
            int[] pileAabb = tableView.transformCard(
                    tableRect.x() + (tableRect.width() - CARD_WIDTH) / 2,
                    tableRect.y() + (tableRect.height() - CARD_HEIGHT) / 2,
                    CARD_WIDTH, CARD_HEIGHT);
            // Drop capture is the whole zone rect, not just the visible stack:
            // this is what lets a full-playfield discard pile catch every
            // otherwise-unmatched drop. Grabbing the top card still uses the
            // small pile rect (see the rendered-card entry below).
            hits.add(new ZoneHit(zone.id(), zone, tableRect, rect));
            this.renderPile(graphics, pile, count, pileAabb[0], pileAabb[1], pileAabb[2], pileAabb[3],
                    tableView.displayRotationDeg(0));
            this.drawZoneLabel(graphics, zone, rect);
            if (!pile.isEmpty())
            {
                // The whole stack is grabbable; hover resolves to its top card.
                Rect grab = pileRect(pileAabb[0], pileAabb[1], pileAabb[2], pileAabb[3]);
                cards.add(new RenderedCard(pile.get(pile.size() - 1), grab.x(), grab.y(),
                        grab.width(), grab.height()));
            }
            return;
        }
        hits.add(new ZoneHit(zone.id(), zone, tableRect, rect));
        if (zone.kind() == ZoneDefinition.Kind.GRID)
        {
            this.renderGridSlots(graphics, zone, tableRect, tableView);
        }
        renderPlacedCards(graphics, placedOf(zone, groupState), tableRect, cards, mouseX, mouseY, tableView);
        this.drawZoneLabel(graphics, zone, rect);
    }

    /**
     * Draws one set of placed (FREE/GRID) cards and registers each in the
     * frame's hover list. {@code tableRect} is the unrotated pixel rect the
     * entries' normalized coordinates are relative to: the zone rect for a
     * layout zone, the whole playfield for the blank surface. Position
     * mapping happens in table space (identical for every client); the view
     * transform then places and turns the card. Cards are fixed-size
     * sprites, so they go through transformCard (exact pixel swap) rather
     * than the aspect-stretching rect map.
     */
    private void renderPlacedCards(GuiGraphics graphics, List<ZoneState.PlacedCard> placed, Rect tableRect,
                                   List<RenderedCard> cards, int mouseX, int mouseY, TableView tableView)
    {
        for (ZoneState.PlacedCard entry : placed)
        {
            if (this.isDragging(entry.card()))
            {
                continue; // held by the mouse; the zone already shows it gone
            }
            int[] position = placedCardPosition(tableRect, entry);
            int[] aabb = tableView.transformCard(position[0], position[1], CARD_WIDTH, CARD_HEIGHT,
                    entry.card().rotation());
            boolean hovered = hitTest(mouseX, mouseY, aabb[0], aabb[1], aabb[2], aabb[3]);
            this.drawCard(graphics, entry.card(), entry.card().isFaceUp(),
                    aabb[0], aabb[1], aabb[2], aabb[3], hovered,
                    tableView.displayRotationDeg(entry.card().rotation()));
            cards.add(new RenderedCard(entry.card(), aabb[0], aabb[1], aabb[2], aabb[3]));
        }
    }

    /**
     * GRID zones show their slot cells so empty slots stay discoverable. The
     * cells cut the rect into {@code capacity} equal columns starting at the
     * rect's left edge — the same rule {@code quantizeGrid} snaps to, so a
     * snapped card center lands on the cell's center.
     */
    private void renderGridSlots(GuiGraphics graphics, ZoneDefinition zone, Rect tableRect, TableView tableView)
    {
        int slots = Math.max(1, zone.capacity());
        float slotWidth = (float) tableRect.width() / slots;
        for (int index = 0; index < slots; index++)
        {
            // Each slot rect is transformed individually so the slot row
            // follows the table axes under rotation.
            int slotTableX = tableRect.x() + (int) (index * slotWidth);
            int slotTableRight = tableRect.x() + (int) ((index + 1) * slotWidth);
            int[] bounds = tableView.transformRect(slotTableX + 1, tableRect.y() + 1,
                    Math.max(1, slotTableRight - slotTableX - 2), Math.max(1, tableRect.height() - 2));
            graphics.renderOutline(bounds[0], bounds[1], bounds[2], bounds[3], COLOR_PLAYFIELD_EDGE);
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
        this.drawLabel(graphics, label, rect.x() + 2, rect.y() - 10, COLOR_TEXT_DIM);
    }

    // Zone data accessors ----------------------------------------------------

    /** STACK cards of a zone: every pile is a layout-declared group-level zone. */
    private static List<CardInstance> stackOf(ZoneDefinition zone, TableGroupState groupState)
    {
        ZoneState state = groupState.getSharedZones().get(zone.id());
        return state == null ? List.of() : state.stackCards();
    }

    /** FREE/GRID placements of a zone: every declared zone is group-level. */
    private static List<ZoneState.PlacedCard> placedOf(ZoneDefinition zone, TableGroupState groupState)
    {
        ZoneState state = groupState.getSharedZones().get(zone.id());
        return state == null ? List.of() : state.placedCards();
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

    /**
     * Zone-local normalized position → pixel top-left of the card inside the
     * zone rect. The stored coordinate is the card's <em>center</em>, so it
     * maps 1:1 onto the rect (0 = left/top edge, 1 = right/bottom edge) with
     * no card-size inset: the same point reads identically for a 34x48 card
     * or a 48x34 one, which keeps it rotation-clean. GRID slot cells are cut
     * by the same rule, so a snapped slot center lands on the cell's center.
     */
    private static int[] placedCardPosition(Rect rect, ZoneState.PlacedCard entry)
    {
        int centerX = rect.x() + Math.round(entry.x() * rect.width());
        int centerY = rect.y() + Math.round(entry.y() * rect.height());
        return new int[] {centerX - CARD_WIDTH / 2, centerY - CARD_HEIGHT / 2};
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

    /** Whether the card is one of the local player's own hand cards. */
    private boolean isOwnHandCard(CardInstance card)
    {
        BlockPos ownSection = ownSeatPosition();
        if (ownSection == null)
        {
            return false;
        }
        for (CardInstance handCard : ClientHandStore.hand(ownSection))
        {
            if (handCard.instanceId().equals(card.instanceId()))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Draws a pile as a short fan of face-down layers. Each layer carries the
     * card it stands for (never its face), so the pile shows the back its own
     * pack declares — card override → set default → core back — instead of
     * always falling back to the anonymous core back.
     */
    private void renderPile(GuiGraphics graphics, List<CardInstance> pile, int count, int x, int y,
                            int width, int height, int rotationDegrees)
    {
        int layers = Math.min(PILE_MAX_LAYERS, count);
        int bottom = count - layers; // index of the deepest visible card
        for (int index = bottom; index < count; index++)
        {
            CardInstance card = pile.get(index);
            if (this.isDragging(card))
            {
                continue; // held by the mouse; the pile already shows it gone
            }
            int layer = index - bottom;
            this.drawCard(graphics, card, false, x + layer * PILE_STACK_OFFSET,
                    y - layer * PILE_STACK_OFFSET, width, height, false, rotationDegrees);
        }
        if (layers == 0)
        {
            // Empty pile outline so the slot is still discoverable. It must sit
            // where a filled pile sits (y is the top-left of the bottom card),
            // otherwise the slot and its hit rect drift a card height apart.
            graphics.renderOutline(x, y, width, height, COLOR_PLAYFIELD_EDGE);
        }
        this.drawCenteredLabel(graphics, Component.literal(String.valueOf(count)),
                x + width / 2 + PILE_STACK_OFFSET * layers / 2, y + 4, COLOR_HOVER);
    }

    /**
     * Hit area of one pile, anchored the same way {@link #renderPile} draws it:
     * {@code (x, y)} is the top-left of the bottom card, and every further layer
     * steps up-right by {@link #PILE_STACK_OFFSET}, so the rect grows in those
     * two directions to keep the whole visible stack clickable.
     */
    private static Rect pileRect(int x, int y, int width, int height)
    {
        int spread = PILE_STACK_OFFSET * (PILE_MAX_LAYERS - 1);
        return new Rect(x, y - spread, width + spread, height + spread);
    }

    /**
     * The local player's rendered hand: nothing while unseated, minus the card
     * currently on the mouse. Single source of truth for the strip, its hover
     * test and the backpack panel's lift.
     */
    private List<CardInstance> ownHandCards()
    {
        BlockPos ownSection = ownSeatPosition();
        if (ownSection == null)
        {
            return List.of();
        }
        return ClientHandStore.hand(ownSection).stream()
                .filter(card -> !this.isDragging(card))
                .toList();
    }

    // Own hand strip: only this client's cards (delivered by HandSyncPacket),
    // fanned along the bottom centre of the screen with a hover highlight. The
    // strip is the player's main point of contact with the table, so it sits in
    // the reserved band they are already looking at rather than on the board.
    //
    // Overflow: the fan first compresses its pitch (down to MIN_PITCH) so every
    // card stays partially visible; when even that cannot fit, the strip becomes
    // a scissored viewport over the longer content row and pans by
    // handScrollOffset (wheel / middle-drag). Cards outside the viewport are
    // neither drawn nor hit-tested.
    private void renderHand(GuiGraphics graphics, int mouseX, int mouseY)
    {
        List<CardInstance> hand = ownHandCards();
        if (hand.isEmpty())
        {
            this.handStripRect = null;
            return;
        }
        int cardCount = hand.size();
        int viewportWidth = HandStripLayout.viewportWidth(this.width);
        int pitch = HandStripLayout.pitch(cardCount, viewportWidth);
        int contentWidth = HandStripLayout.contentWidth(cardCount, pitch);
        boolean overflow = HandStripLayout.isOverflow(contentWidth, viewportWidth);
        this.handScrollOffset = HandStripLayout.clampScroll(
                overflow ? this.handScrollOffset : 0, contentWidth, viewportWidth);

        int viewportLeft = HandStripLayout.VIEWPORT_PAD_X;
        // Comfortable/compressed hands stay centred inside the viewport; a
        // scrolled fan pins its content origin to the viewport's left edge and
        // pans with handScrollOffset instead.
        int contentOriginX = overflow
                ? viewportLeft
                : viewportLeft + Math.max(0, (viewportWidth - contentWidth) / 2);
        // Bottom-anchored inside the reserved band; on windows too short to
        // hold the band at all, the playfield's lower edge is the hard limit
        // instead (the strip then wins the overlap it cannot avoid).
        int stripTop = Math.max(playfieldBottom() + 2,
                this.height - HAND_CARD_HEIGHT - HAND_BOTTOM_MARGIN);
        this.handStripRect = new Rect(viewportLeft, stripTop - 4,
                viewportWidth, HAND_CARD_HEIGHT + 8);

        graphics.fill(this.handStripRect.x(), this.handStripRect.y(),
                this.handStripRect.x() + this.handStripRect.width(),
                this.handStripRect.y() + this.handStripRect.height(), COLOR_PANEL);

        // Clip so partial cards at a scrolled edge never paint outside the
        // viewport; the full-width panel plate behind them stays unclipped.
        graphics.enableScissor(viewportLeft, stripTop,
                viewportLeft + viewportWidth, stripTop + HAND_CARD_HEIGHT);
        Component hoveredName = null;
        int[] visible = HandStripLayout.visibleRange(
                cardCount, pitch, this.handScrollOffset, viewportWidth);
        for (int index = visible[0]; index < visible[1]; index++)
        {
            CardInstance card = hand.get(index);
            int cardX = contentOriginX + index * pitch - this.handScrollOffset;
            boolean hovered = mouseX >= cardX && mouseX < cardX + HAND_CARD_WIDTH
                    && mouseY >= stripTop && mouseY < stripTop + HAND_CARD_HEIGHT;
            // The owner always sees their own hand's face: a hand is hidden
            // information delivered to its owner only, so a card back here
            // would hide it from the one player entitled to read it. The
            // persistent faceUp flag stays free for the table's orientation.
            this.drawCard(graphics, card, true, cardX, stripTop,
                    HAND_CARD_WIDTH, HAND_CARD_HEIGHT, hovered, card.rotation());
            if (hovered)
            {
                hoveredName = displayNameOf(card);
            }
        }
        graphics.disableScissor();

        if (overflow)
        {
            this.renderHandOverflowChrome(graphics, contentWidth, viewportWidth, viewportLeft,
                    stripTop, cardCount, hoveredName == null);
        }

        if (hoveredName != null)
        {
            this.drawCenteredLabel(graphics,
                    Component.translatable("gui.cardtable.hand_hint", hoveredName),
                    this.width / 2, stripTop - 12, COLOR_HOVER);
        }
    }

    /**
     * Edge fades and a position badge shown only while the hand is scrolled.
     * The fades mark that more cards exist off-viewport; the badge reads as
     * "visible-range / total" so the player always knows how much of the hand
     * is currently in view. The badge yields to a hover name so the two never
     * fight for the same line.
     */
    private void renderHandOverflowChrome(GuiGraphics graphics, int contentWidth, int viewportWidth,
                                          int viewportLeft, int stripTop, int cardCount,
                                          boolean showBadge)
    {
        int fade = Math.min(18, viewportWidth / 8);
        if (fade > 1)
        {
            if (this.handScrollOffset > 0)
            {
                graphics.fillGradient(viewportLeft, stripTop,
                        viewportLeft + fade, stripTop + HAND_CARD_HEIGHT,
                        COLOR_EDGE_FADE, 0x00000000);
            }
            int maxScroll = HandStripLayout.maxScroll(contentWidth, viewportWidth);
            if (this.handScrollOffset < maxScroll)
            {
                graphics.fillGradient(viewportLeft + viewportWidth - fade, stripTop,
                        viewportLeft + viewportWidth, stripTop + HAND_CARD_HEIGHT,
                        0x00000000, COLOR_EDGE_FADE);
            }
        }
        if (!showBadge)
        {
            return;
        }
        int pitch = HandStripLayout.pitch(cardCount, viewportWidth);
        int[] visible = HandStripLayout.visibleRange(
                cardCount, pitch, this.handScrollOffset, viewportWidth);
        Component badge = Component.translatable("gui.cardtable.hand_overflow",
                visible[0] + 1, visible[1], cardCount);
        this.drawCenteredLabel(graphics, badge, this.width / 2, stripTop - 12, COLOR_TEXT_DIM);
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
    //
    // {@code (x, y, width, height)} is the axis-aligned bounding box the card
    // occupies on screen, and {@code rotationDegrees} its visual rotation
    // (card rotation composed with the view rotation). For a quarter-turned
    // card the blit rect is the AABB with width/height swapped around the
    // shared centre, so the rotated texture covers the AABB exactly and the
    // hover/hit rect always matches what is drawn.
    private void drawCard(GuiGraphics graphics, @Nullable CardInstance card, boolean faceUp,
                          int x, int y, int width, int height, boolean hovered, int rotationDegrees)
    {
        CardDefinition definition = card != null ? CardRegistry.get(card.definitionId()) : null;
        ResourceLocation texture = resolveTexture(definition, faceUp);
        int rotation = Math.floorMod(rotationDegrees, 360);

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

        int blitX = x;
        int blitY = y;
        int blitWidth = width;
        int blitHeight = height;
        if (rotation % 180 != 0)
        {
            blitX = x + (width - height) / 2;
            blitY = y + (height - width) / 2;
            blitWidth = height;
            blitHeight = width;
        }

        PoseStack pose = graphics.pose();
        pose.pushPose();
        if (rotation != 0)
        {
            pose.translate(blitX + blitWidth / 2.0D, blitY + blitHeight / 2.0D, 0);
            pose.mulPose(Axis.ZP.rotationDegrees(rotation));
            pose.translate(-blitWidth / 2.0D, -blitHeight / 2.0D, 0);
            graphics.blit(binding.location(), 0, 0, blitWidth, blitHeight,
                    0.0F, 0.0F, binding.width(), binding.height(), binding.width(), binding.height());
        }
        else
        {
            graphics.blit(binding.location(), blitX, blitY, blitWidth, blitHeight,
                    0.0F, 0.0F, binding.width(), binding.height(), binding.width(), binding.height());
        }
        pose.popPose();
        graphics.renderOutline(x, y, width, height, COLOR_CARD_BORDER);
        if (hovered)
        {
            graphics.renderOutline(x - 1, y - 1, width + 2, height + 2, COLOR_HOVER);
        }
    }

    // Face-down cards fall back: card override → set default → core back.
    // The last step matters for file content packs: a pack that ships no back
    // texture must still show a card back, not the "?" missing placeholder,
    // or every face-down card of that pack would be unreadable on the table.
    @Nullable
    private static ResourceLocation resolveTexture(@Nullable CardDefinition definition, boolean faceUp)
    {
        if (definition == null)
        {
            return DEFAULT_BACK; // a card whose definition the client does not know
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
        ResourceLocation setBack = set != null ? set.defaultBackTexture() : null;
        return setBack != null ? setBack : DEFAULT_BACK;
    }

    private void drawPlaceholder(GuiGraphics graphics, int x, int y, int width, int height)
    {
        graphics.fill(x, y, x + width, y + height, COLOR_CARD_MISSING);
        graphics.renderOutline(x, y, width, height, COLOR_CARD_BORDER);
        String glyph = "?";
        graphics.drawString(this.font, glyph, x + (width - this.font.width(glyph)) / 2,
                y + height / 2 - 4, COLOR_HOVER, false);
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
        // Cells first: the seat ring is turned by the own seat's index, and the
        // view by the own seat's position, so both read the current frame's
        // cells rather than the previous frame's.
        this.cells = group != null ? this.computeCells(group) : List.of();
        this.seats = group != null ? this.computeSeats(group) : List.of();
        this.view = this.computeView();

        // The vanilla slot pass below draws the backpack before this method's
        // board content, which is what used to let a card cover the panel. Park
        // the grid off-screen for that pass, restore it immediately after, and
        // repaint the whole backpack last (renderInventoryOverlay) so it is the
        // frame's topmost layer no matter what the table put on screen.
        this.positionInventorySlots(INVENTORY_HIDDEN_OFFSET);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.positionInventorySlots(this.showInventory ? 0 : INVENTORY_HIDDEN_OFFSET);
        // That parked pass also resolved hoveredSlot against the off-screen
        // grid, so it came back null. Recompute it now the grid is back in
        // place: the item tooltip, the hotbar-swap keys and the drop keys all
        // read this field between frames.
        this.hoveredSlot = this.slotUnderCursor(mouseX, mouseY);

        this.renderSeats(graphics, mouseX, mouseY);
        this.renderHand(graphics, mouseX, mouseY);
        this.renderStatus(graphics);
        this.renderDebug(graphics, group);

        // The held card follows the mouse; the server's authoritative reply
        // will discard this preview on the next sync. It is drawn before the
        // backpack, so dragging a card across the panel cannot hide it either.
        if (this.drag != null)
        {
            // A hand card shows its face while held (its owner may read it),
            // any other card keeps the face the table gives it: holding a
            // face-down card must not be a peek at it. A hand card previews
            // upright — the same orientation applyPlayOrientation will land
            // it with once playRotation is applied. A table card keeps the
            // orientation it will have on this player's view (card rotation
            // composed with the view rotation).
            int previewRotation = this.drag.fromHand() ? 0
                    : this.view != null
                    ? this.view.displayRotationDeg(this.drag.card().rotation()) : this.drag.card().rotation();
            this.drawCard(graphics, this.drag.card(), this.drag.fromHand() || this.drag.card().isFaceUp(),
                    mouseX - CARD_WIDTH / 2, mouseY - CARD_HEIGHT / 2, CARD_WIDTH, CARD_HEIGHT, true,
                    previewRotation);
        }

        // Topmost layer: the backpack is painted after everything else, so no
        // board content can ever cover it.
        this.renderInventoryOverlay(graphics, mouseX, mouseY);
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
                this.drawCenteredLabel(graphics, Component.translatable("gui.cardtable.seat_empty"),
                        seat.x(), seat.y() - 4, COLOR_TEXT_DIM);
            }
        }
    }

    private void renderOccupant(GuiGraphics graphics, SeatSlot seat, int left, int top)
    {
        Player player = this.resolvePlayer(seat.occupantId());
        if (player == null)
        {
            // Neutral placeholder figure for a seated player not present in the scene.
            graphics.fill(left + 8, top + 5, left + 18, top + 15, COLOR_TEXT_DIM);
            graphics.fill(left + 5, top + 16, left + 21, top + 23, COLOR_TEXT_DIM);
            return;
        }

        // 3D portrait anchored at the seat plate's bottom edge; entity extends
        // upward by roughly 2x the scale, so scale 10 fits the 26px plate.
        InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,
                seat.x, top + SEAT_SIZE - 3, 10,
                this.lastMouseX - seat.x, this.lastMouseY - seat.y, player);
        this.renderSeatName(graphics, player.getName().getString(), seat.x(), top + SEAT_SIZE + 2);
    }

    /**
     * One line of screen text, drawn the only way this screen draws text: on a
     * dark backing plate, with shadowing turned off.
     *
     * <p>Both halves are load-bearing. The plate is what makes a label
     * background-proof — this screen paints text over the wood texture, the
     * translucent playfield, card backs, seat plates and bare world, and no
     * single text colour reads against all of them. Shadowing is off because
     * vanilla's drop shadow lays a near-black offset copy of every glyph under
     * the text; on a light surface that copy is plainly visible and the label
     * reads as doubled and ghosted. The plate supplies the contrast the shadow
     * was only faking, so turning it off costs nothing.</p>
     */
    private void drawLabel(GuiGraphics graphics, Component text, int x, int y, int color)
    {
        if (text.getString().isEmpty())
        {
            return;
        }
        int width = this.font.width(text);
        graphics.fill(x - LABEL_PAD_X, y - LABEL_PAD_Y,
                x + width + LABEL_PAD_X, y + this.font.lineHeight + LABEL_PAD_Y, COLOR_LABEL_BG);
        graphics.drawString(this.font, text, x, y, color, false);
    }

    /**
     * Centered counterpart of {@link #drawLabel}. The plate is clamped inside the
     * window so a label whose centre sits near an edge — a seat name on the
     * bottom of the ring, a status line on a narrow window — stays whole instead
     * of running off screen.
     */
    private void drawCenteredLabel(GuiGraphics graphics, Component text, int centerX, int y, int color)
    {
        if (text.getString().isEmpty())
        {
            return;
        }
        int width = this.font.width(text);
        int left = Math.min(Math.max(centerX - width / 2, LABEL_PAD_X),
                Math.max(LABEL_PAD_X, this.width - width - LABEL_PAD_X));
        int top = Math.min(Math.max(y, LABEL_PAD_Y),
                Math.max(LABEL_PAD_Y, this.height - this.font.lineHeight - LABEL_PAD_Y));
        this.drawLabel(graphics, text, left, top, color);
    }

    /**
     * Draws one seat name as a label: a dark plate with a single copy of the
     * name on top, trimmed by pixel width so it can never be cut between two
     * glyphs.
     */
    private void renderSeatName(GuiGraphics graphics, String name, int centerX, int plateTop)
    {
        int budget = Math.min(SEAT_NAME_MAX_WIDTH, Math.max(1, this.width - LABEL_PAD_X * 4));
        String label = this.trimToWidth(name, budget);
        if (label.isEmpty())
        {
            return;
        }
        this.drawCenteredLabel(graphics, Component.literal(label), centerX, plateTop - 1, COLOR_HOVER);
    }

    /**
     * Trims a label to a pixel budget, appending {@code "..."} when anything had
     * to be cut. Width-based on purpose: cutting by character count can slice a
     * name in the middle of no glyph in particular and, worse, leaves a stub
     * with no hint that the name continues.
     */
    private String trimToWidth(String name, int maxWidth)
    {
        if (this.font.width(name) <= maxWidth)
        {
            return name;
        }
        String ellipsis = "...";
        int budget = Math.max(0, maxWidth - this.font.width(ellipsis));
        return this.font.plainSubstrByWidth(name, budget) + ellipsis;
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
            // The message is centred, so it would land on whatever owns the
            // bottom of the screen: on the open backpack's slot grid, or on the
            // hand strip. It is lifted above whichever is there — the strip
            // (drawn earlier this frame) takes precedence, since it is the one
            // the player is actually looking at.
            int statusY;
            if (this.showInventory)
            {
                statusY = Math.max(2, this.inventoryPanelTop - INVENTORY_CAP_HEIGHT - 12);
            }
            else if (this.handStripRect != null)
            {
                statusY = Math.max(2, this.handStripRect.y() - 14);
            }
            else
            {
                statusY = this.height - 34;
            }
            this.drawCenteredLabel(graphics, message, this.width / 2, statusY, COLOR_ERROR);
        }
    }

    // Development debug info lives in the bottom-right corner, toggled with F3.
    private void renderDebug(GuiGraphics graphics, @Nullable TableGroupService.GroupView group)
    {
        // Suppressed while the backpack panel is open: the readout lives in the
        // bottom-right corner and would overprint the panel's slot grid.
        if (!this.showDebugInfo || this.showInventory
                || group == null || this.minecraft == null || this.minecraft.level == null)
        {
            return;
        }
        BlockEntity master = this.minecraft.level.getBlockEntity(group.masterPos());
        if (!(master instanceof CardTableBlockEntity masterEntity))
        {
            return;
        }
        TableGroupState debugState = masterEntity.getGroupState();
        List<Component> lines = new ArrayList<>(List.of(
                Component.translatable("gui.cardtable.members",
                        this.menu.getSeatedCount(), this.menu.getSeatCount()),
                Component.translatable("gui.cardtable.version", debugState.getVersion()),
                Component.translatable("gui.cardtable.table_id",
                        debugState.getTableId().toString().substring(0, 8)),
                Component.translatable("gui.cardtable.active_set",
                        debugState.getActiveSetId() != null ? debugState.getActiveSetId().toString() : "-"),
                Component.translatable("gui.cardtable.active_layout",
                        debugState.getActiveLayoutId() != null ? debugState.getActiveLayoutId().toString() : "-")));
        appendLayoutDebug(lines, debugState);
        int y = this.height - 12;
        for (Component line : lines)
        {
            this.drawLabel(graphics, line, this.width - 4 - this.font.width(line), y, COLOR_TEXT_DIM);
            y -= 12;
        }
    }

    // Zone counts plus "key → action" hints, purely informational: the debug
    // readout follows whatever the pack declared instead of the old fixed
    // draw/discard counters. Resolves the layout locally so the render flag
    // (owned by renderZones) is untouched.
    private void appendLayoutDebug(List<Component> lines, TableGroupState debugState)
    {
        ResourceLocation layoutId = debugState.getActiveLayoutId();
        TableLayoutDefinition layout = layoutId == null ? null : CardRegistry.getLayout(layoutId);
        if (layout == null)
        {
            return;
        }
        TableLayoutDefinition normalized = layout.normalized();
        for (ZoneDefinition zone : normalized.zones())
        {
            if (TableLayoutDefinition.ZONE_HAND.equals(zone.id()))
            {
                continue; // the hand keeps its own readout
            }
            ZoneState state = debugState.getSharedZones().get(zone.id());
            lines.add(Component.literal(zoneDisplayName(zone) + ": " + (state == null ? 0 : state.size())));
        }
        for (TableActionDefinition action : normalized.actions())
        {
            if (action.key() != null)
            {
                lines.add(Component.translatable("gui.cardtable.action_hint",
                        InputConstants.getKey(action.key()).getDisplayName(), actionDisplayName(action)));
            }
        }
    }

    private static Component zoneDisplayName(ZoneDefinition zone)
    {
        return zone.label() != null ? zone.label() : Component.literal("#" + zone.id().getPath());
    }

    private static Component actionDisplayName(TableActionDefinition action)
    {
        return action.label() != null ? action.label() : Component.literal("#" + action.id().getPath());
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
            List<CardInstance> hand = ownHandCards();
            int index = this.handCardIndexAt(mouseX, mouseY, hand.size());
            if (index >= 0 && index < hand.size())
            {
                return hand.get(index);
            }
        }
        return null;
    }

    /**
     * Index of the own-hand card under the cursor, honouring the current
     * pitch, centre-origin and scroll offset. Returns -1 when the point is
     * outside the strip or over empty space.
     */
    private int handCardIndexAt(double mouseX, double mouseY, int cardCount)
    {
        if (cardCount <= 0 || this.handStripRect == null)
        {
            return -1;
        }
        int viewportLeft = HandStripLayout.VIEWPORT_PAD_X;
        int viewportWidth = HandStripLayout.viewportWidth(this.width);
        int pitch = HandStripLayout.pitch(cardCount, viewportWidth);
        int contentWidth = HandStripLayout.contentWidth(cardCount, pitch);
        boolean overflow = HandStripLayout.isOverflow(contentWidth, viewportWidth);
        int contentOriginX = overflow
                ? viewportLeft
                : viewportLeft + Math.max(0, (viewportWidth - contentWidth) / 2);
        int stripTop = this.handStripRect.y() + 4;
        for (int index = cardCount - 1; index >= 0; index--)
        {
            int cardX = contentOriginX + index * pitch - this.handScrollOffset;
            if (hitTest(mouseX, mouseY, cardX, stripTop, HAND_CARD_WIDTH, HAND_CARD_HEIGHT))
            {
                return index;
            }
        }
        return -1;
    }

    /**
     * True when the strip is long enough to pan and the cursor sits over it.
     * Wheel and middle-drag scrolling only engage in that case so a normal
     * hand never steals the scroll from the rest of the UI.
     */
    private boolean handScrollEngaged(double mouseX, double mouseY)
    {
        List<CardInstance> hand = ownHandCards();
        if (hand.isEmpty() || this.handStripRect == null || !this.handStripRect.contains(mouseX, mouseY))
        {
            return false;
        }
        int viewportWidth = HandStripLayout.viewportWidth(this.width);
        int pitch = HandStripLayout.pitch(hand.size(), viewportWidth);
        return HandStripLayout.isOverflow(
                HandStripLayout.contentWidth(hand.size(), pitch), viewportWidth);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta)
    {
        if (this.drag == null && this.handScrollEngaged(mouseX, mouseY))
        {
            List<CardInstance> hand = ownHandCards();
            int viewportWidth = HandStripLayout.viewportWidth(this.width);
            int pitch = HandStripLayout.pitch(hand.size(), viewportWidth);
            int contentWidth = HandStripLayout.contentWidth(hand.size(), pitch);
            this.handScrollOffset = HandStripLayout.applyScroll(
                    this.handScrollOffset, delta, contentWidth, viewportWidth);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
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
        // Shift while dropping is the "play it face down" modifier; the server
        // only honours it for a card leaving the hand (see applyPlayOrientation).
        boolean faceDown = Screen.hasShiftDown();

        // The hand strip keeps the highest priority (unchanged behavior). The
        // hand target carries no seat any more: the server resolves it to the
        // sender's own seat.
        int playRotation = playRotation();
        if (this.handStripRect != null && this.handStripRect.contains(mouseX, mouseY))
        {
            if (ownSeatPosition() != null)
            {
                this.sendAction(new CardActionPacket.Action.Move(instanceId,
                        new ZoneRef(TableLayoutDefinition.ZONE_HAND), null, false, 0));
            }
            return;
        }
        // Drop targets: the blank surface is the catch-all (whole playfield
        // hit rect), layout zones win by the smallest hit area — the most
        // specific intent first. Equal areas keep "visually topmost wins".
        ZoneHit target = this.pickZoneHit(mouseX, mouseY);
        if (target == null)
        {
            // No valid target: drop cancels, the authoritative state is unchanged.
            return;
        }
        if (target.zone() != null && target.zone().kind() == ZoneDefinition.Kind.STACK)
        {
            this.sendAction(new CardActionPacket.Action.Move(instanceId,
                    new ZoneRef(target.zoneId()), null, faceDown, playRotation));
            return;
        }
        // Placed zones take the drop point in table space: the screen point
        // is mapped back through the view's inverse transform, so every
        // client reports the same group-level coordinates no matter how its
        // view is rotated.
        double[] tablePoint = this.view != null
                ? this.view.screenToTable(mouseX, mouseY) : new double[] {mouseX, mouseY};
        float x = normalizeDrop(tablePoint[0], target.tableRect().x(), target.tableRect().width());
        float y = normalizeDrop(tablePoint[1], target.tableRect().y(), target.tableRect().height());
        if (target.zone() != null && target.zone().kind() == ZoneDefinition.Kind.GRID)
        {
            // Client-side quantization for instant feedback; the
            // server re-quantizes idempotently (same slot wins).
            float[] snapped = ZoneDefinition.quantizeGrid(x, y, target.zone().capacity());
            x = snapped[0];
            y = snapped[1];
        }
        this.sendAction(new CardActionPacket.Action.Move(instanceId,
                new ZoneRef(target.zoneId()), new Vec2(x, y), faceDown, playRotation));
    }

    /**
     * Table-space rotation a card leaving the hand should land with: the
     * inverse of this client's view rotation, so {@link TableView#displayRotationDeg}
     * of the result is 0 and the played card stays upright on the actor's
     * screen. Spectators and unseated players have no view rotation to cancel.
     */
    private int playRotation()
    {
        return this.view != null ? Math.floorMod(-this.view.quarters() * 90, 360) : 0;
    }

    /**
     * The drop target under the mouse: among every zone whose hit rect covers
     * the point, the one with the smallest hit area wins (most specific);
     * ties resolve to the visually topmost, i.e. the later render entry.
     */
    @Nullable
    private ZoneHit pickZoneHit(double mouseX, double mouseY)
    {
        ZoneHit best = null;
        long bestArea = Long.MAX_VALUE;
        for (ZoneHit hit : this.zoneHits)
        {
            if (!hit.hitRect().contains(mouseX, mouseY))
            {
                continue;
            }
            long area = (long) hit.hitRect().width() * hit.hitRect().height();
            if (area <= bestArea)
            {
                bestArea = area;
                best = hit;
            }
        }
        return best;
    }

    /**
     * Table-space pixel → zone-local normalized coordinate for the dropped
     * card's <em>center</em>: the mouse point becomes the card center, so the
     * card lands exactly where the player pointed. Inverse of the render-side
     * mapping, with no card-size inset — 0 = the zone's left/top edge and
     * 1 = its right/bottom edge.
     */
    private static float normalizeDrop(double value, int zoneStart, int zoneSize)
    {
        float normalized = (float) (value - zoneStart) / Math.max(1, zoneSize);
        return Math.max(0.0F, Math.min(1.0F, normalized));
    }

    private void sendAction(CardActionPacket.Action action)
    {
        NetworkHandler.CHANNEL.sendToServer(new CardActionPacket(this.menu.getTablePosition(), action));
    }

    // Keyboard actions come from the active layout's action table (the pack
    // decides which keys do what); the core hardcodes none of them. Every
    // request is validated server-side before it touches any state.
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        // Rebindable backpack toggle (default B). Checked first so a custom
        // binding never collides with the pack-declared action keys below.
        if (ModKeyBindings.TOGGLE_INVENTORY.matches(keyCode, scanCode))
        {
            this.toggleInventory();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F3)
        {
            this.showDebugInfo = !this.showDebugInfo;
            return true;
        }
        TableGroupService.GroupView group = this.clientGroup();
        TableGroupState groupState = group != null ? clientGroupState(group) : null;
        TableLayoutDefinition layout = groupState == null ? null : resolveActiveLayout(groupState);
        if (layout != null)
        {
            for (TableActionDefinition action : layout.actions())
            {
                if (action.key() != null && matchesKey(action.key(), keyCode, scanCode))
                {
                    this.sendDeclaredAction(action);
                    return true;
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** GLFW key-name matching for pack-declared action keys. */
    private static boolean matchesKey(String keyName, int keyCode, int scanCode)
    {
        InputConstants.Key bound = InputConstants.getKey(keyName);
        if (bound.getType() == InputConstants.Type.KEYSYM)
        {
            return bound.getValue() == keyCode;
        }
        return bound.getType() == InputConstants.Type.SCANCODE && bound.getValue() == scanCode;
    }

    /**
     * Performs one declared action: card-targeted primitives (flip/rotate)
     * attach the card under the cursor, pile-targeted ones (draw/shuffle)
     * carry no instance. Nothing is sent when a needed card is missing.
     */
    private void sendDeclaredAction(TableActionDefinition action)
    {
        if (action.type() == TableActionDefinition.Type.FLIP
                || action.type() == TableActionDefinition.Type.ROTATE)
        {
            CardInstance hovered = hoveredCard(this.lastMouseX, this.lastMouseY);
            if (hovered == null)
            {
                return;
            }
            this.sendAction(new CardActionPacket.Action.Perform(action.id(), hovered.instanceId()));
            return;
        }
        this.sendAction(new CardActionPacket.Action.Perform(action.id(), null));
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        // Middle-drag pans an overflowing hand strip. Checked before left-click
        // so a pan never also starts a card drag.
        if (button == 2 && this.handScrollEngaged(mouseX, mouseY))
        {
            this.handPanning = true;
            this.handPanLastMouseX = mouseX;
            return true;
        }
        if (button == 0)
        {
            // The backpack toggle intercepts the click (and must not also trigger
            // the deck slot sitting right above it). Relayout moves the inventory
            // slots back on-screen (or off-screen) to match the new state.
            if (this.inventoryToggleRect != null && this.inventoryToggleRect.contains(mouseX, mouseY))
            {
                this.toggleInventory();
                return true;
            }
            CardInstance hovered = hoveredCard(mouseX, mouseY);
            if (hovered != null)
            {
                this.drag = new Drag(hovered, isOwnHandCard(hovered));
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
        if (button == 2)
        {
            this.handPanning = false;
        }
        if (button == 0 && this.drag != null)
        {
            this.finishDrag(mouseX, mouseY);
            this.drag = null;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // While a card is held the mouse drag belongs to it, not to vanilla's
    // slot quick-craft logic. Middle-drag pans an overflowing hand strip.
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY)
    {
        if (this.handPanning)
        {
            List<CardInstance> hand = ownHandCards();
            int viewportWidth = HandStripLayout.viewportWidth(this.width);
            int pitch = HandStripLayout.pitch(hand.size(), viewportWidth);
            int contentWidth = HandStripLayout.contentWidth(hand.size(), pitch);
            // Dragging right reveals earlier cards (content moves right → offset shrinks).
            this.handScrollOffset = HandStripLayout.clampScroll(
                    this.handScrollOffset - (mouseX - this.handPanLastMouseX),
                    contentWidth, viewportWidth);
            this.handPanLastMouseX = mouseX;
            return true;
        }
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

    /**
     * The card currently held by the mouse (client-side preview only).
     *
     * @param fromHand whether it was picked up from the local player's own
     *                 hand, which is the only case where the preview may show
     *                 the face of a card the server still counts as face-down.
     */
    private record Drag(CardInstance card, boolean fromHand)
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

    /**
     * One drop target: the blank surface ({@code zone == null}, addressed by
     * the reserved surface id) or a rendered layout zone. {@code tableRect}
     * is the unrotated table-space rect drop points normalize against;
     * {@code hitRect} is its on-screen bounding rect for picking.
     */
    private record ZoneHit(ResourceLocation zoneId, @Nullable ZoneDefinition zone, Rect tableRect, Rect hitRect)
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
