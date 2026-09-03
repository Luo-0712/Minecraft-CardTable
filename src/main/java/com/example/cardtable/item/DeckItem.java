package com.example.cardtable.item;

import com.example.cardtable.api.CardRegistry;
import com.example.cardtable.api.CardSetDefinition;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * A whole deck in one item — the physical form a content pack takes in a
 * player's inventory. The stack's NBT carries only the {@code DeckId} (the
 * {@link CardSetDefinition} id); which exact cards it contains is decided by
 * the table when the deck is inserted into its deck slot, so packs never need
 * per-card items.
 */
public class DeckItem extends Item
{
    public static final String DECK_ID_TAG = "DeckId";

    public DeckItem(Properties properties)
    {
        super(properties);
    }

    /** Creates one deck of the given set for creative tabs and commands. */
    public static ItemStack create(ResourceLocation setId)
    {
        ItemStack stack = new ItemStack(ModItems.DECK.get());
        stack.getOrCreateTag().putString(DECK_ID_TAG, setId.toString());
        return stack;
    }

    public static Optional<ResourceLocation> deckId(ItemStack stack)
    {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(DECK_ID_TAG, CompoundTag.TAG_STRING))
        {
            return Optional.empty();
        }
        try
        {
            return Optional.of(new ResourceLocation(tag.getString(DECK_ID_TAG)));
        }
        catch (Exception exception)
        {
            return Optional.empty();
        }
    }

    /** The deck's display name from its set definition; falls back to a generic name. */
    @Override
    public Component getName(ItemStack stack)
    {
        return deckId(stack)
                .map(CardRegistry::getSet)
                .map(CardSetDefinition::displayName)
                .map(Component::copy)
                .orElseGet(() -> Component.translatable("item.cardtable.deck"));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag)
    {
        deckId(stack).ifPresentOrElse(setId -> {
            CardSetDefinition set = CardRegistry.getSet(setId);
            int cards = CardRegistry.cardsInSet(setId).size();
            tooltip.add(Component.translatable("item.cardtable.deck_cards", cards)
                    .withStyle(ChatFormatting.GRAY));
            if (set == null)
            {
                tooltip.add(Component.translatable("item.cardtable.deck_missing")
                        .withStyle(ChatFormatting.RED));
            }
        }, () -> tooltip.add(Component.translatable("item.cardtable.deck_unbound")
                .withStyle(ChatFormatting.RED)));
    }
}
