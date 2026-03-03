package org.fentanylsolutions.wawelauth.client.render;

import org.fentanylsolutions.wawelauth.client.render.skinlayers.SkinLayers3DState;

/**
 * Duck interface injected into {@link net.minecraft.client.model.ModelBiped} via mixin.
 *
 * Cast any ModelBiped to this interface to access modern 64x64 skin features.
 * Non-player ModelBiped instances (zombies, skeletons, armor) have
 * {@link #wawelauth$isModern()} → false; all methods are no-ops for them.
 */
public interface IModelBipedModernExt {

    /**
     * Initialize this ModelBiped for modern 64x64 skin rendering.
     * Sets textureWidth/Height to 64x64, rebuilds all vanilla parts with correct UVs,
     * creates 5 overlay layers and slim arm variants.
     * Called once from MixinRenderPlayer's constructor.
     */
    void wawelauth$initModern();

    /**
     * Swap between slim (3px) and classic (4px) arm ModelRenderers.
     * No-op if the current state already matches the requested mode.
     */
    void wawelauth$setSlim(boolean slim);

    /**
     * Returns true if this model has been initialized for modern 64x64 rendering.
     */
    boolean wawelauth$isModern();

    /**
     * Render the right arm overlay layer (for first-person arm rendering).
     */
    void wawelauth$renderRightArmWear(float scale);

    /**
     * Set 3D skin layer state for the current render pass.
     * When non-null, overlay rendering uses 3D meshes instead of flat ModelRenderers.
     */
    void wawelauth$setSkinLayers3D(SkinLayers3DState state);

    /**
     * Get the current 3D skin layer state, or null if 3D layers are inactive.
     */
    SkinLayers3DState wawelauth$getSkinLayers3D();
}
