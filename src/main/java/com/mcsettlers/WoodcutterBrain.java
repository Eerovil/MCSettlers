package com.mcsettlers;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

public class WoodcutterBrain {
    public static void tick(VillagerEntity villager, ServerWorld world) {
        BlockPos workstation = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE)
            .map(GlobalPos::pos)
            .orElse(null);
        if (workstation == null) return;

        BlockPos targetLog = findNearbyLog(world, workstation, 10);
        if (targetLog != null) {
            if (villager.squaredDistanceTo(Vec3d.ofCenter(targetLog)) > 2) {
                villager.getNavigation().startMovingTo(targetLog.getX(), targetLog.getY(), targetLog.getZ(), 1.0);
            } else {
                world.breakBlock(targetLog, true, villager);
            }
        }
    }

    private static BlockPos findNearbyLog(ServerWorld world, BlockPos center, int radius) {
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = world.getBlockState(mutable);
                    if (state.isIn(BlockTags.LOGS)) {
                        return mutable.toImmutable();
                    }
                }
            }
        }
        return null;
    }
}
