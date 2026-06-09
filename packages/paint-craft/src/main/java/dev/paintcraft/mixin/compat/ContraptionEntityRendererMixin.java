package dev.paintcraft.mixin.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import dev.paintcraft.client.compat.create.ContraptionDecalRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders riding PaintCraft decals as part of each contraption, reusing Create's
 * per-frame model matrix and virtual render world. Injected just before the matrices
 * are cleared (while they are still valid).
 */
@Pseudo
@Mixin(value = ContraptionEntityRenderer.class, remap = false)
public abstract class ContraptionEntityRendererMixin {

    @Inject(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/contraptions/render/ContraptionMatrices;clear()V"
        ),
        remap = false
    )
    private void paintcraft$renderDecals(AbstractContraptionEntity entity, float yaw, float partialTicks,
                                         PoseStack poseStack, MultiBufferSource buffers, int overlay,
                                         CallbackInfo ci) {
        ContraptionDecalRenderer.render(entity, poseStack, buffers);
    }
}
