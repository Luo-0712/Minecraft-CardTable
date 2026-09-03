package com.example.cardtable.menu;

import com.example.cardtable.block.entity.CardTableBlockEntity;
import com.example.cardtable.card.DeckService;
import com.example.cardtable.table.TableGroupService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * Single-slot container backing the menu's deck slot. Server-side it reads
 * and writes the master block's {@code TableGroupState#deckStack} and drives
 * {@link DeckService} on changes (insert fills the draw pile, removal
 * reclaims the deck's cards); client-side it only mirrors the synced stack
 * for rendering. The master block is re-resolved on each access, so slot
 * count and layout never depend on construction-time group state.
 */
public class DeckSlotContainer implements Container
{
    private final Level level;
    private final BlockPos tablePosition;
    private final ServerPlayerBridge actor;
    private final Predicate<Player> validity;

    /** Bridge so the container never needs a live ServerPlayer on the client. */
    public interface ServerPlayerBridge
    {
        @Nullable
        net.minecraft.server.level.ServerPlayer resolve();
    }

    public DeckSlotContainer(Level level, BlockPos tablePosition,
                             ServerPlayerBridge actor, Predicate<Player> validity)
    {
        this.level = level;
        this.tablePosition = tablePosition;
        this.actor = actor;
        this.validity = validity;
    }

    @Nullable
    private TableGroupService.GroupView group()
    {
        return TableGroupService.resolve(this.level, this.tablePosition);
    }

    @Nullable
    private CardTableBlockEntity master()
    {
        TableGroupService.GroupView group = group();
        return group != null && this.level.getBlockEntity(group.masterPos()) instanceof CardTableBlockEntity master
                ? master : null;
    }

    @Nullable
    private net.minecraft.server.level.ServerPlayer actor()
    {
        return this.actor.resolve();
    }

    @Override
    public int getContainerSize()
    {
        return 1;
    }

    @Override
    public boolean isEmpty()
    {
        CardTableBlockEntity master = master();
        return master == null || master.getGroupState().getDeckStack().isEmpty();
    }

    @Override
    public ItemStack getItem(int slot)
    {
        CardTableBlockEntity master = master();
        return master == null ? ItemStack.EMPTY : master.getGroupState().getDeckStack();
    }

    @Override
    public ItemStack removeItem(int slot, int amount)
    {
        ItemStack current = getItem(slot);
        if (current.isEmpty() || amount <= 0)
        {
            return ItemStack.EMPTY;
        }
        // A deck is indivisible: any removal takes the whole deck out, which
        // atomically reclaims its cards from every zone.
        TableGroupService.GroupView group = group();
        if (this.level.isClientSide || group == null)
        {
            // Client mirror only; the server owns the real transition.
            CardTableBlockEntity master = master();
            if (master != null)
            {
                master.getGroupState().setDeckStack(ItemStack.EMPTY);
            }
            return current;
        }
        // DeckService owns the state write: the slot must still read as holding
        // the deck when it runs, otherwise its guard rejects the removal and
        // the deck's cards are never reclaimed.
        DeckService.onDeckRemoved(this.level, group, actor());
        return current;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot)
    {
        return removeItem(slot, 1);
    }

    @Override
    public void setItem(int slot, ItemStack stack)
    {
        ItemStack previous = getItem(slot);
        TableGroupService.GroupView group = group();
        if (this.level.isClientSide || group == null)
        {
            // Client mirror only; the server owns the real transition.
            CardTableBlockEntity master = master();
            if (master != null)
            {
                master.getGroupState().setDeckStack(stack);
            }
            return;
        }
        // DeckService owns the state write: the slot must still read as empty
        // when it runs, otherwise its "one deck at a time" guard mistakes this
        // very deck for a leftover one and no card is ever minted.
        if (previous.isEmpty() && !stack.isEmpty())
        {
            DeckService.onDeckInserted(this.level, group, stack, actor());
        }
        else if (!previous.isEmpty() && stack.isEmpty())
        {
            DeckService.onDeckRemoved(this.level, group, actor());
        }
        else if (!stack.isEmpty() && !ItemStack.isSameItemSameTags(previous, stack))
        {
            // Slot swap attempt: rejected by the slot's mayPlace, but if it
            // ever lands here, keep the previous deck to stay consistent.
            CardTableBlockEntity master = master();
            if (master != null)
            {
                master.getGroupState().setDeckStack(previous);
            }
        }
    }

    @Override
    public void setChanged()
    {
        CardTableBlockEntity master = master();
        if (master != null)
        {
            master.markStateChanged();
        }
    }

    @Override
    public boolean stillValid(Player player)
    {
        return this.validity.test(player);
    }

    @Override
    public void clearContent()
    {
        TableGroupService.GroupView group = group();
        if (this.level.isClientSide || group == null)
        {
            return;
        }
        DeckService.onDeckRemoved(this.level, group, actor());
    }
}
