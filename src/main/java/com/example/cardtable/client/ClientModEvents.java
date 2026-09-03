package com.example.cardtable.client;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.client.card.CardTextureResolver;
import com.example.cardtable.client.screen.CardTableScreen;
import com.example.cardtable.menu.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = CardTableMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents
{
    private ClientModEvents()
    {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event)
    {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.CARD_TABLE.get(), CardTableScreen::new);
            // Runs after the common setup froze CardRegistry, so file-pack
            // textures land in the texture manager before any card renders.
            CardTextureResolver.registerDynamicTextures();
        });
    }
}
