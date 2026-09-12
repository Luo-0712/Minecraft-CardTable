package com.example.cardtable.item;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.api.CardRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModCreativeModTabs
{
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CardTableMod.MODID);

    /**
     * Dedicated tab so decks are not buried in Building Blocks. Contents are
     * filled when the creative screen first builds the tab — after common
     * setup has frozen {@link CardRegistry} — so one deck per loaded set is
     * always present once packs are on disk.
     */
    public static final RegistryObject<CreativeModeTab> CARD_TABLE = CREATIVE_MODE_TABS.register("card_table",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.cardtable"))
                    .icon(() -> new ItemStack(ModItems.CARD_TABLE_ITEM.get()))
                    .displayItems((parameters, output) ->
                    {
                        output.accept(new ItemStack(ModItems.CARD_TABLE_ITEM.get()));
                        for (var set : CardRegistry.allSets())
                        {
                            output.accept(DeckItem.create(set.id()));
                        }
                    })
                    .build());

    private ModCreativeModTabs()
    {
    }
}
