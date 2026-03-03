package org.fentanylsolutions.wawelauth.client.render;

import org.fentanylsolutions.wawelauth.wawelcore.data.SkinModel;

/**
 * Optional hook for entities that want to force a resolved skin model.
 */
public interface ISkinModelOverride {

    SkinModel wawelauth$getForcedSkinModel();
}
