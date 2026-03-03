package org.fentanylsolutions.wawelauth.wawelcore.ping;

import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;

import org.fentanylsolutions.wawelauth.wawelcore.config.FallbackServer;
import org.fentanylsolutions.wawelauth.wawelcore.config.ServerConfig;
import org.fentanylsolutions.wawelauth.wawelcore.util.HexUtil;
import org.fentanylsolutions.wawelauth.wawelcore.util.StringUtil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Shared serializer/parser for WawelAuth capability data exchanged via
 * FentLib's server list ping extension.
 */
public final class WawelPingPayload {

    public static final int VERSION = 1;
    public static final String KEY_VERSION = "version";
    public static final String KEY_PROVIDES_YGGDRASIL_SERVICE = "providesYggdrasilService";
    public static final String KEY_API_ROOT = "apiRoot";
    public static final String KEY_ACCEPTED_AUTH_SERVER_URLS = "acceptedAuthServerUrls";
    public static final String KEY_LOCAL_AUTH_SUPPORTED = "localAuthSupported";
    public static final String KEY_LOCAL_AUTH_API_ROOT = "localAuthApiRoot";
    public static final String KEY_LOCAL_AUTH_PUBLIC_KEY_FINGERPRINT = "localAuthPublicKeyFingerprint";
    public static final String KEY_LOCAL_AUTH_PUBLIC_KEY_BASE64 = "localAuthPublicKeyBase64";
    public static final String KEY_LOCAL_AUTH_SKIN_DOMAINS = "localAuthSkinDomains";

    private WawelPingPayload() {}

    public static JsonObject buildServerPayload(ServerConfig config) {
        return buildServerPayload(config, null);
    }

    public static JsonObject buildServerPayload(ServerConfig config, String localPublicKeyBase64) {
        JsonObject payload = new JsonObject();
        payload.addProperty(KEY_VERSION, VERSION);

        if (config == null) {
            payload.addProperty(KEY_PROVIDES_YGGDRASIL_SERVICE, false);
            payload.addProperty(KEY_LOCAL_AUTH_SUPPORTED, false);
            payload.add(KEY_ACCEPTED_AUTH_SERVER_URLS, new JsonArray());
            payload.add(KEY_LOCAL_AUTH_SKIN_DOMAINS, new JsonArray());
            return payload;
        }

        boolean yggdrasilEnabled = config.isEnabled();
        payload.addProperty(KEY_PROVIDES_YGGDRASIL_SERVICE, yggdrasilEnabled);

        String apiRoot = normalizeUrl(config.getApiRoot());
        if (apiRoot != null) {
            payload.addProperty(KEY_API_ROOT, apiRoot);
        }

        String normalizedLocalKey = trimToNull(localPublicKeyBase64);
        String localFingerprint = computeKeyFingerprint(normalizedLocalKey);
        boolean localAuthSupported = yggdrasilEnabled && apiRoot != null && localFingerprint != null;
        payload.addProperty(KEY_LOCAL_AUTH_SUPPORTED, localAuthSupported);
        if (localAuthSupported) {
            payload.addProperty(KEY_LOCAL_AUTH_API_ROOT, apiRoot);
            payload.addProperty(KEY_LOCAL_AUTH_PUBLIC_KEY_FINGERPRINT, localFingerprint);
            payload.addProperty(KEY_LOCAL_AUTH_PUBLIC_KEY_BASE64, normalizedLocalKey);
            payload.add(KEY_LOCAL_AUTH_SKIN_DOMAINS, toJsonArray(config.getSkinDomains()));
        } else {
            payload.add(KEY_LOCAL_AUTH_SKIN_DOMAINS, new JsonArray());
        }

        LinkedHashSet<String> authServerUrls = new LinkedHashSet<>();

        if (yggdrasilEnabled) {
            if (apiRoot != null) {
                authServerUrls.add(normalizeUrl(apiRoot + "/authserver"));
            }
        }

        for (FallbackServer fallback : config.getFallbackServers()) {
            if (fallback == null) continue;

            String accountUrl = resolveFallbackAuthUrl(fallback);
            if (accountUrl != null) {
                authServerUrls.add(accountUrl);
            }
        }

        payload.add(KEY_ACCEPTED_AUTH_SERVER_URLS, toJsonArray(authServerUrls));
        return payload;
    }

    public static List<String> parseStringArray(JsonObject payload, String key) {
        List<String> values = new ArrayList<>();
        if (payload == null || key == null
            || !payload.has(key)
            || !payload.get(key)
                .isJsonArray()) {
            return values;
        }

        for (JsonElement element : payload.getAsJsonArray(key)) {
            if (element == null || !element.isJsonPrimitive()) continue;
            String value = trimToNull(element.getAsString());
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    public static List<String> normalizeUrls(List<String> urls) {
        List<String> result = new ArrayList<>();
        if (urls == null) return result;

        LinkedHashSet<String> dedup = new LinkedHashSet<>();
        for (String url : urls) {
            String normalized = normalizeUrl(url);
            if (normalized != null) {
                dedup.add(normalized);
            }
        }
        result.addAll(dedup);
        return result;
    }

    public static String normalizeUrl(String raw) {
        return StringUtil.stripTrailingSlashes(raw);
    }

    /**
     * Resolve the auth endpoint URL to advertise for a fallback entry.
     *
     * Rules:
     * 1. Prefer configured accountUrl.
     * 2. Treat legacy Mojang accountUrl (api.mojang.com) as authserver.mojang.com.
     * 3. If accountUrl missing and sessionServerUrl is "<root>/sessionserver", derive "<root>/authserver".
     */
    private static String resolveFallbackAuthUrl(FallbackServer fallback) {
        String accountUrl = normalizeUrl(fallback.getAccountUrl());
        String sessionUrl = normalizeUrl(fallback.getSessionServerUrl());

        // Legacy/incorrect Mojang fallback configs often use api.mojang.com.
        // For auth capability matching we need authserver.mojang.com.
        if (isHost(accountUrl, "api.mojang.com") || isHost(sessionUrl, "sessionserver.mojang.com")) {
            return "https://authserver.mojang.com";
        }

        if (accountUrl != null) {
            return accountUrl;
        }

        if (sessionUrl != null && sessionUrl.endsWith("/sessionserver")) {
            return sessionUrl.substring(0, sessionUrl.length() - "/sessionserver".length()) + "/authserver";
        }

        return null;
    }

    private static boolean isHost(String url, String expectedHost) {
        if (url == null) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return host != null && host.equalsIgnoreCase(expectedHost);
        } catch (Exception e) {
            return false;
        }
    }

    private static JsonArray toJsonArray(Iterable<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            if (value != null) {
                array.add(new JsonPrimitive(value));
            }
        }
        return array;
    }

    private static String computeKeyFingerprint(String base64Key) {
        if (base64Key == null || base64Key.isEmpty()) return null;
        try {
            byte[] derBytes = Base64.getDecoder()
                .decode(base64Key);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(derBytes);
            return HexUtil.bytesToHex(hash);
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        return StringUtil.trimToNull(value);
    }
}
