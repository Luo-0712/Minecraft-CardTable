package com.example.cardtable.client;

import com.example.cardtable.CardTableMod;
import com.example.cardtable.block.entity.ModBlockEntities;
import com.example.cardtable.client.card.CardTextureResolver;
import com.example.cardtable.client.item.DeckIconResolver;
import com.example.cardtable.client.render.CardTableDeckRenderer;
import com.example.cardtable.client.screen.CardTableScreen;
import com.example.cardtable.menu.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
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
            // Deck icons are resolved against those textures, so drop anything
            // memoised before this point and let it re-resolve against the
            // freshly registered ones.
            DeckIconResolver.clearCache();
        });
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event)
    {
        event.register(ModKeyBindings.TOGGLE_INVENTORY);
    }

    // The BER draws the face-down deck pile on a loaded table; the block's
    // static model keeps rendering underneath (RenderShape.MODEL unchanged).
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerBlockEntityRenderer(ModBlockEntities.CARD_TABLE.get(), CardTableDeckRenderer::new);
    }
}
