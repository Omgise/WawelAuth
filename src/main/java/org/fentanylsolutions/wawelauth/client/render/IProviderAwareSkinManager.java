package org.fentanylsolutions.wawelauth.client.render;

import net.minecraft.client.resources.SkinManager;
import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.wawelauth.wawelclient.data.ClientProvider;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTexture.Type;

public interface IProviderAwareSkinManager {

    ResourceLocation wawelauth$loadTexture(MinecraftProfileTexture texture, Type textureType,
        SkinManager.SkinAvailableCallback callback, ClientProvider provider);

    void wawelauth$loadProfileTextures(GameProfile profile, SkinManager.SkinAvailableCallback callback,
        boolean requireSecure, ClientProvider provider);
}
