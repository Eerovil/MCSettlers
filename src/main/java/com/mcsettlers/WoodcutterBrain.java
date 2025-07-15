package com.mcsettlers;

import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
public class WoodcutterBrain {
    private static int tickCounter = 0;

    public static void tick(VillagerEntity villager, ServerWorld world) {
        Brain<?> brain = villager.getBrain();
        BlockPos workstation = brain.getOptionalMemory(MemoryModuleType.JOB_SITE)
            .map(GlobalPos::pos)
            .orElse(null);
        if (workstation == null) return;
        Optional<BlockPos> optionalTarget = brain.getOptionalMemory(ModMemoryModules.TARGET_LOG);
        BlockPos targetLog = optionalTarget != null ? optionalTarget.orElse(null) : null;
        boolean needsNewTarget = targetLog == null ||
            !(world.getBlockState(targetLog).isIn(BlockTags.LOGS) || world.getBlockState(targetLog).isIn(BlockTags.LEAVES));
        // Only search for new logs or leaves every 10 ticks
        if (needsNewTarget) {
            if (tickCounter % 10 == 0) {
                targetLog = findNearbyTarget(world, workstation, 10);
                if (targetLog != null) {
                    brain.remember(ModMemoryModules.TARGET_LOG, targetLog);
                } else {
                    brain.forget(ModMemoryModules.TARGET_LOG);
                    tickCounter++;
                    showDebugInfo(villager, null);
                    return;
                }
            } else {
                tickCounter++;
                showDebugInfo(villager, null);
                return;
            }
        }
        tickCounter++;
        if (targetLog != null) {
            double dist = villager.squaredDistanceTo(Vec3d.ofCenter(targetLog));
            showDebugInfo(villager, targetLog);
            if (dist > 7) {
                // Always try to move under the target block
                BlockPos underTarget = targetLog.down();
                brain.remember(MemoryModuleType.WALK_TARGET,
                    new net.minecraft.entity.ai.brain.WalkTarget(underTarget, 0.6F, 0));
            } else {
                // Find the actual block to break (the log/leaf adjacent to this air block)
                BlockPos blockToBreak = null;
                for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
                    BlockPos adj = targetLog.offset(dir);
                    BlockState state = world.getBlockState(adj);
                    if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) {
                        blockToBreak = adj;
                        break;
                    }
                }
                if (blockToBreak != null) {
                    world.breakBlock(blockToBreak, true, villager);
                }
                brain.forget(ModMemoryModules.TARGET_LOG); // clear after breaking
                brain.forget(MemoryModuleType.WALK_TARGET); // clear walk target
                showDebugInfo(villager, null);
            }
        } else {
            showDebugInfo(villager, null);
        }
    }

    private static void showDebugInfo(VillagerEntity villager, BlockPos target) {
        if (target != null) {
            double dist = villager.squaredDistanceTo(Vec3d.ofCenter(target));
            villager.setCustomName(
                net.minecraft.text.Text.of("Target: " + target.getX() + "," + target.getY() + "," + target.getZ() + " | Dist: " + String.format("%.2f", dist))
            );
            villager.setCustomNameVisible(true);
        } else {
            villager.setCustomName(null);
            villager.setCustomNameVisible(false);
        }
    }

    private static BlockPos findNearbyTarget(ServerWorld world, BlockPos center, int radius) {
        BlockPos bestTarget = null;
        BlockPos bestApproach = null;
        double bestDist = Double.MAX_VALUE;
        int bestYDiff = Integer.MAX_VALUE;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx*dx + dy*dy + dz*dz > r2) continue;
                    mutable.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = world.getBlockState(mutable);
                    if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) {
                        // Find an adjacent air block to approach from
                        BlockPos approach = null;
                        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
                            BlockPos adj = mutable.offset(dir);
                            BlockState adjState = world.getBlockState(adj);
                            if (adjState.isAir()) {
                                approach = adj;
                                break;
                            }
                        }
                        if (approach == null) continue; // Not reachable
                        double dist = center.getSquaredDistance(approach);
                        int yDiff = Math.abs(center.getY() - approach.getY());
                        // Prioritize by Y difference, then by distance
                        if (yDiff < bestYDiff || (yDiff == bestYDiff && dist < bestDist)) {
                            bestDist = dist;
                            bestYDiff = yDiff;
                            bestTarget = mutable.toImmutable();
                            bestApproach = approach;
                        }
                    }
                }
            }
        }
        // Return the approach block if found, otherwise null
        return bestApproach;
    }
}
