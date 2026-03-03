package org.fentanylsolutions.wawelauth.wawelclient;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;

import org.fentanylsolutions.wawelauth.WawelAuth;
import org.fentanylsolutions.wawelauth.wawelclient.data.ClientAccount;

/**
 * Persistence and cleanup helpers for per-server WawelAuth account bindings.
 */
public final class ServerBindingPersistence {

    private static volatile WeakReference<ServerList> activeServerListRef = new WeakReference<>(null);

    private ServerBindingPersistence() {}

    /**
     * Register the currently active multiplayer server list so in-memory rows
     * can be healed immediately when accounts/providers are removed.
     */
    public static void setActiveServerList(ServerList serverList) {
        activeServerListRef = new WeakReference<>(serverList);
    }

    /**
     * Persist one modified ServerData entry to servers.dat.
     */
    public static void persistServerSelection(ServerData selected) {
        try {
            // Vanilla helper that updates this server entry and flushes servers.dat.
            ServerList.func_147414_b(selected); // ServerList.saveSingleServer
            return;
        } catch (Throwable t) {
            WawelAuth.debug("ServerList.func_147414_b failed, using fallback save path: " + t.getMessage());
        }

        // Fallback path for environments where static helper signature differs.
        try {
            ServerList serverList = new ServerList(Minecraft.getMinecraft());
            serverList.loadServerList();
            for (int i = 0; i < serverList.countServers(); i++) {
                ServerData existing = serverList.getServerData(i);
                if (sameServer(existing, selected)) {
                    serverList.func_147413_a(i, selected); // ServerList.setServer
                    break;
                }
            }
            serverList.saveServerList();
        } catch (Exception e) {
            WawelAuth.LOG.warn("Failed to persist per-server account selection: {}", e.getMessage());
        }
    }

    /**
     * If the selected account does not exist anymore, clear this server binding
     * and persist the change.
     *
     * @return true if a stale binding was removed
     */
    public static boolean clearMissingBinding(ServerData serverData, AccountManager accountManager) {
        if (serverData == null || accountManager == null) return false;
        IServerDataExt ext = (IServerDataExt) serverData;
        long accountId = ext.getWawelAccountId();
        if (accountId < 0) return false;

        if (accountManager.getAccount(accountId) != null) {
            return false;
        }

        ext.setWawelAccountId(-1L);
        ext.setWawelProviderName(null);
        persistServerSelection(serverData);
        return true;
    }

    /**
     * Remove all server bindings whose account IDs do not exist anymore.
     *
     * @return number of bindings removed
     */
    public static int clearMissingAccountBindings(AccountManager accountManager) {
        if (accountManager == null) return 0;

        List<ClientAccount> accounts = accountManager.listAccounts();
        Set<Long> validIds = new HashSet<>();
        for (ClientAccount account : accounts) {
            validIds.add(account.getId());
        }

        try {
            ServerList serverList = new ServerList(Minecraft.getMinecraft());
            serverList.loadServerList();

            int removed = clearMissingInServerList(serverList, validIds, true);

            ServerList active = activeServerListRef.get();
            if (active != null && active != serverList) {
                removed += clearMissingInServerList(active, validIds, true);
            }
            return removed;
        } catch (Exception e) {
            WawelAuth.LOG.warn("Failed to clean stale server bindings: {}", e.getMessage());
            return 0;
        }
    }

    private static boolean sameServer(ServerData a, ServerData b) {
        if (a == null || b == null) return false;
        if (a.serverIP == null || b.serverIP == null) return false;
        return a.serverIP.equals(b.serverIP);
    }

    private static int clearMissingInServerList(ServerList serverList, Set<Long> validIds, boolean save) {
        int removed = 0;
        for (int i = 0; i < serverList.countServers(); i++) {
            ServerData serverData = serverList.getServerData(i);
            if (!(serverData instanceof IServerDataExt)) continue;

            IServerDataExt ext = (IServerDataExt) serverData;
            long accountId = ext.getWawelAccountId();
            if (accountId >= 0 && !validIds.contains(accountId)) {
                ext.setWawelAccountId(-1L);
                ext.setWawelProviderName(null);
                serverList.func_147413_a(i, serverData); // ServerList.setServer
                removed++;
            }
        }

        if (save && removed > 0) {
            serverList.saveServerList();
        }
        return removed;
    }
}
