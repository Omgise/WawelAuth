package org.fentanylsolutions.wawelauth.mixins.early.minecraft;

import java.util.UUID;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.wawelauth.client.gui.AnimatedCapeTexture;
import org.fentanylsolutions.wawelauth.client.gui.AnimatedCapeTracker;
import org.fentanylsolutions.wawelauth.client.render.skinlayers.SkinLayers3DConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Two injection points:
 * 1. Static init: replace default Steve skin with 64x64 version
 * 2. getLocationCape: override with animated cape texture if registered
 */
@Mixin(value = AbstractClientPlayer.class, priority = 999)
public class MixinAbstractClientPlayer {

    @Shadow
    @Final
    @Mutable
    public static ResourceLocation locationStevePng;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void wawelauth$useModernSteve(CallbackInfo ci) {
        if (!SkinLayers3DConfig.modernSkinSupport) {
            return;
        }
        locationStevePng = new ResourceLocation("wawelauth", "textures/steve_64.png");
    }

    @Inject(method = "getLocationCape", at = @At("RETURN"), cancellable = true)
    private void wawelauth$overrideAnimatedCape(CallbackInfoReturnable<ResourceLocation> cir) {
        // Access UUID via Entity cast: getGameProfile() is inherited from EntityPlayer
        // and cannot be @Shadow'd on AbstractClientPlayer.
        UUID uuid = ((Entity) (Object) this).getUniqueID();
        if (uuid == null) return;

        AnimatedCapeTexture animated = AnimatedCapeTracker.get(uuid);
        if (animated != null) {
            cir.setReturnValue(animated.getResourceLocation());
        }
    }
}
