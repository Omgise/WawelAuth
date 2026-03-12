package org.fentanylsolutions.wawelauth.client.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.multiplayer.ServerData;

import org.fentanylsolutions.wawelauth.wawelclient.IServerDataExt;
import org.fentanylsolutions.wawelauth.wawelclient.ServerBindingPersistence;
import org.fentanylsolutions.wawelauth.wawelclient.ServerCapabilities;
import org.fentanylsolutions.wawelauth.wawelclient.SingleplayerAccountPersistence;
import org.fentanylsolutions.wawelauth.wawelclient.WawelClient;
import org.fentanylsolutions.wawelauth.wawelclient.data.AccountStatus;
import org.fentanylsolutions.wawelauth.wawelclient.data.ClientAccount;
import org.fentanylsolutions.wawelauth.wawelclient.data.ClientProvider;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Column;
import com.cleanroommc.modularui.widgets.layout.Row;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class ServerAccountPickerScreen extends ParentAwareModularScreen {

    private static final int ACCOUNT_LABEL_MAX_WIDTH_PX = 174;

    /**
     * ModularScreen's constructor calls buildUI() immediately, before subclass
     * field assignments execute. Pass serverData through a static field so it
     * is available during buildUI(). Single-threaded (render thread only).
     */
    private static ServerData pendingServerData;
    private static String pendingStatusMessage;
    private static boolean pendingSingleplayerMode;

    private final ServerData serverData;
    private final boolean singleplayerMode;

    public static void open(ServerData serverData) {
        open(serverData, null);
    }

    public static void open(ServerData serverData, String statusMessage) {
        pendingServerData = serverData;
        pendingStatusMessage = statusMessage;
        pendingSingleplayerMode = false;
        ClientGUI.open(new ServerAccountPickerScreen());
    }

    public static void openSingleplayer() {
        pendingServerData = null;
        pendingStatusMessage = null;
        pendingSingleplayerMode = true;
        ClientGUI.open(new ServerAccountPickerScreen());
    }

    private ServerAccountPickerScreen() {
        super("wawelauth");
        openParentOnClose(true);
        this.serverData = pendingServerData;
        this.singleplayerMode = pendingSingleplayerMode;
        pendingServerData = null;
        pendingSingleplayerMode = false;
    }

    @Override
    public ModularPanel buildUI(ModularGuiContext context) {
        boolean singleplayerMode = pendingSingleplayerMode || this.singleplayerMode;
        ServerData targetServerData = pendingServerData != null ? pendingServerData : serverData;
        if (targetServerData == null && !singleplayerMode) {
            return ModularPanel.defaultPanel("wawelauth_server_picker", 200, 80)
                .child(
                    new Column().widthRel(1.0f)
                        .heightRel(1.0f)
                        .padding(6)
                        .child(
                            new TextWidget<>(GuiText.key("wawelauth.gui.common.no_server_selected")).widthRel(1.0f)
                                .height(14)));
        }

        ModularPanel panel = ModularPanel.defaultPanel("wawelauth_server_picker", 200, 226);
        WawelClient client = WawelClient.instance();

        IServerDataExt ext = null;
        String title;
        String statusMessage = singleplayerMode ? null : pendingStatusMessage;
        ServerCapabilities localAuthCapabilities = null;
        boolean wawelAuthServer = false;
        boolean localAuthAvailable = false;
        String[] trustedLocalProviderName = { null };
        long selectedAccountId;

        if (singleplayerMode) {
            title = GuiText.tr("wawelauth.gui.singleplayer_picker.title");
            if (client != null) {
                SingleplayerAccountPersistence.clearMissingSelection(client.getAccountManager());
            }
            selectedAccountId = SingleplayerAccountPersistence.getSelectedAccountId();
        } else {
            ext = (IServerDataExt) targetServerData;
            String serverName = targetServerData.serverName != null ? targetServerData.serverName
                : GuiText.tr("wawelauth.gui.common.server");
            title = GuiText.tr("wawelauth.gui.server_picker.title", serverName);

            ServerCapabilities capabilities = ext.getWawelCapabilities();
            localAuthCapabilities = ServerBindingPersistence.getEffectiveLocalAuthCapabilities(targetServerData);
            wawelAuthServer = capabilities != null && capabilities.isWawelAuthAdvertised();
            localAuthAvailable = localAuthCapabilities != null && localAuthCapabilities.isLocalAuthSupported()
                && notBlank(localAuthCapabilities.getLocalAuthApiRoot())
                && notBlank(localAuthCapabilities.getLocalAuthPublicKeyFingerprint());
            selectedAccountId = ext.getWawelAccountId();
        }
        pendingStatusMessage = null;

        final ServerData serverDataRef = targetServerData;
        final IServerDataExt serverExt = ext;
        final ServerCapabilities localAuthCapabilitiesRef = localAuthCapabilities;

        LoginDialog loginDialog = LoginDialog.attach(panel, account -> {
            if (account == null) return;
            trustedLocalProviderName[0] = account.getProviderName();
            if (serverExt == null || serverDataRef == null) {
                return;
            }
            serverExt.setWawelAccountId(account.getId());
            serverExt.setWawelProviderName(account.getProviderName());
            ServerBindingPersistence.markServerBindingOrigin(serverDataRef);
            ServerBindingPersistence.persistServerSelection(serverDataRef);
            String successMessage = GuiText.tr(
                "wawelauth.gui.server_picker.status.bound",
                account.getProfileName() != null ? account.getProfileName() : "?");
            GuiTransitionScheduler
                .transition(panel, () -> ServerAccountPickerScreen.open(serverDataRef, successMessage));
        });
        RegisterDialog registerDialog = RegisterDialog.attach(panel, success -> {
            if (Boolean.TRUE.equals(success) && trustedLocalProviderName[0] != null) {
                loginDialog.openAfterRegister(trustedLocalProviderName[0]);
            }
        });

        ListWidget<IWidget, ?> accountList = new ListWidget<>();
        accountList.widthRel(1.0f)
            .expanded();

        if (client != null) {
            if (singleplayerMode) {
                accountList.child(buildSingleplayerClearEntry(selectedAccountId, panel));
            }
            List<ClientAccount> allAccounts = new ArrayList<>(
                client.getAccountManager()
                    .listAccounts());
            Collections.sort(
                allAccounts,
                Comparator.comparing(ServerAccountPickerScreen::sortKeyForAccount, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(
                        account -> ProviderDisplayName.displayName(account.getProviderName()),
                        String.CASE_INSENSITIVE_ORDER)
                    .thenComparingLong(ClientAccount::getId));
            for (ClientAccount account : allAccounts) {
                if (singleplayerMode) {
                    accountList.child(
                        buildAccountEntry(
                            account,
                            selectedAccountId,
                            panel,
                            selected -> SingleplayerAccountPersistence.setSelectedAccountId(selected.getId())));
                } else {
                    accountList.child(buildAccountEntry(account, selectedAccountId, panel, selected -> {
                        if (serverExt == null || serverDataRef == null) {
                            return;
                        }
                        serverExt.setWawelAccountId(selected.getId());
                        serverExt.setWawelProviderName(selected.getProviderName());
                        ServerBindingPersistence.markServerBindingOrigin(serverDataRef);
                        ServerBindingPersistence.persistServerSelection(serverDataRef);
                    }));
                }
            }
        }

        accountList.margin(0, 4);

        ButtonWidget<?> manageLocalAuthBtn = new ButtonWidget<>();
        manageLocalAuthBtn.widthRel(1.0f)
            .height(18)
            .margin(0, 3, 0, 0)
            .onMousePressed(mouseButton -> {
                GuiTransitionScheduler.transition(panel, () -> AccountManagerScreen.openForLocalAuth(targetServerData));
                return true;
            });
        GuiText.fitButtonLabelMaxWidth(manageLocalAuthBtn, 180, "wawelauth.gui.server_picker.manage_local_auth");
        manageLocalAuthBtn.setEnabled(localAuthAvailable);

        Column content = new Column();
        content.widthRel(1.0f)
            .heightRel(1.0f)
            .padding(6);
        content.child(
            new TextWidget<>(IKey.str(title)).widthRel(1.0f)
                .height(14));
        content.child(accountList);
        if (statusMessage != null && !statusMessage.isEmpty()) {
            content.child(
                new TextWidget<>(IKey.str(statusMessage)).color(0xFF55FF55)
                    .widthRel(1.0f)
                    .height(10)
                    .margin(0, 2));
        }
        content.child(
            GuiText.fitButtonLabelMaxWidth(
                new ButtonWidget<>().widthRel(1.0f)
                    .height(18),
                180,
                "wawelauth.gui.common.manage_accounts")
                .onMousePressed(mouseButton -> {
                    GuiTransitionScheduler.transition(panel, () -> ClientGUI.open(new AccountManagerScreen()));
                    return true;
                }));
        content.child(manageLocalAuthBtn);

        if (wawelAuthServer) {
            ButtonWidget<?> loginLocalBtn = GuiText.fitButtonLabel(
                new ButtonWidget<>().width(90)
                    .height(18),
                90,
                "wawelauth.gui.common.login");
            loginLocalBtn.setEnabled(localAuthAvailable);
            loginLocalBtn.onMousePressed(mouseButton -> {
                openLocalAuthAction(
                    targetServerData,
                    localAuthCapabilitiesRef,
                    panel,
                    trustedLocalProviderName,
                    loginDialog,
                    registerDialog,
                    false);
                return true;
            });

            ButtonWidget<?> registerLocalBtn = GuiText.fitButtonLabel(
                new ButtonWidget<>().width(90)
                    .height(18),
                90,
                "wawelauth.gui.common.register");
            registerLocalBtn.setEnabled(localAuthAvailable);
            registerLocalBtn.onMousePressed(mouseButton -> {
                openLocalAuthAction(
                    targetServerData,
                    localAuthCapabilitiesRef,
                    panel,
                    trustedLocalProviderName,
                    loginDialog,
                    registerDialog,
                    true);
                return true;
            });

            content.child(new Widget<>().size(1, 3))
                .child(
                    new Row().widthRel(1.0f)
                        .height(20)
                        .child(loginLocalBtn)
                        .child(new Widget<>().size(6, 18))
                        .child(registerLocalBtn));
        }

        panel.child(content);

        return panel;
    }

    private static void openLocalAuthAction(ServerData targetServerData, ServerCapabilities capabilities,
        ModularPanel panel, String[] trustedLocalProviderName, LoginDialog loginDialog, RegisterDialog registerDialog,
        boolean register) {
        if (capabilities == null) {
            return;
        }

        WawelClient client = WawelClient.instance();
        if (client == null) {
            return;
        }

        ClientProvider trustedProvider = client.getLocalAuthProviderResolver()
            .findExisting(capabilities);
        if (trustedProvider == null) {
            GuiTransitionScheduler.transition(panel, () -> LocalAuthManagerScreen.open(targetServerData));
            return;
        }

        trustedLocalProviderName[0] = trustedProvider.getName();
        if (register) {
            registerDialog.open(trustedProvider.getName());
        } else {
            loginDialog.open(trustedProvider.getName());
        }
    }

    private ButtonWidget<?> buildAccountEntry(ClientAccount account, long selectedAccountId, ModularPanel panel,
        Consumer<ClientAccount> onSelect) {
        AccountStatus status = getLiveStatus(account);
        int statusColor = StatusColors.getColor(status);
        String profileName = account.getProfileName() != null ? account.getProfileName() : "?";
        String providerName = ProviderDisplayName.displayName(account.getProviderName());
        boolean isSelected = selectedAccountId == account.getId();

        ButtonWidget<?> entry = new ButtonWidget<>();
        entry.widthRel(1.0f)
            .height(16);
        if (isSelected) {
            entry.background(new Rectangle().color(0x44FFFFFF));
        }

        Row dot = new Row();
        dot.size(8, 8)
            .margin(1, 4)
            .background(new Rectangle().color(0xFF2A2A2A))
            .child(
                new Widget<>().size(6, 6)
                    .margin(1, 1)
                    .background(new Rectangle().color(statusColor)));

        String fullLabel = profileName;
        String displayLabel = GuiText.ellipsizeToPixelWidth(fullLabel, ACCOUNT_LABEL_MAX_WIDTH_PX);
        TextWidget<?> label = new TextWidget<>(IKey.str(displayLabel));
        label.expanded()
            .heightRel(1.0f);
        label.addTooltipLine(GuiText.tr("wawelauth.gui.server_picker.tooltip.account", fullLabel));
        label.addTooltipLine(GuiText.tr("wawelauth.gui.server_picker.tooltip.provider", providerName));

        Row row = new Row();
        row.widthRel(1.0f)
            .heightRel(1.0f)
            .child(new Widget<>().size(2, 16));
        if (account.getProfileUuid() != null) {
            row.child(createFaceWidget(profileName, account.getProfileUuid(), account.getProviderName()));
            row.child(new Widget<>().size(2, 16));
        }
        row.child(dot)
            .child(new Widget<>().size(2, 16))
            .child(label);

        entry.child(row);
        entry.onMousePressed(mouseButton -> {
            onSelect.accept(account);
            panel.closeIfOpen();
            return true;
        });

        return entry;
    }

    private ButtonWidget<?> buildSingleplayerClearEntry(long selectedAccountId, ModularPanel panel) {
        boolean isSelected = selectedAccountId < 0L;

        ButtonWidget<?> entry = new ButtonWidget<>();
        entry.widthRel(1.0f)
            .height(16);
        if (isSelected) {
            entry.background(new Rectangle().color(0x44FFFFFF));
        }

        TextWidget<?> label = new TextWidget<>(GuiText.key("wawelauth.gui.common.no_account_selected"));
        label.widthRel(1.0f)
            .heightRel(1.0f)
            .margin(2, 0, 0, 0);

        Row row = new Row();
        row.widthRel(1.0f)
            .heightRel(1.0f)
            .child(new Widget<>().size(2, 16))
            .child(label);

        entry.child(row);
        entry.onMousePressed(mouseButton -> {
            SingleplayerAccountPersistence.clearSelection();
            panel.closeIfOpen();
            return true;
        });
        return entry;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim()
            .isEmpty();
    }

    private static AccountStatus getLiveStatus(ClientAccount account) {
        if (account == null) return null;
        WawelClient client = WawelClient.instance();
        if (client == null) return account.getStatus();
        AccountStatus cached = client.getAccountManager()
            .getAccountStatus(account.getId());
        if (cached != null) {
            account.setStatus(cached);
            return cached;
        }
        return account.getStatus();
    }

    private static String sortKeyForAccount(ClientAccount account) {
        if (account == null || account.getProfileName() == null) {
            return "";
        }
        return account.getProfileName();
    }

    private static Widget<?> createFaceWidget(String displayName, java.util.UUID profileUuid, String providerName) {
        return new FaceWidget(displayName, profileUuid, providerName).size(8, 8)
            .margin(0, 4);
    }

}
