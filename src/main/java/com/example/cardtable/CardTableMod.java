package com.example.cardtable;

import com.example.cardtable.block.ModBlocks;
import com.example.cardtable.item.ModCreativeModTabs;
import com.example.cardtable.item.ModItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(CardTableMod.MODID)
public class CardTableMod
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "cardtable";

    public CardTableMod(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();

        // Register the Deferred Registers to the mod event bus so entries get registered
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModCreativeModTabs.CREATIVE_MODE_TABS.register(modEventBus);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ForgeConfigSpec so that Forge can create and load the config file for us
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // MDK 模板示例内容（示例方块/物品/标签页和示例日志），与本模组功能无关，见 ExampleContent.java
        ExampleContent.register(modEventBus);
    }

    // Add the card table item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS)
        {
            event.accept(ModItems.CARD_TABLE_ITEM);
        }
    }
}
