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
        // Always show debug info for all related memory values at the start of every
        // tick
        Brain<?> brain = villager.getBrain();
        BlockPos workstation = brain.getOptionalMemory(MemoryModuleType.JOB_SITE)
                .map(GlobalPos::pos)
                .orElse(null);
        Optional<BlockPos> optionalTarget = brain.getOptionalMemory(ModMemoryModules.TARGET_LOG);
        BlockPos targetLog = optionalTarget != null ? optionalTarget.orElse(null) : null;
        Optional<BlockPos> optionalBreakBlock = brain.getOptionalMemory(ModMemoryModules.TARGET_BREAK_BLOCK);
        BlockPos breakBlock = optionalBreakBlock != null ? optionalBreakBlock.orElse(null) : null;
        showDebugInfoAll(villager, targetLog, breakBlock, workstation);
        if (workstation == null)
            return;
        boolean needsNewTarget = targetLog == null ||
                !(world.getBlockState(targetLog).isIn(BlockTags.LOGS)
                        || world.getBlockState(targetLog).isIn(BlockTags.LEAVES));
        // Only search for new logs or leaves every 10 ticks
        if (needsNewTarget) {
            if (tickCounter % 10 == 0) {
                int searchRadius = 10;
                BlockPos villagerPos = villager.getBlockPos();
                BlockPos[] found = findNearbyLogAndApproach(world, villagerPos, workstation, searchRadius);
                BlockPos foundLog = found != null ? found[0] : null;
                BlockPos foundApproach = found != null ? found[1] : null;
                if (foundLog != null) {
                    brain.remember(ModMemoryModules.TARGET_LOG, foundLog);
                    brain.remember(MemoryModuleType.WALK_TARGET,
                            new net.minecraft.entity.ai.brain.WalkTarget(foundApproach, 0.6F, 0));
                } else {
                    brain.forget(ModMemoryModules.TARGET_LOG);
                    tickCounter++;
                    return;
                }
            } else {
                tickCounter++;
                return;
            }
        }
        tickCounter++;
        // Always show debug info for all related memory values
        showDebugInfoAll(villager, targetLog, breakBlock, workstation);
        if (targetLog != null) {
            double distSq = villager.squaredDistanceTo(Vec3d.ofCenter(targetLog));
            double dist = Math.sqrt(distSq);
            // Only break if close enough to the log and not already set
            if (dist <= 6 && breakBlock == null) {
                brain.remember(ModMemoryModules.TARGET_BREAK_BLOCK, targetLog);
                Vec3d blockCenter = Vec3d.ofCenter(targetLog);
                villager.getLookControl().lookAt(blockCenter.x, blockCenter.y, blockCenter.z);
            }
        }

        // If we have a block to break stored, do the breaking animation this tick
        if (breakBlock != null) {
            System.out.println("[WoodcutterBrain] Villager " + villager.getUuidAsString() + " breaking block at "
                    + breakBlock.toShortString());
            // Axe holding logic
            net.minecraft.item.ItemStack axeStack = findAxeInInventory(villager);
            if (!axeStack.isEmpty()) {
                System.out.println("[WoodcutterBrain] Villager holding axe: " + axeStack);
                villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, axeStack);
            }
            Vec3d blockCenter = Vec3d.ofCenter(breakBlock);
            villager.getLookControl().lookAt(blockCenter.x, blockCenter.y, blockCenter.z);
            // Breaking animation logic
            for (int progress = 0; progress <= 10; progress++) {
                world.setBlockBreakingInfo(villager.getId(), breakBlock, progress);
                villager.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                try {
                    Thread.sleep(80);
                } catch (InterruptedException ignored) {
                }
            }
            System.out.println("[WoodcutterBrain] Actually breaking block at " + breakBlock.toShortString());
            world.breakBlock(breakBlock, true, villager);
            world.setBlockBreakingInfo(villager.getId(), breakBlock, -1); // clear animation
            villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, net.minecraft.item.ItemStack.EMPTY);
            brain.forget(ModMemoryModules.TARGET_BREAK_BLOCK);
            brain.forget(ModMemoryModules.TARGET_LOG); // clear after breaking
            brain.forget(MemoryModuleType.WALK_TARGET); // clear walk target
        }
    }

    // Show debug info for all related memory values
    private static void showDebugInfoAll(VillagerEntity villager, BlockPos targetLog, BlockPos breakBlock,
            BlockPos workstation) {
        StringBuilder sb = new StringBuilder();
        if (targetLog != null) {
            double dist = Math.sqrt(villager.squaredDistanceTo(Vec3d.ofCenter(targetLog)));
            sb.append("TargetLog: ").append(targetLog.getX()).append(",").append(targetLog.getY()).append(",")
                    .append(targetLog.getZ())
                    .append(" | Dist: ").append(String.format("%.2f", dist)).append("\n");
        }
        if (breakBlock != null) {
            double dist = Math.sqrt(villager.squaredDistanceTo(Vec3d.ofCenter(breakBlock)));
            sb.append("BreakBlock: ").append(breakBlock.getX()).append(",").append(breakBlock.getY()).append(",")
                    .append(breakBlock.getZ())
                    .append(" | Dist: ").append(String.format("%.2f", dist)).append("\n");
        }
        if (sb.length() > 0) {
            villager.setCustomName(net.minecraft.text.Text.of(sb.toString()));
            villager.setCustomNameVisible(true);
        } else {
            villager.setCustomName(null);
            villager.setCustomNameVisible(false);
        }
    }

    // Find the nearest log/leaf and its approach air block
    private static BlockPos[] findNearbyLogAndApproach(ServerWorld world, BlockPos villagerPos, BlockPos workstation,
            int radius) {
        BlockPos bestLog = null;
        BlockPos bestApproach = null;
        double bestDist = Double.MAX_VALUE;
        int bestYDiff = Integer.MAX_VALUE;
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int wx = workstation.getX(), wy = workstation.getY(), wz = workstation.getZ();
                    int vx = villagerPos.getX(), vy = villagerPos.getY(), vz = villagerPos.getZ();
                    int distToVillager2 = dx * dx + dy * dy + dz * dz;
                    mutable.set(vx + dx, vy + dy, vz + dz);
                    int distToWorkstation2 = (vx + dx - wx) * (vx + dx - wx) + (vy + dy - wy) * (vy + dy - wy)
                            + (vz + dz - wz) * (vz + dz - wz);
                    if (distToVillager2 > r2 || distToWorkstation2 > r2)
                        continue;
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
                        if (approach == null)
                            continue; // Not reachable
                        double dist = villagerPos.getSquaredDistance(approach);
                        int yDiff = Math.abs(villagerPos.getY() - approach.getY());
                        // Prioritize by Y difference, then by distance to villager
                        if (yDiff < bestYDiff || (yDiff == bestYDiff && dist < bestDist)) {
                            bestDist = dist;
                            bestYDiff = yDiff;
                            bestLog = mutable.toImmutable();
                            bestApproach = approach;
                        }
                    }
                }
            }
        }
        return (bestLog != null && bestApproach != null) ? new BlockPos[] { bestLog, bestApproach } : null;
    }
}
