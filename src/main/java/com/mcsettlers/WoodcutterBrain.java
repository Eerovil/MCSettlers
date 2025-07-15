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

    // Find an axe in the villager's inventory
    private static net.minecraft.item.ItemStack findAxeInInventory(VillagerEntity villager) {
        for (int i = 0; i < villager.getInventory().size(); i++) {
            net.minecraft.item.ItemStack stack = villager.getInventory().getStack(i);
            if (stack.getItem() instanceof net.minecraft.item.AxeItem) {
                return stack;
            }
        }
        return net.minecraft.item.ItemStack.EMPTY;
    }

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
                // Search for targets near the villager, but within the area around the workstation
                int searchRadius = 10;
                BlockPos villagerPos = villager.getBlockPos();
                targetLog = findNearbyTargetPrioritized(world, villagerPos, workstation, searchRadius);
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
            double distSq = villager.squaredDistanceTo(Vec3d.ofCenter(targetLog));
            double dist = Math.sqrt(distSq);
            showDebugInfo(villager, targetLog, dist);
            if (dist > 6) {
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
                    // Axe holding logic
                    net.minecraft.item.ItemStack axeStack = findAxeInInventory(villager);
                    if (!axeStack.isEmpty()) {
                        villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, axeStack);
                    }
                    // Animate villager looking at the block
                    Vec3d blockCenter = Vec3d.ofCenter(blockToBreak);
                    villager.getLookControl().lookAt(blockCenter.x, blockCenter.y, blockCenter.z);
                    // Breaking animation logic
                    for (int progress = 0; progress <= 10; progress++) {
                        world.setBlockBreakingInfo(villager.getId(), blockToBreak, progress);
                        // Animate swing arm (if available)
                        villager.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                        try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                    }
                    world.breakBlock(blockToBreak, true, villager);
                    world.setBlockBreakingInfo(villager.getId(), blockToBreak, -1); // clear animation
                    // Remove axe from hand after breaking
                    villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, net.minecraft.item.ItemStack.EMPTY);
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
            // Overloaded version expects distance
            showDebugInfo(villager, target, Math.sqrt(villager.squaredDistanceTo(Vec3d.ofCenter(target))));
        } else {
            villager.setCustomName(null);
            villager.setCustomNameVisible(false);
        }

    }

    // Overloaded debug info to accept precomputed distance
    private static void showDebugInfo(VillagerEntity villager, BlockPos target, double dist) {
        if (target != null) {
            villager.setCustomName(
                net.minecraft.text.Text.of("Target: " + target.getX() + "," + target.getY() + "," + target.getZ() + " | Dist: " + String.format("%.2f", dist))
            );
            villager.setCustomNameVisible(true);
        } else {
            villager.setCustomName(null);
            villager.setCustomNameVisible(false);
        }
    }

    // Search for targets near the villager, but only within the area around the workstation
    private static BlockPos findNearbyTargetPrioritized(ServerWorld world, BlockPos villagerPos, BlockPos workstation, int radius) {
        BlockPos bestApproach = null;
        double bestDist = Double.MAX_VALUE;
        int bestYDiff = Integer.MAX_VALUE;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Only consider blocks within radius of both villager and workstation
                    int distToVillager2 = dx*dx + dy*dy + dz*dz;
                    int wx = workstation.getX(), wy = workstation.getY(), wz = workstation.getZ();
                    int vx = villagerPos.getX(), vy = villagerPos.getY(), vz = villagerPos.getZ();
                    mutable.set(vx+dx, vy+dy, vz+dz);
                    int distToWorkstation2 = (vx+dx-wx)*(vx+dx-wx) + (vy+dy-wy)*(vy+dy-wy) + (vz+dz-wz)*(vz+dz-wz);
                    if (distToVillager2 > r2 || distToWorkstation2 > r2) continue;
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
                        double dist = villagerPos.getSquaredDistance(approach);
                        int yDiff = Math.abs(villagerPos.getY() - approach.getY());
                        // Prioritize by Y difference, then by distance to villager
                        if (yDiff < bestYDiff || (yDiff == bestYDiff && dist < bestDist)) {
                            bestDist = dist;
                            bestYDiff = yDiff;
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
