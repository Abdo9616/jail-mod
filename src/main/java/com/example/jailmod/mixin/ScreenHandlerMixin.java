package com.example.jailmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.jailmod.JailMod;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

@Mixin(AbstractContainerMenu.class)
public class ScreenHandlerMixin {

    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void jailmod$blockThrow(int slotIndex, int button, ContainerInput actionType, Player player,
            CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer && JailMod.isPlayerInJail(serverPlayer)) {
            if (actionType == ContainerInput.THROW || slotIndex == -999) {
                serverPlayer.getInventory().setChanged();
                ((AbstractContainerMenu) (Object) this).broadcastChanges();
                serverPlayer.inventoryMenu.broadcastChanges();
                ci.cancel();
            }
        }
    }
}
