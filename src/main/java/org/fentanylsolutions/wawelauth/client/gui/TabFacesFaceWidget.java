package org.fentanylsolutions.wawelauth.client.gui;

import java.util.UUID;

import net.minecraft.client.renderer.RenderHelper;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class TabFacesFaceWidget extends Widget<TabFacesFaceWidget> {

    private final String faceKey;
    private final String displayName;
    private final UUID profileUuid;
    private float alpha = 1.0f;

    public TabFacesFaceWidget(String faceKey, String displayName, UUID profileUuid) {
        this.faceKey = faceKey;
        this.displayName = displayName;
        this.profileUuid = profileUuid;
    }

    public TabFacesFaceWidget alpha(float alpha) {
        this.alpha = alpha;
        return this;
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            RenderHelper.disableStandardItemLighting();

            TabFacesCompat.drawFace(faceKey, displayName, profileUuid, 0, 0, alpha);
        } finally {
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GL11.glPopAttrib();
        }
    }
}
