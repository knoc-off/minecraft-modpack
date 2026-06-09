package dev.structurestash.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.neoforged.neoforge.client.model.pipeline.VertexConsumerWrapper;

/**
 * Wraps a VertexConsumer to multiply all vertex alpha values by a factor.
 * Used for rendering ghost/translucent block previews.
 */
public class GhostVertexConsumer extends VertexConsumerWrapper {

    private final float alpha;

    public GhostVertexConsumer(VertexConsumer wrapped, float alpha) {
        super(wrapped);
        this.alpha = alpha;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        return parent.setColor(r, g, b, (int) (a * alpha));
    }
}
