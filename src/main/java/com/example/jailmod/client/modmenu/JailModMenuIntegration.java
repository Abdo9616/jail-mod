package com.example.jailmod.client.modmenu;

import com.example.jailmod.client.config.JailModClothConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class JailModMenuIntegration implements ModMenuApi {
    private static final Component TITLE = Component.literal("Jail Mod Configuration");
    private static final Component BACK = Component.literal("Back");

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            if (!isClothConfigPresent()) {
                return infoScreen(parent, Component.literal("Install Cloth Config API to edit Jail Mod settings."));
            }
            try {
                return JailModClothConfigScreen.create(parent);
            } catch (Throwable error) {
                System.err.println("Failed to create Jail Mod config screen: " + error);
                return infoScreen(parent, Component.literal("Could not open the Jail Mod config screen."));
            }
        };
    }

    private static boolean isClothConfigPresent() {
        FabricLoader loader = FabricLoader.getInstance();
        return loader.isModLoaded("cloth-config") || loader.isModLoaded("cloth-config2");
    }

    private static Screen infoScreen(Screen parent, Component message) {
        return new ConfirmScreen(
                accepted -> Minecraft.getInstance().setScreenAndShow(parent),
                TITLE,
                message,
                BACK,
                BACK);
    }
}
