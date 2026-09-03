package com.example.cardtable.menu;

import com.example.cardtable.CardTableMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus
{
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, CardTableMod.MODID);

    public static final RegistryObject<MenuType<CardTableMenu>> CARD_TABLE = MENUS.register("card_table",
            () -> IForgeMenuType.create(CardTableMenu::new));

    private ModMenus()
    {
    }
}
