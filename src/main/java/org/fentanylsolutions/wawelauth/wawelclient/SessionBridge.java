package org.fentanylsolutions.wawelauth.wawelclient;

import java.io.IOException;
import java.net.URI;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;

import org.fentanylsolutions.wawelauth.WawelAuth;
import org.fentanylsolutions.wawelauth.mixins.early.minecraft.AccessorMinecraft;
import org.fentanylsolutions.wawelauth.wawelclient.data.AccountStatus;
import org.fentanylsolutions.wawelauth.wawelclient.data.ClientAccount;
import org.fentanylsolutions.wawelauth.wawelclient.data.ClientProvider;
import org.fentanylsolutions.wawelauth.wawelclient.http.YggdrasilHttpClient;
import org.fentanylsolutions.wawelauth.wawelclient.http.YggdrasilRequestException;
import org.fentanylsolutions.wawelauth.wawelclient.storage.ClientAccountDAO;
import org.fentanylsolutions.wawelauth.wawelclient.storage.ClientProviderDAO;
import org.fentanylsolutions.wawelauth.wawelcore.data.UuidUtil;
import org.fentanylsolutions.wawelauth.wawelcore.ping.WawelPingPayload;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.exceptions.InvalidCredentialsException;
import com.mojang.authlib.properties.Property;

/**
 * Coordinates between the WawelAuth client account system and
 * vanilla Minecraft's authentication/session infrastructure.
 *
 * Handles: session swapping, joinServer redirection, connection-scoped
 * texture signature verification, skin domain whitelisting, profile
 * property fetching from custom providers, and auto-import of the
 * launcher session.
 */
public class SessionBridge {

    private static final String MOJANG_PROVIDER_NAME = "Mojang";
    private static final String[] VANILLA_SKIN_DOMAINS = { ".minecraft.net", ".mojang.com" };

    private final YggdrasilHttpClient httpClient;
    private final ClientProviderDAO providerDAO;
    private final ClientAccountDAO accountDAO;
    private final AccountManager accountManager;
    private final ExecutorService profileFetchExecutor;

    /** The launcher's original session, captured at construction. */
    private final Session launcherSession;

    private volatile ClientAccount activeAccount;
    private volatile ClientProvider activeProvider;
    private volatile String connectedSessionServerBase;
    private volatile List<ClientProvider> trustedProviders = Collections.emptyList();
    private volatile List<PublicKey> trustedKeys = Collections.emptyList();
    private final ConcurrentHashMap<String, GameProfile> profileCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> profileFetchInFlight = new ConcurrentHashMap<>();

    public SessionBridge(YggdrasilHttpClient httpClient, ClientProviderDAO providerDAO, ClientAccountDAO accountDAO,
        AccountManager accountManager) {
        this.httpClient = httpClient;
        this.providerDAO = providerDAO;
        this.accountDAO = accountDAO;
        this.accountManager = accountManager;
        this.launcherSession = Minecraft.getMinecraft()
            .getSession();
        this.profileFetchExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "WawelAuth-ProfileFetch");
            t.setDaemon(true);
            return t;
        });
    }

    // =========================================================================
    // Account activation / session swap
    // =========================================================================

    /**
     * Swap the Minecraft session to the given account.
     * Builds the trusted provider list for the current connection.
     */
    public void activateAccount(long accountId) {
        clearActiveAccount();

        ClientAccount account = accountDAO.findById(accountId);
        if (account == null) {
            WawelAuth.LOG.warn("Cannot activate account {}: not found", accountId);
            return;
        }
        if (account.getStatus() == AccountStatus.EXPIRED) {
            WawelAuth.LOG.warn(
                "Cannot activate account {} ({}): token expired, re-authentication required",
                accountId,
                account.getProfileName());
            return;
        }

        ClientProvider provider = providerDAO.findByName(account.getProviderName());
        if (provider == null) {
            WawelAuth.LOG
                .warn("Cannot activate account {}: provider '{}' not found", accountId, account.getProviderName());
            return;
        }

        if (account.getProfileUuid() == null || account.getProfileName() == null) {
            WawelAuth.LOG.warn("Cannot activate account {}: no profile bound", accountId);
            return;
        }

        String token = accountManager.getUsableAccessToken(account);

        // Build new session
        Session newSession = new Session(
            account.getProfileName(),
            account.getProfileUuid()
                .toString(),
            token,
            "mojang");

        // Swap session
        ((AccessorMinecraft) Minecraft.getMinecraft()).wawelauth$setSession(newSession);

        this.activeAccount = account;
        this.activeProvider = provider;

        // Build trusted providers: Mojang + active provider (deduplicated)
        buildTrustedProviders(provider);

        WawelAuth
            .debug("Activated account '" + account.getProfileName() + "' on provider '" + provider.getName() + "'");
    }

    /** Clear active account state and restore the launcher's original session. */
    public void clearActiveAccount() {
        this.activeAccount = null;
        this.activeProvider = null;
        this.connectedSessionServerBase = null;
        this.trustedProviders = Collections.emptyList();
        this.trustedKeys = Collections.emptyList();
        this.profileCache.clear();
        this.profileFetchInFlight.clear();
        ((AccessorMinecraft) Minecraft.getMinecraft()).wawelauth$setSession(launcherSession);
    }

    /**
     * Remove all cached profile entries for the given UUID so the next
     * {@link #fillProfileFromProvider} call fetches a fresh profile from
     * the server. Useful after skin/cape uploads.
     */
    public void invalidateProfileCache(UUID profileId) {
        if (profileId == null) return;
        String fragment = "|" + UuidUtil.toUnsigned(profileId) + "|";
        profileCache.keySet()
            .removeIf(key -> key.contains(fragment));
    }

    /** Stop background resources used by session/profile bridging. */
    public void shutdown() {
        profileFetchExecutor.shutdownNow();
    }

    public boolean hasActiveAccount() {
        return activeAccount != null && activeProvider != null;
    }

    public ClientProvider getActiveProvider() {
        return activeProvider;
    }

    public ClientAccount getActiveAccount() {
        return activeAccount;
    }

    /**
     * Set trusted providers for the current connection.
     * Called by capability detection (Step 6/7) with server-accepted providers.
     */
    public void setTrustedProviders(List<ClientProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            this.trustedProviders = Collections.emptyList();
            this.trustedKeys = Collections.emptyList();
            return;
        }

        this.trustedProviders = new ArrayList<>(providers);
        this.trustedKeys = parsePublicKeys(this.trustedProviders);
    }

    /**
     * Build connection-trusted providers from live server capabilities.
     *
     * Priority:
     * 1) Active account provider (always)
     * 2) Mojang provider (always, when present)
     * 3) Providers matching server-accepted auth URLs
     * 4) Providers matching advertised local-auth fingerprint
     * 5) Ephemeral local provider from capabilities (if no persisted match)
     */
    public void applyServerCapabilities(ServerCapabilities capabilities) {
        ClientProvider active = this.activeProvider;
        if (active == null) {
            return;
        }
        this.connectedSessionServerBase = resolveConnectedSessionServerBase(capabilities);

        List<ClientProvider> resolved = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        addTrustedProvider(resolved, seen, providerDAO.findByName(MOJANG_PROVIDER_NAME));
        addTrustedProvider(resolved, seen, active);

        if (capabilities != null) {
            Set<String> acceptedAuthUrls = new HashSet<>();
            Set<String> acceptedAuthHosts = new HashSet<>();
            for (String url : capabilities.getAcceptedAuthServerUrls()) {
                String normalized = WawelPingPayload.normalizeUrl(url);
                if (normalized != null) {
                    acceptedAuthUrls.add(normalized);
                    String host = extractHost(normalized);
                    if (host != null) {
                        acceptedAuthHosts.add(host);
                    }
                }
            }

            String localFingerprint = normalizeFingerprint(capabilities.getLocalAuthPublicKeyFingerprint());

            for (ClientProvider provider : providerDAO.listAll()) {
                String providerAuthUrl = WawelPingPayload.normalizeUrl(provider.getAuthServerUrl());
                String providerFingerprint = normalizeFingerprint(provider.getPublicKeyFingerprint());
                boolean matchedByHost = matchesAnyAcceptedHost(acceptedAuthHosts, extractHost(providerAuthUrl))
                    || matchesAnyAcceptedHost(acceptedAuthHosts, extractHost(provider.getSessionServerUrl()))
                    || matchesAnyAcceptedHost(acceptedAuthHosts, extractHost(provider.getApiRoot()));

                if ((providerAuthUrl != null && acceptedAuthUrls.contains(providerAuthUrl)) || matchedByHost
                    || (localFingerprint != null && localFingerprint.equals(providerFingerprint))) {
                    addTrustedProvider(resolved, seen, provider);
                }
            }

            addTrustedProvider(resolved, seen, buildEphemeralLocalProvider(capabilities));
        }

        setTrustedProviders(resolved);
    }

    /**
     * Resolve a single account candidate based on runtime server capabilities.
     *
     * Returns:
     * - account id, if exactly one local account matches server-accepted auth endpoints
     * - -1, if none or multiple match
     *
     * Does not persist anything to ServerData/NBT.
     */
    public long findSingleMatchingAccountId(ServerCapabilities capabilities) {
        if (capabilities == null) return -1L;

        String acceptedFingerprint = normalizeFingerprint(capabilities.getLocalAuthPublicKeyFingerprint());

        Set<String> acceptedAuthUrls = new HashSet<>();
        for (String url : capabilities.getAcceptedAuthServerUrls()) {
            String normalized = WawelPingPayload.normalizeUrl(url);
            if (normalized != null) {
                acceptedAuthUrls.add(normalized);
            }
        }

        Set<String> acceptedProviderNames = new HashSet<>();
        for (String providerName : capabilities.getAcceptedProviderNames()) {
            if (providerName != null && !providerName.trim()
                .isEmpty()) {
                acceptedProviderNames.add(
                    providerName.trim()
                        .toLowerCase());
            }
        }

        if (acceptedFingerprint == null && acceptedAuthUrls.isEmpty() && acceptedProviderNames.isEmpty()) {
            return -1L;
        }

        long selectedId = -1L;
        int matches = 0;

        for (ClientAccount account : accountDAO.listAll()) {
            // EXPIRED always requires user action.
            if (account.getStatus() == AccountStatus.EXPIRED) {
                continue;
            }

            ClientProvider provider = providerDAO.findByName(account.getProviderName());
            if (provider == null) {
                continue;
            }

            boolean matched = false;
            String providerAuthUrl = WawelPingPayload.normalizeUrl(provider.getAuthServerUrl());
            String providerFingerprint = normalizeFingerprint(provider.getPublicKeyFingerprint());

            if (acceptedFingerprint != null && acceptedFingerprint.equals(providerFingerprint)) {
                matched = true;
            } else if (providerAuthUrl != null && acceptedAuthUrls.contains(providerAuthUrl)) {
                matched = true;
            } else if (!acceptedProviderNames.isEmpty()) {
                // Backward-compat fallback for older payloads that only expose names.
                String providerName = provider.getName();
                if (providerName != null && acceptedProviderNames.contains(
                    providerName.trim()
                        .toLowerCase())) {
                    matched = true;
                }
            }

            if (!matched) {
                continue;
            }

            matches++;
            if (matches == 1) {
                selectedId = account.getId();
            } else {
                return -1L;
            }
        }

        return matches == 1 ? selectedId : -1L;
    }

    // =========================================================================
    // joinServer: redirect to active provider
    // =========================================================================

    /**
     * POST joinServer to the active provider's session URL.
     * Throws authlib exception types matching vanilla's catch blocks in
     * NetHandlerLoginClient.handleEncryptionRequest.
     */
    public void joinServer(GameProfile profile, String token, String serverId) throws AuthenticationException {
        ClientProvider provider = this.activeProvider;
        if (provider == null) {
            throw new AuthenticationException("No active WawelAuth provider");
        }

        JsonObject body = new JsonObject();
        body.addProperty("accessToken", token);
        body.addProperty("selectedProfile", UuidUtil.toUnsigned(profile.getId()));
        body.addProperty("serverId", serverId);

        try {
            httpClient.postJson(provider.sessionUrl("/session/minecraft/join"), body);
            WawelAuth.debug("joinServer succeeded for provider '" + provider.getName() + "'");
        } catch (YggdrasilRequestException e) {
            // If the selected provider is local and its API module is disabled/unavailable,
            // don't surface "auth servers down" from the client path. Continue so the
            // dedicated server performs its normal username verification and rejects with
            // "Failed to verify username!".
            if (isLikelyLocalProvider(provider) && isLikelyDisabledLocalApiStatus(e.getHttpStatus())) {
                WawelAuth.debug(
                    "Local provider join endpoint returned HTTP " + e.getHttpStatus()
                        + ", continuing to server-side verification");
                return;
            }
            if (e.getHttpStatus() == 403) {
                throw new InvalidCredentialsException(e.getErrorMessage());
            }
            throw new AuthenticationException(e.getErrorMessage());
        } catch (IOException e) {
            if (isLikelyLocalProvider(provider)) {
                WawelAuth.debug(
                    "Local provider join endpoint unavailable (" + e.getClass()
                        .getSimpleName() + "), continuing to server-side verification");
                return;
            }
            throw new AuthenticationUnavailableException(e);
        }
    }

    // =========================================================================
    // Texture verification: connection-scoped
    // =========================================================================

    /** Get all trusted public keys for the current connection. */
    public List<PublicKey> getTrustedPublicKeys() {
        return trustedKeys;
    }

    /**
     * Public keys used for texture signature verification.
     *
     * During an active server session, uses connection-trusted keys only.
     * Outside session context (menus/tooltips), falls back to all configured
     * provider keys so local UI integrations (e.g. TabFaces) can resolve
     * custom-provider textures without signature spam.
     */
    public List<PublicKey> getTextureVerificationKeys() {
        List<PublicKey> scoped = trustedKeys;
        // Use connection-scoped keys only while actually connected.
        // On the menu, trustedKeys may be stale from the last session;
        // fall back to all configured provider keys.
        if (scoped != null && !scoped.isEmpty() && Minecraft.getMinecraft().theWorld != null) {
            return scoped;
        }
        return parsePublicKeys(providerDAO.listAll());
    }

    /**
     * Check if a URL's domain is whitelisted by any trusted provider
     * for the current connection. Also checks vanilla Mojang domains.
     */
    public boolean isWhitelistedDomain(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return false;

            // Check vanilla Mojang domains
            for (String domain : VANILLA_SKIN_DOMAINS) {
                if (host.endsWith(domain)) return true;
            }

            List<ClientProvider> providers = trustedProviders;
            // Use connection-scoped providers only while actually connected.
            // On the menu, trustedProviders may be stale from the last session;
            // fall back to all configured providers.
            if (providers == null || providers.isEmpty() || Minecraft.getMinecraft().theWorld == null) {
                providers = providerDAO.listAll();
            }

            // Check provider skin domains (connection-trusted in-session,
            // all configured providers in UI/off-session contexts).
            for (ClientProvider provider : providers) {
                List<String> domains = provider.getSkinDomainList();
                for (String domain : domains) {
                    if (domain.startsWith(".")) {
                        if (host.endsWith(domain)) return true;
                    } else {
                        if (host.equals(domain)) return true;
                    }
                }
            }
        } catch (Exception e) {
            WawelAuth.debug("Error checking whitelisted domain for URL '" + url + "': " + e.getMessage());
        }
        return false;
    }

    // =========================================================================
    // Profile property fetching: active provider context
    // =========================================================================

    /**
     * Fetch profile properties from the active provider's session server.
     * Uses active provider when present; otherwise resolves provider by stored
     * local account profile UUID.
     *
     * Returns null only when no suitable provider can be determined
     * (caller should fall through to vanilla).
     */
    public GameProfile fillProfileFromProvider(GameProfile profile, boolean requireSecure) {
        if (profile == null || profile.getId() == null) {
            return null;
        }

        String connectedSessionBase = this.connectedSessionServerBase;
        if (connectedSessionBase != null && Minecraft.getMinecraft().theWorld != null) {
            String cacheKey = buildProfileCacheKey("server:" + connectedSessionBase, profile.getId(), requireSecure);
            GameProfile cached = profileCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }

            if (Minecraft.getMinecraft()
                .func_152345_ab()) {
                queueProfileFetchFromSessionBase(connectedSessionBase, profile, requireSecure, cacheKey);
                return profile;
            }

            GameProfile fetchedFromServer = fetchProfileFromSessionServer(connectedSessionBase, profile, requireSecure);
            if (hasProperties(fetchedFromServer)) {
                profileCache.put(cacheKey, fetchedFromServer);
                String altKey = buildProfileCacheKey("server:" + connectedSessionBase, profile.getId(), !requireSecure);
                profileCache.putIfAbsent(altKey, fetchedFromServer);
                return fetchedFromServer;
            }
        }

        ClientProvider provider = this.activeProvider;
        // Only use activeProvider when actually connected to a server.
        // After disconnect, activeProvider may still be set (stale); fall
        // through to per-profile resolution so each account uses its own provider.
        if (provider != null && Minecraft.getMinecraft().theWorld == null) {
            provider = null;
        }
        if (provider == null) {
            provider = resolveProviderForProfile(profile != null ? profile.getId() : null);
        }
        if (provider == null) {
            return null;
        }

        String cacheKey = buildProfileCacheKey(provider, profile.getId(), requireSecure);
        GameProfile cached = profileCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Never block the client thread on remote profile I/O.
        if (Minecraft.getMinecraft()
            .func_152345_ab()) { // Minecraft.isCallingFromMinecraftThread
            queueProfileFetch(provider, profile, requireSecure, cacheKey);
            return profile;
        }

        GameProfile fetched = fetchProfileFromProvider(provider, profile, requireSecure);
        if (hasProperties(fetched)) {
            profileCache.put(cacheKey, fetched);
            // Also populate the opposite signed/unsigned key so that concurrent
            // callers (e.g. SkinManager with requireSecure=false vs TabFaces with
            // requireSecure=true) don't each fire their own HTTP request.
            String altKey = buildProfileCacheKey(provider, profile.getId(), !requireSecure);
            profileCache.putIfAbsent(altKey, fetched);
        }
        return fetched;
    }

    private ClientProvider resolveProviderForProfile(UUID profileId) {
        if (profileId == null) {
            return null;
        }

        List<ClientAccount> accounts = accountDAO.listAll();
        for (ClientAccount account : accounts) {
            if (account == null || account.getProfileUuid() == null) {
                continue;
            }
            if (!profileId.equals(account.getProfileUuid())) {
                continue;
            }
            String providerName = account.getProviderName();
            if (providerName == null || providerName.trim()
                .isEmpty()) {
                continue;
            }
            ClientProvider provider = providerDAO.findByName(providerName);
            if (provider != null) {
                return provider;
            }
        }
        return null;
    }

    // =========================================================================
    // Auto-import launcher session
    // =========================================================================

    /**
     * Inspect the launcher's Minecraft session and auto-import it as a
     * WawelAuth account under the Mojang provider if the token is valid.
     * Called once from WawelClient.start().
     */
    public void tryImportLauncherSession() {
        try {
            Session session = Minecraft.getMinecraft()
                .getSession();
            String token = session.getToken();

            // Skip invalid/dev sessions
            if (token == null || token.isEmpty()
                || "NotValid".equals(token)
                || "0".equals(token)
                || "FML".equals(token)) {
                WawelAuth.debug("Launcher session token is not valid, skipping auto-import");
                return;
            }

            ClientProvider mojang = providerDAO.findByName(MOJANG_PROVIDER_NAME);
            if (mojang == null) {
                WawelAuth.debug("Mojang provider not found, skipping launcher session import");
                return;
            }

            // Validate token against Mojang
            JsonObject validateBody = new JsonObject();
            validateBody.addProperty("accessToken", token);

            try {
                httpClient.postJson(mojang.authUrl("/validate"), validateBody);
            } catch (YggdrasilRequestException e) {
                WawelAuth.debug("Launcher session token validation failed: " + e.getMessage());
                return;
            } catch (IOException e) {
                WawelAuth.debug("Could not reach Mojang for session validation: " + e.getMessage());
                return;
            }

            // Token is valid: import as account
            String playerIdStr = session.getPlayerID();
            UUID profileUuid;
            try {
                profileUuid = UUID.fromString(playerIdStr);
            } catch (IllegalArgumentException e) {
                // playerID might be unsigned
                profileUuid = UuidUtil.fromUnsigned(playerIdStr);
            }

            String username = session.getUsername();

            // Check if account already exists
            ClientAccount existing = accountDAO.findByProviderAndProfile(mojang.getName(), profileUuid);
            if (existing != null) {
                WawelAuth.debug("Mojang account '" + username + "' already exists, skipping import");
                return;
            }

            // Create new account
            long now = System.currentTimeMillis();
            ClientAccount account = new ClientAccount();
            account.setProviderName(mojang.getName());
            account.setUserUuid(UuidUtil.toUnsigned(profileUuid));
            account.setProfileUuid(profileUuid);
            account.setProfileName(username);
            account.setAccessToken(token);
            account.setClientToken(null);
            account.setStatus(AccountStatus.VALID);
            account.setConsecutiveFailures(0);
            account.setCreatedAt(now);
            account.setLastValidatedAt(now);
            account.setTokenIssuedAt(now);

            long id = accountDAO.create(account);
            account.setId(id);
            accountManager.cacheStatus(id, AccountStatus.VALID);

            WawelAuth.LOG.info("Auto-imported launcher session as Mojang account: {}", username);

        } catch (Exception e) {
            WawelAuth.LOG.warn("Failed to auto-import launcher session: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private void buildTrustedProviders(ClientProvider activeProvider) {
        List<ClientProvider> providers = new ArrayList<>();

        // Always include Mojang
        if (!MOJANG_PROVIDER_NAME.equals(activeProvider.getName())) {
            ClientProvider mojang = providerDAO.findByName(MOJANG_PROVIDER_NAME);
            if (mojang != null) {
                providers.add(mojang);
            }
        }

        providers.add(activeProvider);

        this.trustedProviders = providers;
        this.trustedKeys = parsePublicKeys(providers);
    }

    private static void addTrustedProvider(List<ClientProvider> providers, Set<String> seen, ClientProvider provider) {
        if (provider == null) return;

        String identity = providerIdentity(provider);
        if (!seen.add(identity)) {
            return;
        }
        providers.add(provider);
    }

    private static String providerIdentity(ClientProvider provider) {
        String name = provider.getName();
        if (name != null && !name.trim()
            .isEmpty()) {
            return "name:" + name.trim()
                .toLowerCase();
        }

        String authUrl = WawelPingPayload.normalizeUrl(provider.getAuthServerUrl());
        if (authUrl != null) {
            return "auth:" + authUrl.toLowerCase();
        }

        String fingerprint = normalizeFingerprint(provider.getPublicKeyFingerprint());
        if (fingerprint != null) {
            return "fp:" + fingerprint;
        }

        return "anon:" + System.identityHashCode(provider);
    }

    private static String resolveConnectedSessionServerBase(ServerCapabilities capabilities) {
        if (capabilities == null || !capabilities.isWawelAuthAdvertised()) {
            return null;
        }
        String apiRoot = WawelPingPayload.normalizeUrl(capabilities.getLocalAuthApiRoot());
        if (apiRoot == null) {
            return null;
        }
        return apiRoot + "/sessionserver";
    }

    private static String extractHost(String rawUrl) {
        if (rawUrl == null || rawUrl.trim()
            .isEmpty()) return null;
        try {
            String host = new URI(rawUrl).getHost();
            if (host == null) return null;
            return host.toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean matchesAnyAcceptedHost(Set<String> acceptedHosts, String candidateHost) {
        if (candidateHost == null || acceptedHosts == null || acceptedHosts.isEmpty()) {
            return false;
        }
        for (String accepted : acceptedHosts) {
            if (hostsEquivalent(accepted, candidateHost)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hostsEquivalent(String leftHost, String rightHost) {
        if (leftHost == null || rightHost == null) return false;
        String left = leftHost.toLowerCase();
        String right = rightHost.toLowerCase();
        if (left.equals(right)) return true;

        // For IP literals require exact match.
        if (isIpLiteral(left) || isIpLiteral(right)) {
            return false;
        }

        String leftDomain = registrableDomain(left);
        String rightDomain = registrableDomain(right);
        return leftDomain != null && leftDomain.equals(rightDomain);
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("^[0-9.]+$") || host.contains(":");
    }

    private static String registrableDomain(String host) {
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return host;
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static ClientProvider buildEphemeralLocalProvider(ServerCapabilities capabilities) {
        if (capabilities == null || !capabilities.isLocalAuthSupported()) {
            return null;
        }

        String apiRoot = WawelPingPayload.normalizeUrl(capabilities.getLocalAuthApiRoot());
        if (apiRoot == null) {
            return null;
        }

        ClientProvider provider = new ClientProvider();
        String fingerprint = normalizeFingerprint(capabilities.getLocalAuthPublicKeyFingerprint());
        provider.setName("local@" + (fingerprint == null ? "runtime" : fingerprint));
        provider.setApiRoot(apiRoot);
        provider.setAuthServerUrl(apiRoot + "/authserver");
        provider.setSessionServerUrl(apiRoot + "/sessionserver");
        provider.setServicesUrl(apiRoot);
        provider.setPublicKeyFingerprint(fingerprint);

        String publicKeyBase64 = capabilities.getLocalAuthPublicKeyBase64();
        if (publicKeyBase64 != null) {
            String trimmed = publicKeyBase64.trim();
            if (!trimmed.isEmpty()) {
                provider.setPublicKeyBase64(trimmed);
            }
        }

        List<String> skinDomains = capabilities.getLocalAuthSkinDomains();
        if (skinDomains != null && !skinDomains.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String domain : skinDomains) {
                if (domain != null) {
                    String trimmed = domain.trim();
                    if (!trimmed.isEmpty()) {
                        arr.add(new JsonPrimitive(trimmed));
                    }
                }
            }
            provider.setSkinDomains(arr.toString());
        }

        return provider;
    }

    private static List<PublicKey> parsePublicKeys(List<ClientProvider> providers) {
        List<PublicKey> keys = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ClientProvider provider : providers) {
            String keyBase64 = provider.getPublicKeyBase64();
            if (keyBase64 == null || keyBase64.isEmpty()) continue;
            if (!seen.add(keyBase64)) continue;
            try {
                byte[] keyBytes = Base64.getDecoder()
                    .decode(keyBase64);
                X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                keys.add(kf.generatePublic(spec));
            } catch (Exception e) {
                WawelAuth
                    .debug("Failed to parse public key for provider '" + provider.getName() + "': " + e.getMessage());
            }
        }
        return keys;
    }

    private static boolean isLikelyDisabledLocalApiStatus(int status) {
        return status == 400 || status == 404
            || status == 405
            || status == 500
            || status == 501
            || status == 502
            || status == 503;
    }

    private static boolean isLikelyLocalProvider(ClientProvider provider) {
        if (provider == null || provider.getApiRoot() == null) {
            return false;
        }
        try {
            URI uri = new URI(provider.getApiRoot());
            String host = uri.getHost();
            if (host == null) return false;
            if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
                return true;
            }
            // RFC1918 / loopback v4 ranges often used for local/LAN auth hosts.
            if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("127.")) {
                return true;
            }
            if (host.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..*")) {
                return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalizeFingerprint(String fingerprint) {
        if (fingerprint == null) return null;
        String trimmed = fingerprint.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase();
    }

    private void queueProfileFetch(ClientProvider provider, GameProfile profile, boolean requireSecure,
        String cacheKey) {
        if (profileFetchInFlight.putIfAbsent(cacheKey, Boolean.TRUE) != null) {
            return;
        }

        // Snapshot provider name to avoid caching stale results after server switches.
        final String providerName = provider.getName();
        final UUID profileId = profile.getId();
        final String profileName = profile.getName();

        profileFetchExecutor.submit(() -> {
            try {
                GameProfile probe = new GameProfile(profileId, profileName);
                GameProfile fetched = fetchProfileFromProvider(provider, probe, requireSecure);
                if (hasProperties(fetched)) {
                    ClientProvider current = this.activeProvider;
                    if (current != null && providerName.equals(current.getName())) {
                        profileCache.put(cacheKey, fetched);
                        String altKey = buildProfileCacheKey(provider, profileId, !requireSecure);
                        profileCache.putIfAbsent(altKey, fetched);
                    }
                }
            } catch (Exception e) {
                WawelAuth.debug("Background profile fetch failed: " + e.getMessage());
            } finally {
                profileFetchInFlight.remove(cacheKey);
            }
        });
    }

    private void queueProfileFetchFromSessionBase(String sessionBase, GameProfile profile, boolean requireSecure,
        String cacheKey) {
        if (profileFetchInFlight.putIfAbsent(cacheKey, Boolean.TRUE) != null) {
            return;
        }

        final String sessionBaseSnapshot = sessionBase;
        final UUID profileId = profile.getId();
        final String profileName = profile.getName();

        profileFetchExecutor.submit(() -> {
            try {
                GameProfile probe = new GameProfile(profileId, profileName);
                GameProfile fetched = fetchProfileFromSessionServer(sessionBaseSnapshot, probe, requireSecure);
                if (hasProperties(fetched)) {
                    String current = this.connectedSessionServerBase;
                    if (current != null && current.equals(sessionBaseSnapshot)) {
                        profileCache.put(cacheKey, fetched);
                        String altKey = buildProfileCacheKey(
                            "server:" + sessionBaseSnapshot,
                            profileId,
                            !requireSecure);
                        profileCache.putIfAbsent(altKey, fetched);
                    }
                }
            } catch (Exception e) {
                WawelAuth.debug("Background server profile fetch failed: " + e.getMessage());
            } finally {
                profileFetchInFlight.remove(cacheKey);
            }
        });
    }

    private static String buildProfileCacheKey(ClientProvider provider, UUID profileId, boolean requireSecure) {
        String providerKey = provider.getName();
        if (providerKey == null || providerKey.trim()
            .isEmpty()) {
            providerKey = WawelPingPayload.normalizeUrl(provider.getSessionServerUrl());
        }
        if (providerKey == null || providerKey.trim()
            .isEmpty()) {
            providerKey = "provider@" + System.identityHashCode(provider);
        }
        return buildProfileCacheKey("provider:" + providerKey, profileId, requireSecure);
    }

    private static String buildProfileCacheKey(String sourceKey, UUID profileId, boolean requireSecure) {
        return sourceKey + "|" + UuidUtil.toUnsigned(profileId) + "|" + (requireSecure ? "S" : "U");
    }

    private static boolean hasProperties(GameProfile profile) {
        return profile != null && !profile.getProperties()
            .isEmpty();
    }

    private GameProfile fetchProfileFromProvider(ClientProvider provider, GameProfile profile, boolean requireSecure) {
        try {
            String uuid = UuidUtil.toUnsigned(profile.getId());
            String url = provider.sessionUrl("/session/minecraft/profile/" + uuid);
            if (requireSecure) {
                url += "?unsigned=false";
            }

            JsonObject response = httpClient.getJson(url);
            return buildProfileFromJson(profile, response);
        } catch (Exception e) {
            WawelAuth.debug(
                "Failed to fill profile properties from provider '" + provider.getName() + "': " + e.getMessage());
            if (MOJANG_PROVIDER_NAME.equals(provider.getName())) {
                return null; // Mojang: fall through to vanilla (same endpoint)
            }
            // Non-Mojang: re-throw so the exception propagates through the mixin
            // (prevents vanilla fallthrough) and callers like TabFaces see null → retry.
            throw new RuntimeException("Provider profile fetch failed: " + provider.getName(), e);
        }
    }

    private GameProfile fetchProfileFromSessionServer(String sessionBase, GameProfile profile, boolean requireSecure) {
        try {
            String uuid = UuidUtil.toUnsigned(profile.getId());
            String base = WawelPingPayload.normalizeUrl(sessionBase);
            if (base == null) return null;

            String url = base + "/session/minecraft/profile/" + uuid;
            if (requireSecure) {
                url += "?unsigned=false";
            }

            JsonObject response = httpClient.getJson(url);
            return buildProfileFromJson(profile, response);
        } catch (Exception e) {
            WawelAuth.debug("Failed to fill profile properties from connected server: " + e.getMessage());
            return null;
        }
    }

    private static GameProfile buildProfileFromJson(GameProfile original, JsonObject response) {
        if (original == null || response == null) return null;

        UUID profileId = original.getId();
        String name = response.has("name") ? response.get("name")
            .getAsString() : original.getName();
        GameProfile filled = new GameProfile(profileId, name);

        if (response.has("properties") && response.get("properties")
            .isJsonArray()) {
            JsonArray props = response.getAsJsonArray("properties");
            for (JsonElement elem : props) {
                JsonObject prop = elem.getAsJsonObject();
                String propName = prop.get("name")
                    .getAsString();
                String propValue = prop.get("value")
                    .getAsString();
                if (prop.has("signature") && !prop.get("signature")
                    .isJsonNull()) {
                    filled.getProperties()
                        .put(
                            propName,
                            new Property(
                                propName,
                                propValue,
                                prop.get("signature")
                                    .getAsString()));
                } else {
                    filled.getProperties()
                        .put(propName, new Property(propName, propValue));
                }
            }
        }

        return filled;
    }
}
