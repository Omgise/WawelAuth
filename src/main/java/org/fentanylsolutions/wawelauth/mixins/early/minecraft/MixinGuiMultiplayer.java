package org.fentanylsolutions.wawelauth.mixins.early.minecraft;

import java.util.Collections;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;

import org.fentanylsolutions.wawelauth.Config;
import org.fentanylsolutions.wawelauth.client.gui.AccountManagerScreen;
import org.fentanylsolutions.wawelauth.client.gui.AuthButton;
import org.fentanylsolutions.wawelauth.client.gui.FolderIconButton;
import org.fentanylsolutions.wawelauth.client.gui.FolderOpenUtil;
import org.fentanylsolutions.wawelauth.client.gui.GuiText;
import org.fentanylsolutions.wawelauth.wawelclient.ServerBindingPersistence;
import org.fentanylsolutions.wawelauth.wawelclient.WawelClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.cleanroommc.modularui.factory.ClientGUI;

@Mixin(GuiMultiplayer.class)
public abstract class MixinGuiMultiplayer extends GuiScreen {

    @Unique
    private static final int WAWELAUTH_ACCOUNTS_BUTTON_ID = 200;
    @Unique
    private static final int WAWELAUTH_OPEN_FOLDER_BUTTON_ID = 201;

    @Unique
    private GuiButton wawelauth$openFolderButton;

    @Inject(method = "initGui", at = @At("RETURN"))
    private void wawelauth$onInitGui(CallbackInfo ci) {
        GuiMultiplayer self = (GuiMultiplayer) (Object) this;
        ServerBindingPersistence.setActiveServerList(self.func_146795_p()); // GuiMultiplayer.getServerList

        WawelClient client = WawelClient.instance();
        if (client != null) {
            ServerBindingPersistence.clearMissingAccountBindings(client.getAccountManager());
        }
    }

    @Inject(method = "func_146794_g", at = @At("RETURN")) // GuiMultiplayer.createButtons
    private void wawelauth$addAccountsButton(CallbackInfo ci) {
        int accountsWidth = AuthButton.preferredWidth(this.fontRendererObj);
        int iconWidth = 20;
        int gap = 2;
        int x = this.width - 5 - (accountsWidth + gap + iconWidth);
        this.buttonList.add(new AuthButton(WAWELAUTH_ACCOUNTS_BUTTON_ID, x, 5, 20));
        this.wawelauth$openFolderButton = new FolderIconButton(
            WAWELAUTH_OPEN_FOLDER_BUTTON_ID,
            x + accountsWidth + gap,
            5);
        this.buttonList.add(this.wawelauth$openFolderButton);
    }

    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    private void wawelauth$onAction(GuiButton button, CallbackInfo ci) {
        if (button.id == WAWELAUTH_ACCOUNTS_BUTTON_ID) {
            ClientGUI.open(new AccountManagerScreen());
            ci.cancel();
            return;
        }
        if (button.id == WAWELAUTH_OPEN_FOLDER_BUTTON_ID) {
            FolderOpenUtil.openFolder(Config.getConfigDir());
            ci.cancel();
        }
    }

    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void wawelauth$drawFolderTooltip(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (this.wawelauth$openFolderButton instanceof FolderIconButton) {
            FolderIconButton folderButton = (FolderIconButton) this.wawelauth$openFolderButton;
            if (folderButton.isMouseOver(mouseX, mouseY)) {
                this.drawHoveringText(
                    Collections.singletonList(GuiText.tr("wawelauth.gui.multiplayer.open_folder")),
                    mouseX,
                    mouseY,
                    this.fontRendererObj);
            }
        }
    }
}
