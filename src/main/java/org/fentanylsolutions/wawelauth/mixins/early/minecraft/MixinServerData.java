package org.fentanylsolutions.wawelauth.mixins.early.minecraft;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.NBTTagCompound;

import org.fentanylsolutions.wawelauth.wawelclient.IServerDataExt;
import org.fentanylsolutions.wawelauth.wawelclient.ServerCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerData.class)
public class MixinServerData implements IServerDataExt {

    @Unique
    private long wawelAccountId = -1;

    @Unique
    private String wawelProviderName;

    @Unique
    private ServerCapabilities wawelCapabilities = ServerCapabilities.empty();

    @Override
    public long getWawelAccountId() {
        return wawelAccountId;
    }

    @Override
    public void setWawelAccountId(long id) {
        this.wawelAccountId = id;
    }

    @Override
    public String getWawelProviderName() {
        return wawelProviderName;
    }

    @Override
    public void setWawelProviderName(String name) {
        this.wawelProviderName = name;
    }

    @Override
    public ServerCapabilities getWawelCapabilities() {
        return wawelCapabilities;
    }

    @Override
    public void setWawelCapabilities(ServerCapabilities capabilities) {
        this.wawelCapabilities = capabilities != null ? capabilities : ServerCapabilities.empty();
    }

    @Inject(method = "getNBTCompound", at = @At("RETURN"))
    private void wawelauth$saveNbt(CallbackInfoReturnable<NBTTagCompound> cir) {
        NBTTagCompound nbt = cir.getReturnValue();
        if (wawelAccountId >= 0) {
            nbt.setLong("wawelAccountId", wawelAccountId);
        }
        if (wawelProviderName != null) {
            nbt.setString("wawelProviderName", wawelProviderName);
        }
    }

    @Inject(method = "getServerDataFromNBTCompound", at = @At("RETURN"))
    private static void wawelauth$loadNbt(NBTTagCompound nbt, CallbackInfoReturnable<ServerData> cir) {
        IServerDataExt ext = (IServerDataExt) cir.getReturnValue();
        if (nbt.hasKey("wawelAccountId")) {
            ext.setWawelAccountId(nbt.getLong("wawelAccountId"));
        }
        if (nbt.hasKey("wawelProviderName")) {
            ext.setWawelProviderName(nbt.getString("wawelProviderName"));
        }
    }

    @Inject(method = "func_152583_a", at = @At("RETURN")) // ServerData.copyFrom
    private void wawelauth$copyFrom(ServerData other, CallbackInfo ci) {
        IServerDataExt otherExt = (IServerDataExt) other;
        this.wawelAccountId = otherExt.getWawelAccountId();
        this.wawelProviderName = otherExt.getWawelProviderName();
        this.wawelCapabilities = otherExt.getWawelCapabilities();
    }
}
