package com.example.cardtable.client;

import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Client key bindings, registered from {@link ClientModEvents} on the MOD bus.
 * Only touched from client code, so the class never loads on a dedicated server.
 */
public final class ModKeyBindings
{
    /** Translation key of the shared category shown in the vanilla controls screen. */
    public static final String CATEGORY = "key.cardtable.category";

    /** Toggles the backpack panel inside the card table screen (default: B). */
    public static final KeyMapping TOGGLE_INVENTORY =
            new KeyMapping("key.cardtable.toggle_inventory", GLFW.GLFW_KEY_B, CATEGORY);

    private ModKeyBindings()
    {
    }
}
