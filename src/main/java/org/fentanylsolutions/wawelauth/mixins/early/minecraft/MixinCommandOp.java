package org.fentanylsolutions.wawelauth.mixins.early.minecraft;

import net.minecraft.command.server.CommandOp;
import net.minecraft.server.management.PlayerProfileCache;

import org.fentanylsolutions.wawelauth.wawelserver.FallbackWhitelistLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.authlib.GameProfile;

/**
 * Supports provider-qualified op adds:
 * /op <username>@<fallbackName>
 */
@Mixin(CommandOp.class)
public class MixinCommandOp {

    @Redirect(
        method = "processCommand",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/management/PlayerProfileCache;func_152655_a(Ljava/lang/String;)Lcom/mojang/authlib/GameProfile;")) // PlayerProfileCache.getGameProfileForUsername
    private GameProfile wawelauth$resolveQualifiedOpEntry(PlayerProfileCache profileCache, String rawInput) {
        if (FallbackWhitelistLookup.isQualifiedProviderUsername(rawInput)) {
            GameProfile resolved = FallbackWhitelistLookup.resolveQualifiedProfile(rawInput);
            if (resolved != null) {
                return resolved;
            }
        }

        // Enforce provider-qualified syntax only for op targets.
        return null;
    }
}
