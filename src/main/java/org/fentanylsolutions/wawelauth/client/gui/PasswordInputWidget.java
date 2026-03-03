package org.fentanylsolutions.wawelauth.client.gui;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class PasswordInputWidget extends ParentWidget<PasswordInputWidget> {

    private static final UITexture PASSWORD_EYE_TEXTURE = UITexture.fullImage("wawelauth", "gui/password_eye");
    private static final IDrawable EYE_OPEN_ICON = PASSWORD_EYE_TEXTURE.getSubArea(0.0f, 0.0f, 0.5f, 1.0f);
    private static final IDrawable EYE_CLOSED_ICON = PASSWORD_EYE_TEXTURE.getSubArea(0.5f, 0.0f, 1.0f, 1.0f);

    private final PasswordFieldWidget field;
    private final ButtonWidget<?> toggleButton;

    public PasswordInputWidget() {
        this.field = new PasswordFieldWidget();
        this.toggleButton = new ButtonWidget<>();

        this.field.widthRel(1.0f)
            .heightRel(1.0f)
            // Reserve a larger right gutter so long text never collides with the eye button.
            .padding(4, 26, 0, 0);
        this.field.setClipRightPadding(22);

        this.toggleButton.size(10, 10)
            .right(6)
            .top(4)
            .background(IDrawable.EMPTY)
            .hoverBackground(IDrawable.EMPTY)
            .onMousePressed(mouseButton -> {
                this.field.togglePasswordVisibility();
                refreshEyeIcon();
                return true;
            });

        refreshEyeIcon();
        child(this.field);
        child(this.toggleButton);
    }

    private void refreshEyeIcon() {
        IDrawable icon = this.field.isPasswordHidden() ? EYE_CLOSED_ICON : EYE_OPEN_ICON;
        this.toggleButton.overlay(icon)
            .hoverOverlay(icon);
    }

    public PasswordInputWidget hintText(String text) {
        this.field.hintText(text);
        return this;
    }

    public PasswordInputWidget setMaxLength(int maxLength) {
        this.field.setMaxLength(maxLength);
        return this;
    }

    public String getText() {
        return this.field.getText();
    }

    public PasswordInputWidget setText(String text) {
        this.field.setText(text == null ? "" : text);
        return this;
    }

    public PasswordInputWidget setPasswordHidden(boolean hidden) {
        this.field.setPasswordHidden(hidden);
        refreshEyeIcon();
        return this;
    }

    public boolean isPasswordHidden() {
        return this.field.isPasswordHidden();
    }

    public PasswordInputWidget onEnterPressed(Runnable callback) {
        this.field.onEnterPressed(callback);
        return this;
    }
}
