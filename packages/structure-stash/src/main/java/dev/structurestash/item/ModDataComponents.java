package dev.structurestash.item;

import com.mojang.serialization.Codec;
import dev.structurestash.StructureStash;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

public class ModDataComponents {

    public static final DeferredRegister.DataComponents COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, StructureStash.MODID);

    private static final Codec<byte[]> BYTE_ARRAY_CODEC = Codec.BYTE_BUFFER.xmap(
        buf -> { byte[] arr = new byte[buf.remaining()]; buf.get(arr); return arr; },
        ByteBuffer::wrap
    );

    /** Wand: stores point A position when first corner is set. */
    public static final Supplier<DataComponentType<BlockPos>> WAND_POS_A =
        COMPONENTS.registerComponentType("wand_pos_a", builder -> builder
            .persistent(BlockPos.CODEC)
            .networkSynchronized(BlockPos.STREAM_CODEC)
        );

    /** Blueprint: stores the full StructureTemplate data. */
    public static final Supplier<DataComponentType<BlueprintData>> BLUEPRINT_DATA =
        COMPONENTS.registerComponentType("blueprint_data", builder -> builder
            .persistent(BYTE_ARRAY_CODEC.xmap(BlueprintData::new, BlueprintData::data))
            .networkSynchronized(ByteBufCodecs.BYTE_ARRAY
                .map(BlueprintData::new, BlueprintData::data))
        );
}
