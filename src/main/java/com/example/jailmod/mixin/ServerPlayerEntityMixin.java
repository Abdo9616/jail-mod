package com.example.jailmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.jailmod.JailMod;

import net.minecraft.server.network.ServerPlayerEntity;

@Mixin(ServerPlayerEntity.class)
public class ServerPlayerEntityMixin {

    @Inject(method = "dropSelectedItem", at = @At("HEAD"), cancellable = true)
    private void jailmod$blockDropSelectedItem(boolean entireStack, CallbackInfo ci) {
        ServerPlayerEntity self = (ServerPlayerEntity) (Object) this;
        if (JailMod.isPlayerInJail(self)) {
            self.getInventory().markDirty();
            self.currentScreenHandler.updateToClient();
            self.playerScreenHandler.updateToClient();
            ci.cancel();
        }
    }
}
