package dev.structurestash.item;

import dev.structurestash.client.BlueprintGhostRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Rotation;

import java.util.Arrays;

/**
 * Blueprint item — places a captured multi-block structure in the world.
 * Single-use: consumed on successful placement.
 * Rotates to match player facing direction.
 *
 * <p>Placement policy:
 * <ul>
 *   <li>Air blocks in the template are skipped — they never overwrite world blocks.</li>
 *   <li>Non-air template blocks reject placement only if the world position is
 *       already occupied by a non-replaceable block.</li>
 * </ul>
 */
public class BlueprintItem extends Item {

    public BlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        ItemStack stack = ctx.getItemInHand();
        BlueprintData bd = stack.get(ModDataComponents.BLUEPRINT_DATA.get());
        byte[] data = bd != null ? bd.data() : null;
        if (data == null || data.length == 0) {
            if (!ctx.getLevel().isClientSide() && ctx.getPlayer() != null) {
                ctx.getPlayer().displayClientMessage(Component.literal("Empty blueprint"), true);
            }
            return InteractionResult.FAIL;
        }

        if (ctx.getLevel().isClientSide()) {
            // Don't re-lock if already confirming with the same blueprint —
            // confirm is handled by InteractionKeyMappingTriggered
            if (BlueprintGhostRenderer.isConfirming()
                    && Arrays.equals(data, BlueprintGhostRenderer.getLockedData())) {
                return InteractionResult.SUCCESS;
            }
            // Lock (or re-lock) ghost preview at this position
            BlockPos anchor = ctx.getClickedPos().relative(ctx.getClickedFace());
            Rotation rotation = facingToRotation(ctx.getPlayer().getDirection());
            BlueprintGhostRenderer.lockConfirm(anchor, rotation, data);
        }
        // Server: no-op — placement handled by ConfirmBlueprintPlacePayload
        return InteractionResult.SUCCESS;
    }

    /**
     * Map player horizontal facing to StructureTemplate Rotation.
     * Structures are captured facing SOUTH (the default MC orientation).
     * SOUTH = no rotation, WEST = CW90, NORTH = CW180, EAST = CCW90.
     */
    public static Rotation facingToRotation(Direction facing) {
        return switch (facing) {
            case SOUTH -> Rotation.NONE;
            case WEST  -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST  -> Rotation.COUNTERCLOCKWISE_90;
            default    -> Rotation.NONE;
        };
    }

    @Override
    public Component getName(ItemStack stack) {
        BlueprintData bd = stack.get(ModDataComponents.BLUEPRINT_DATA.get());
        if (bd != null && bd.data().length > 0) {
            return Component.literal("Blueprint (" + (bd.data().length / 1024) + "KB)");
        }
        return super.getName(stack);
    }
}
