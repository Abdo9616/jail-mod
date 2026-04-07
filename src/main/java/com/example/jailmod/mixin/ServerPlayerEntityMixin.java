package com.example.jailmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.jailmod.JailMod;

import net.minecraft.server.level.ServerPlayer;

@Mixin(ServerPlayer.class)
public class ServerPlayerEntityMixin {

    @Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
    private void jailmod$blockDropSelectedItem(boolean entireStack, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (JailMod.isPlayerInJail(self)) {
            self.getInventory().setChanged();
            self.containerMenu.broadcastChanges();
            self.inventoryMenu.broadcastChanges();
            ci.cancel();
        }
    }
}
