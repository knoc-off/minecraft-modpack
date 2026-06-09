package dev.paintcraft.client.compat;

import com.communi.suggestu.scena.core.client.models.data.IBlockModelData;
import com.communi.suggestu.scena.core.entity.block.IBlockEntityWithModelData;
import mod.chiselsandbits.registrars.ModModelProperties;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Bypasses the ForgeBakedModelDelegate to extract C&B's resolved BakedModel
 * directly from Scena's IBlockModelData. This class references Scena and C&B
 * types and must ONLY be loaded when C&B is present (guarded by HAS_CNB check).
 */
public final class ChiseledBlockHelper {

    private ChiseledBlockHelper() {}

    public static List<BakedQuad> getDataAwareQuads(
            BlockAndTintGetter level, BlockPos pos, BlockState state,
            BakedModel model, Direction face, RandomSource random) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof IBlockEntityWithModelData withData)) return List.of();

        IBlockModelData modelData = withData.getBlockModelData();
        if (!modelData.hasProperty(ModModelProperties.UNKNOWN_LAYER_MODEL_PROPERTY)) return List.of();

        BakedModel resolved = modelData.getData(ModModelProperties.UNKNOWN_LAYER_MODEL_PROPERTY);
        if (resolved == null) return List.of();

        // Face-specific bucket: boundary bits flush with the block edge (cullable)
        List<BakedQuad> faceQuads = resolved.getQuads(state, face, random);
        // Null/generic bucket: interior bits not on any block boundary (never culled)
        List<BakedQuad> nullQuads = resolved.getQuads(state, null, random);

        if (nullQuads.isEmpty()) return faceQuads;

        // Combine face-specific quads with direction-matching interior quads
        List<BakedQuad> combined = new ArrayList<>(faceQuads);
        for (BakedQuad q : nullQuads) {
            if (q.getDirection() == face) {
                combined.add(q);
            }
        }
        return combined;
    }
}
