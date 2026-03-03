package org.fentanylsolutions.wawelauth.client.gui;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import net.minecraft.util.Util;

import org.fentanylsolutions.wawelauth.WawelAuth;
import org.lwjgl.Sys;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * OS folder open helper based on Catalogue-Vintage's Java 25-safe implementation.
 */
@SideOnly(Side.CLIENT)
public final class FolderOpenUtil {

    private FolderOpenUtil() {}

    public static boolean openFolder(File folder) {
        if (folder == null) {
            return false;
        }
        if (!folder.exists() && !folder.mkdirs()) {
            return false;
        }

        String absolutePath = folder.getAbsolutePath();

        if (Util.getOSType() == Util.EnumOS.OSX) {
            try {
                Runtime.getRuntime()
                    .exec(new String[] { "/usr/bin/open", absolutePath });
                return true;
            } catch (IOException ioexception) {
                WawelAuth.LOG.error("Problem opening folder", ioexception);
            }
        } else if (Util.getOSType() == Util.EnumOS.WINDOWS) {
            String openCommand = String.format("cmd.exe /C start \"Open file\" \"%s\"", absolutePath);
            try {
                Runtime.getRuntime()
                    .exec(openCommand);
                return true;
            } catch (IOException ioexception) {
                WawelAuth.LOG.error("Problem opening folder", ioexception);
            }
        }

        boolean awtDesktopFailed = false;

        try {
            Class<?> oclass = Class.forName("java.awt.Desktop");
            Object object = oclass.getMethod("getDesktop", new Class[0])
                .invoke(null);
            oclass.getMethod("browse", new Class[] { URI.class })
                .invoke(object, folder.toURI());
            return true;
        } catch (Throwable throwable) {
            WawelAuth.LOG.error("Problem opening folder", throwable);
            awtDesktopFailed = true;
        }

        if (awtDesktopFailed) {
            WawelAuth.LOG.info("Opening folder via system class fallback");
            try {
                Class<?> sysX = Class.forName("org.lwjglx.Sys");
                Object ok = sysX.getMethod("openURL", String.class)
                    .invoke(null, "file://" + absolutePath);
                if (ok instanceof Boolean) {
                    return (Boolean) ok;
                }
                return true;
            } catch (Throwable ignored) {
                try {
                    Sys.openURL("file://" + absolutePath);
                    return true;
                } catch (Throwable t) {
                    WawelAuth.LOG.error("Failed to open folder via Sys fallback", t);
                }
            }
        }

        return false;
    }
}
