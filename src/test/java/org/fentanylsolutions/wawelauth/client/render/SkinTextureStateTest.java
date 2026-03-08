package org.fentanylsolutions.wawelauth.client.render;

import java.io.IOException;

import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.resources.IResourceManager;

import org.junit.Assert;
import org.junit.Test;

public class SkinTextureStateTest {

    @Test
    public void usableRejectsNullAndMissingTextureMarker() {
        ITextureObject sentinel = new StubTextureObject();
        ITextureObject realTexture = new StubTextureObject();

        Assert.assertFalse(SkinTextureState.isUsable(null, sentinel));
        Assert.assertFalse(SkinTextureState.isUsable(sentinel, sentinel));
        Assert.assertTrue(SkinTextureState.isUsable(realTexture, sentinel));
    }

    private static final class StubTextureObject implements ITextureObject {

        @Override
        public void loadTexture(IResourceManager resourceManager) throws IOException {}

        @Override
        public int getGlTextureId() {
            return 0;
        }
    }
}
