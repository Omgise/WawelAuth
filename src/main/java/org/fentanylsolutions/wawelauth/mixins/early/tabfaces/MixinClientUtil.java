package org.fentanylsolutions.wawelauth.mixins.early.tabfaces;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.tabfaces.util.ClientUtil;
import org.fentanylsolutions.wawelauth.client.render.skinlayers.SkinLayers3DConfig;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Force HD-safe face sampling for every TabFaces render path.
 */
@Pseudo
@Mixin(value = ClientUtil.class, remap = false)
public class MixinClientUtil {

    @Inject(method = "drawPlayerFace(Lnet/minecraft/util/ResourceLocation;FFF)V", at = @At("HEAD"), cancellable = true)
    private static void wawelauth$drawPlayerFaceHd(ResourceLocation rl, float xPos, float yPos, float alpha,
        CallbackInfo ci) {
        if (!SkinLayers3DConfig.modernSkinSupport) {
            return;
        }
        if (rl == null) {
            ci.cancel();
            return;
        }

        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(rl);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, alpha);

        int texWidth = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int texHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (texWidth <= 0 || texHeight <= 0) {
            // Fallback to normalized 64x64 UVs.
            ClientUtil.drawTexFloat(xPos, yPos, 8, 8, 8, 8, 8, 8, 64.0F, 64.0F);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            ClientUtil.drawTexFloat(xPos, yPos, 40, 8, 8, 8, 8, 8, 64.0F, 64.0F);
            ci.cancel();
            return;
        }

        boolean legacyLayout = texWidth == texHeight * 2;
        float uScale = texWidth / 64.0F;
        float vScale = texHeight / (legacyLayout ? 32.0F : 64.0F);

        int sampleWidth = Math.max(1, Math.round(8.0F * uScale));
        int sampleHeight = Math.max(1, Math.round(8.0F * vScale));
        float baseU = 8.0F * uScale;
        float baseV = 8.0F * vScale;
        ClientUtil.drawTexFloat(xPos, yPos, baseU, baseV, sampleWidth, sampleHeight, 8, 8, texWidth, texHeight);

        float hatU = 40.0F * uScale;
        float hatV = 8.0F * vScale;
        if (hatU + sampleWidth <= texWidth && hatV + sampleHeight <= texHeight) {
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            ClientUtil.drawTexFloat(xPos, yPos, hatU, hatV, sampleWidth, sampleHeight, 8, 8, texWidth, texHeight);
        }

        ci.cancel();
    }
}
