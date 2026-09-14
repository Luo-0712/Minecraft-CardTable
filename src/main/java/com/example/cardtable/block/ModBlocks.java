package com.example.cardtable.block;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.block.custom.CardTableBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks
{
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, CardTableMod.MODID);

    // The tabletop block used as the entry point for card table interactions.
    // Crafted from quartz blocks, so its properties mirror vanilla quartz_block:
    // instrument/requiresCorrectToolForDrops/strength(0.8F) exactly as in Blocks,
    // and the default SoundType.STONE (quartz does not override the sound).
    // The matching mineable/pickaxe tag lives in
    // data/cardtable/tags/blocks/mineable/pickaxe.json, otherwise a pickaxe
    // would neither mine fast nor yield drops.
    public static final RegistryObject<Block> CARD_TABLE = BLOCKS.register("card_table", () -> new CardTableBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.QUARTZ)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops()
                    .strength(0.8F)));

    private ModBlocks()
    {
    }
}
