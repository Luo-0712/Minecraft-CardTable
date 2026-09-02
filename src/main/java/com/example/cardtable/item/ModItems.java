package com.example.cardtable.item;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.block.ModBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems
{
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, CardTableMod.MODID);

    // The inventory item for the card table block
    public static final RegistryObject<Item> CARD_TABLE_ITEM = ITEMS.register("card_table", () -> new BlockItem(
            ModBlocks.CARD_TABLE.get(), new Item.Properties()));

    private ModItems()
    {
    }
}
