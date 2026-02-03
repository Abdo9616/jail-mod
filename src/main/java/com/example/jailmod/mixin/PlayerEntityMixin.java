package com.example.jailmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.example.jailmod.JailMod;

import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

@Mixin(PlayerEntity.class)
public class PlayerEntityMixin {

    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void jailmod$preventDrop(ItemStack stack, boolean retainOwnership,
            CallbackInfoReturnable<ItemEntity> cir) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        if (self instanceof ServerPlayerEntity serverPlayer && JailMod.isPlayerInJail(serverPlayer)) {
            if (!stack.isEmpty()) {
                serverPlayer.getInventory().insertStack(stack);
                serverPlayer.getInventory().markDirty();
                serverPlayer.currentScreenHandler.updateToClient();
                serverPlayer.playerScreenHandler.updateToClient();
            }
            cir.setReturnValue(null);
        }
    }
}
