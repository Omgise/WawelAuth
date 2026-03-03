package org.fentanylsolutions.wawelauth.wawelserver;

import org.fentanylsolutions.fentlib.services.S00PacketServerInfoModifyService;
import org.fentanylsolutions.wawelauth.Config;
import org.fentanylsolutions.wawelauth.WawelAuth;
import org.fentanylsolutions.wawelauth.wawelcore.ping.WawelPingPayload;

import com.google.gson.JsonObject;

/**
 * Registers server-side ping capability advertisement via FentLib.
 */
public final class WawelPingServerHooks {

    private static volatile boolean registered;

    private WawelPingServerHooks() {}

    public static synchronized void register() {
        if (registered) return;

        S00PacketServerInfoModifyService.registerHandler((response, fentLibPresent) -> {
            if (!fentLibPresent) {
                return null;
            }

            String publicKeyBase64 = null;
            WawelServer server = WawelServer.instance();
            if (server != null && server.getKeyManager() != null) {
                publicKeyBase64 = server.getKeyManager()
                    .getPublicKeyBase64();
            }

            JsonObject payload = WawelPingPayload.buildServerPayload(Config.server(), publicKeyBase64);
            return new S00PacketServerInfoModifyService.KeyValue(WawelAuth.MODID, payload);
        });

        registered = true;
        WawelAuth.debug("Registered WawelAuth server ping capability handler");
    }
}
