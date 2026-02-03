package com.example.jailmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.jailmod.JailMod;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

@Mixin(ScreenHandler.class)
public class ScreenHandlerMixin {

    @Inject(method = "onSlotClick", at = @At("HEAD"), cancellable = true)
    private void jailmod$blockThrow(int slotIndex, int button, SlotActionType actionType, PlayerEntity player,
            CallbackInfo ci) {
        if (player instanceof ServerPlayerEntity serverPlayer && JailMod.isPlayerInJail(serverPlayer)) {
            if (actionType == SlotActionType.THROW || slotIndex == -999) {
                serverPlayer.getInventory().markDirty();
                ((ScreenHandler) (Object) this).updateToClient();
                serverPlayer.playerScreenHandler.updateToClient();
                ci.cancel();
            }
        }
    }
}
