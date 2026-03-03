package org.fentanylsolutions.wawelauth.core;

import java.util.List;
import java.util.Set;

import org.fentanylsolutions.fentlib.core.FentEarlyMixinLoader;
import org.fentanylsolutions.wawelauth.WawelAuth;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

@SuppressWarnings("unused")
@IFMLLoadingPlugin.MCVersion("1.7.10")
public class EarlyMixinLoader extends FentEarlyMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins." + WawelAuth.MODID + ".early.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedCoreMods) {
        return Mixins.getEarlyMixinsForLoader();
    }
}
