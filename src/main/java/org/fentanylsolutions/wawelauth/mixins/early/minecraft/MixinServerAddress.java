package org.fentanylsolutions.wawelauth.mixins.early.minecraft;

import net.minecraft.client.multiplayer.ServerAddress;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerAddress.class)
public class MixinServerAddress {

    /**
     * Strip {@code http://} and {@code https://} scheme prefixes that vanilla
     * ServerAddress parsing cannot handle (it splits on {@code :} naively).
     */
    @ModifyVariable(method = "func_78860_a", at = @At("HEAD"), argsOnly = true)
    private static String wawelauth$stripScheme(String address) {
        if (address == null) return null;
        if (address.startsWith("http://")) return address.substring(7);
        if (address.startsWith("https://")) return address.substring(8);
        return address;
    }
}
