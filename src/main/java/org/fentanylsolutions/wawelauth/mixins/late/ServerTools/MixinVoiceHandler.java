package org.fentanylsolutions.wawelauth.mixins.late.ServerTools;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.entity.player.PlayerEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import info.servertools.core.chat.VoiceHandler;

/**
 * ServerTools VoiceHandler.nameFormat crashes when no server is running
 * because it calls MinecraftServer.getServer().isSinglePlayer() without
 * a null check.
 */
@Mixin(value = VoiceHandler.class, remap = false)
public class MixinVoiceHandler {

    @Inject(method = "nameFormat", at = @At("HEAD"), cancellable = true)
    private void wawelauth$skipWhenNoServer(PlayerEvent.NameFormat event, CallbackInfo ci) {
        if (MinecraftServer.getServer() == null) {
            ci.cancel();
        }
    }
}
