package org.fentanylsolutions.wawelauth.client.render;

import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureUtil;

/**
 * Helpers for distinguishing a real registered skin texture from Minecraft's
 * cached missing-texture sentinel.
 */
public final class SkinTextureState {

    private SkinTextureState() {}

    public static boolean isUsable(ITextureObject textureObject) {
        return isUsable(textureObject, TextureUtil.missingTexture);
    }

    static boolean isUsable(ITextureObject textureObject, ITextureObject missingTextureMarker) {
        return textureObject != null && textureObject != missingTextureMarker;
    }
}
