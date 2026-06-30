package dev.paintcraft.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import dev.paintcraft.PaintCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.lwjgl.opengl.GL11;

/**
 * CPU-side snapshot of the live block texture atlas, read back from the GPU.
 *
 * <p>The block atlas on the GPU is exactly what the world renders. Sampling it guarantees the
 * editor background/palette match in-world appearance. This deliberately avoids
 * {@code SpriteContents.getOriginalImage()}, whose pre-stitch source image can be a stale or
 * higher-resolution variant retained from resource-pack loading — that mismatch was the source
 * of the "HD editor background" bug.
 *
 * <p>The snapshot is cached and re-downloaded only when the atlas GL texture id changes.
 * All methods must be called on the render thread (they touch GL state).
 */
public final class AtlasImageCache {

    private static NativeImage cached;
    private static int cachedId = -1;
    private static int atlasW;
    private static int atlasH;

    private AtlasImageCache() {}

    /** Returns the cached atlas image, downloading it from the GPU if needed. Never null. */
    public static synchronized NativeImage blockAtlas() {
        TextureAtlas atlas = Minecraft.getInstance().getModelManager()
            .getAtlas(TextureAtlas.LOCATION_BLOCKS);
        int id = atlas.getId();
        if (cached != null && id == cachedId) return cached;

        if (cached != null) { cached.close(); cached = null; }

        GlStateManager._bindTexture(id);
        atlasW = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        atlasH = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        NativeImage img = new NativeImage(atlasW, atlasH, false);
        img.downloadTexture(0, false);
        cached = img;
        cachedId = id;
        PaintCraft.LOGGER.info("[atlas] downloaded block atlas snapshot {}x{} (id {})",
            atlasW, atlasH, id);
        return cached;
    }

    public static int width()  { return atlasW; }
    public static int height() { return atlasH; }

    /** Sample the atlas at normalized atlas UV coordinates. Returns ABGR (NativeImage format). */
    public static int sampleABGR(float u, float v) {
        NativeImage img = blockAtlas();
        int x = Math.clamp((int) (u * atlasW), 0, atlasW - 1);
        int y = Math.clamp((int) (v * atlasH), 0, atlasH - 1);
        return img.getPixelRGBA(x, y);
    }

    /** Drop the cached snapshot; next access re-downloads. */
    public static synchronized void invalidate() {
        if (cached != null) { cached.close(); cached = null; }
        cachedId = -1;
    }

    /**
     * Mod-bus registration: invalidate the snapshot on every resource reload, since the atlas
     * is re-stitched into the same GL id (so the id check alone wouldn't detect the change).
     */
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) rm -> invalidate());
    }
}
