package com.example.cardtable.block;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.block.custom.CardTableBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks
{
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, CardTableMod.MODID);

    // The tabletop block used as the entry point for card table interactions
    public static final RegistryObject<Block> CARD_TABLE = BLOCKS.register("card_table", () -> new CardTableBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F)));

    private ModBlocks()
    {
    }
}
