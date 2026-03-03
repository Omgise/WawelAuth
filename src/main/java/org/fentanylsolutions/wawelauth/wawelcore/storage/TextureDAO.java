package org.fentanylsolutions.wawelauth.wawelcore.storage;

import org.fentanylsolutions.wawelauth.wawelcore.data.StoredTexture;

/**
 * Data access interface for {@link StoredTexture} metadata.
 *
 * This DAO tracks metadata about stored texture files. The actual PNG files
 * are stored on disk using content-addressed storage ({hash}.png).
 * This DAO does NOT handle file I/O: only the metadata records.
 *
 * Used by:
 * - PUT /api/user/profile/{uuid}/{type}: texture upload (create)
 * - DELETE /api/user/profile/{uuid}/{type}: texture removal (delete)
 * - Texture URL resolution (findByHash)
 */
public interface TextureDAO {

    /** Find texture metadata by content hash. Returns null if not found. */
    StoredTexture findByHash(String hash);

    /** Persist texture metadata. If hash already exists, this is a no-op (content-addressed dedup). */
    void create(StoredTexture texture);

    /**
     * Delete texture metadata by hash.
     * The caller is responsible for also deleting the file on disk,
     * but only if no other profiles still reference this hash.
     */
    void delete(String hash);

    /**
     * Check if any profile still references this texture hash.
     * Used to determine if the file on disk can be safely deleted.
     */
    boolean isReferenced(String hash);
}
