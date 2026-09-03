package com.example.cardtable.block.entity;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities
{
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CardTableMod.MODID);

    public static final RegistryObject<BlockEntityType<CardTableBlockEntity>> CARD_TABLE = BLOCK_ENTITIES.register("card_table",
            () -> BlockEntityType.Builder.of(CardTableBlockEntity::new, ModBlocks.CARD_TABLE.get()).build(null));

    private ModBlockEntities()
    {
    }
}
