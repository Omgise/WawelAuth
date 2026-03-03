package org.fentanylsolutions.wawelauth.client.gui;

import java.util.UUID;

import net.minecraft.util.ResourceLocation;

import org.fentanylsolutions.tabfaces.TabFaces;
import org.fentanylsolutions.tabfaces.util.ClientUtil;

import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class TabFacesCompat {

    private static final String MODID = "tabfaces";

    private TabFacesCompat() {}

    public static boolean isAvailable() {
        return Loader.isModLoaded(MODID);
    }

    public static String buildFaceKey(String providerName, String displayName, UUID uuid) {
        String provider = trimToNull(providerName);
        String id = uuid != null ? uuid.toString() : trimToNull(displayName);
        if (id == null) {
            return null;
        }
        if (provider == null) {
            return id;
        }
        return provider + ":" + id;
    }

    public static void drawFace(String faceKey, String displayName, UUID uuid, int x, int y, float alpha) {
        if (!isAvailable()) return;
        String key = trimToNull(faceKey);
        if (key == null) return;
        Api.drawFace(key, trimToNull(displayName), uuid, x, y, alpha);
    }

    public static void ensureRegistered(String faceKey, String displayName, UUID uuid) {
        if (!isAvailable()) return;
        String key = trimToNull(faceKey);
        if (key == null || uuid == null) return;
        Api.ensureRegistered(key, trimToNull(displayName), uuid);
    }

    public static void forceRefreshFace(String faceKey, String displayName, UUID uuid) {
        if (!isAvailable()) return;
        String key = trimToNull(faceKey);
        if (key == null || uuid == null) return;
        Api.forceRefreshFace(key, trimToNull(displayName), uuid);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Isolated class to avoid touching TabFaces classes unless the mod is present.
     */
    private static final class Api {

        private Api() {}

        static void drawFace(String faceKey, String displayName, UUID uuid, int x, int y, float alpha) {
            if (TabFaces.varInstanceClient == null) return;
            if (uuid != null) ensureRegistered(faceKey, displayName, uuid);
            ResourceLocation rl = TabFaces.varInstanceClient.clientRegistry
                .getTabMenuResourceLocation(faceKey, false, -1);
            if (rl != null) {
                ClientUtil.drawPlayerFace(rl, x, y, alpha);
            }
        }

        static void ensureRegistered(String faceKey, String displayName, UUID uuid) {
            if (TabFaces.varInstanceClient == null || uuid == null) return;
            // Only insert if not already in registry: let TabFaces handle state transitions
            if (TabFaces.varInstanceClient.clientRegistry.displayNameInRegistry(faceKey)) return;
            String name = displayName != null ? displayName : faceKey;
            TabFaces.varInstanceClient.playerProfileRegistry.setProfile(faceKey, new GameProfile(uuid, name));
            TabFaces.varInstanceClient.clientRegistry.insert(faceKey, uuid, null, false, -1);
        }

        static void forceRefreshFace(String faceKey, String displayName, UUID uuid) {
            if (TabFaces.varInstanceClient == null || uuid == null) return;
            TabFaces.varInstanceClient.clientRegistry.removeByDisplayName(faceKey);
            String name = displayName != null ? displayName : faceKey;
            TabFaces.varInstanceClient.playerProfileRegistry.setProfile(faceKey, new GameProfile(uuid, name));
            TabFaces.varInstanceClient.clientRegistry.insert(faceKey, uuid, null, false, -1);
        }
    }
}
