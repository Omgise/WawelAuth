package org.fentanylsolutions.wawelauth.client.gui;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ProviderDisplayName {

    private static final String MICROSOFT_PROVIDER_KEY = "Mojang";
    private static final String MICROSOFT_DISPLAY_NAME_KEY = "wawelauth.gui.common.microsoft";

    private ProviderDisplayName() {}

    public static boolean isMicrosoftProvider(String providerName) {
        return MICROSOFT_PROVIDER_KEY.equals(providerName);
    }

    public static String displayName(String providerName) {
        if (providerName == null || providerName.trim()
            .isEmpty()) {
            return "?";
        }
        if (isMicrosoftProvider(providerName)) {
            return GuiText.tr(MICROSOFT_DISPLAY_NAME_KEY);
        }
        return providerName;
    }
}
