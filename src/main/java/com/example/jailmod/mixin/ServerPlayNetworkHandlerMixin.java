package com.example.jailmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.jailmod.JailMod;

import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;

@Mixin(ServerPlayNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayerEntity player;

    @Inject(method = "onPlayerAction", at = @At("HEAD"), cancellable = true)
    private void jailmod$blockDropActions(PlayerActionC2SPacket packet, CallbackInfo ci) {
        if (JailMod.isPlayerInJail(player)) {
            PlayerActionC2SPacket.Action action = packet.getAction();
            if (action == PlayerActionC2SPacket.Action.DROP_ITEM
                    || action == PlayerActionC2SPacket.Action.DROP_ALL_ITEMS) {
                player.getInventory().markDirty();
                player.currentScreenHandler.updateToClient();
                player.playerScreenHandler.updateToClient();
                ci.cancel();
            }
        }
    }

    @Inject(method = "onClickSlot", at = @At("HEAD"), cancellable = true)
    private void jailmod$blockThrowFromInventory(ClickSlotC2SPacket packet, CallbackInfo ci) {
        if (JailMod.isPlayerInJail(player) && packet.actionType() == SlotActionType.THROW) {
            player.getInventory().markDirty();
            player.currentScreenHandler.updateToClient();
            player.playerScreenHandler.updateToClient();
            ci.cancel();
        }
    }
}
