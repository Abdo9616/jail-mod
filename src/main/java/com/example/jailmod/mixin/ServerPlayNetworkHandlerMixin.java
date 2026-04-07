package com.example.jailmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.example.jailmod.JailMod;

import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ContainerInput;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void jailmod$blockDropActions(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (JailMod.isPlayerInJail(player)) {
            ServerboundPlayerActionPacket.Action action = packet.getAction();
            if (action == ServerboundPlayerActionPacket.Action.DROP_ITEM
                    || action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS) {
                player.getInventory().setChanged();
                player.containerMenu.broadcastChanges();
                player.inventoryMenu.broadcastChanges();
                ci.cancel();
            }
        }
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void jailmod$blockThrowFromInventory(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (JailMod.isPlayerInJail(player) && packet.containerInput() == ContainerInput.THROW) {
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
            ci.cancel();
        }
    }
}
