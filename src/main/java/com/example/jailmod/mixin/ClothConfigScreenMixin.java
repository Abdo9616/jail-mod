package com.example.jailmod.mixin;

import com.example.jailmod.client.config.JailModClothConfigScreen;

import me.shedaniel.clothconfig2.gui.ClothConfigScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClothConfigScreen.class)
public abstract class ClothConfigScreenMixin {
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void jailmod$updateConfigFileButtons(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partialTick, CallbackInfo callbackInfo) {
        JailModClothConfigScreen.updateConfigFileButtonVisibility((ClothConfigScreen) (Object) this);
    }
}
