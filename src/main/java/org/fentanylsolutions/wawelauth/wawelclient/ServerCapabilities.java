package org.fentanylsolutions.wawelauth.wawelclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.fentanylsolutions.wawelauth.wawelcore.ping.WawelPingPayload;

import com.google.gson.JsonObject;

/**
 * Runtime-only ping capability snapshot for one server entry.
 * Never persisted to servers.dat.
 */
public final class ServerCapabilities {

    private final boolean wawelAuthAdvertised;
    private final boolean localAuthSupported;
    private final String localAuthApiRoot;
    private final String localAuthPublicKeyFingerprint;
    private final String localAuthPublicKeyBase64;
    private final List<String> localAuthSkinDomains;
    private final List<String> acceptedProviderNames;
    private final List<String> acceptedAuthServerUrls;
    private final long updatedAtMs;
    private final String rawPayloadJson;

    private ServerCapabilities(boolean wawelAuthAdvertised, boolean localAuthSupported, String localAuthApiRoot,
        String localAuthPublicKeyFingerprint, String localAuthPublicKeyBase64, List<String> localAuthSkinDomains,
        List<String> acceptedProviderNames, List<String> acceptedAuthServerUrls, long updatedAtMs,
        String rawPayloadJson) {
        this.wawelAuthAdvertised = wawelAuthAdvertised;
        this.localAuthSupported = localAuthSupported;
        this.localAuthApiRoot = localAuthApiRoot;
        this.localAuthPublicKeyFingerprint = localAuthPublicKeyFingerprint;
        this.localAuthPublicKeyBase64 = localAuthPublicKeyBase64;
        this.localAuthSkinDomains = localAuthSkinDomains;
        this.acceptedProviderNames = acceptedProviderNames;
        this.acceptedAuthServerUrls = acceptedAuthServerUrls;
        this.updatedAtMs = updatedAtMs;
        this.rawPayloadJson = rawPayloadJson;
    }

    public static ServerCapabilities empty() {
        return new ServerCapabilities(
            false,
            false,
            null,
            null,
            null,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            0L,
            null);
    }

    /**
     * No WawelAuth payload in ping response.
     * Provider set is unknown; do not assume Mojang/Microsoft compatibility.
     */
    public static ServerCapabilities unadvertised(long nowMs) {
        return new ServerCapabilities(
            false,
            false,
            null,
            null,
            null,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            nowMs,
            null);
    }

    public static ServerCapabilities fromPayload(JsonObject payload, long nowMs) {
        // Backward-compatible: older servers may still send provider names.
        List<String> names = toUnmodifiableCopy(WawelPingPayload.parseStringArray(payload, "acceptedProviderNames"));
        List<String> urls = toUnmodifiableCopy(
            WawelPingPayload.normalizeUrls(
                WawelPingPayload.parseStringArray(payload, WawelPingPayload.KEY_ACCEPTED_AUTH_SERVER_URLS)));

        String legacyApiRoot = WawelPingPayload.normalizeUrl(getString(payload, WawelPingPayload.KEY_API_ROOT));
        boolean legacyProvides = getBoolean(payload, WawelPingPayload.KEY_PROVIDES_YGGDRASIL_SERVICE, false);

        boolean localAuth = getBoolean(payload, WawelPingPayload.KEY_LOCAL_AUTH_SUPPORTED, legacyProvides);
        String localApiRoot = WawelPingPayload
            .normalizeUrl(getString(payload, WawelPingPayload.KEY_LOCAL_AUTH_API_ROOT));
        if (localApiRoot == null) {
            localApiRoot = legacyApiRoot;
        }
        String localFingerprint = normalizeFingerprint(
            getString(payload, WawelPingPayload.KEY_LOCAL_AUTH_PUBLIC_KEY_FINGERPRINT));
        String localPublicKeyBase64 = normalizeString(
            getString(payload, WawelPingPayload.KEY_LOCAL_AUTH_PUBLIC_KEY_BASE64));
        List<String> localSkinDomains = toUnmodifiableCopy(
            WawelPingPayload.parseStringArray(payload, WawelPingPayload.KEY_LOCAL_AUTH_SKIN_DOMAINS));

        boolean localDescriptorComplete = localApiRoot != null && localFingerprint != null;
        if (!localDescriptorComplete) {
            localAuth = false;
        }

        return new ServerCapabilities(
            true,
            localAuth,
            localApiRoot,
            localFingerprint,
            localPublicKeyBase64,
            localSkinDomains,
            names,
            urls,
            nowMs,
            payload == null ? null : payload.toString());
    }

    private static List<String> toUnmodifiableCopy(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    public boolean isWawelAuthAdvertised() {
        return wawelAuthAdvertised;
    }

    public boolean isLocalAuthSupported() {
        return localAuthSupported;
    }

    public String getLocalAuthApiRoot() {
        return localAuthApiRoot;
    }

    public String getLocalAuthPublicKeyFingerprint() {
        return localAuthPublicKeyFingerprint;
    }

    public String getLocalAuthPublicKeyBase64() {
        return localAuthPublicKeyBase64;
    }

    public List<String> getLocalAuthSkinDomains() {
        return localAuthSkinDomains;
    }

    public List<String> getAcceptedProviderNames() {
        return acceptedProviderNames;
    }

    public List<String> getAcceptedAuthServerUrls() {
        return acceptedAuthServerUrls;
    }

    public long getUpdatedAtMs() {
        return updatedAtMs;
    }

    public String getRawPayloadJson() {
        return rawPayloadJson;
    }

    private static String getString(JsonObject payload, String key) {
        if (payload == null || key == null
            || !payload.has(key)
            || payload.get(key)
                .isJsonNull()) {
            return null;
        }
        try {
            return payload.get(key)
                .getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean getBoolean(JsonObject payload, String key, boolean defaultValue) {
        if (payload == null || key == null || !payload.has(key)) {
            return defaultValue;
        }
        try {
            return payload.get(key)
                .getAsBoolean();
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static String normalizeString(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeFingerprint(String value) {
        String normalized = normalizeString(value);
        return normalized == null ? null : normalized.toLowerCase();
    }
}
