package com.mcsettlers;

import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

import com.mcsettlers.utils.RadiusGenerator;

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

        tickCounter++;
        Brain<?> brain = villager.getBrain();
        BlockPos workstation = brain.getOptionalMemory(MemoryModuleType.JOB_SITE)
                .map(GlobalPos::pos)
                .orElse(null);
        Optional<BlockPos> optionalTarget = brain.getOptionalMemory(ModMemoryModules.TARGET_LOG);
        BlockPos targetLog = optionalTarget != null ? optionalTarget.orElse(null) : null;
        Optional<BlockPos> optionalBreakBlock = brain.getOptionalMemory(ModMemoryModules.TARGET_BREAK_BLOCK);
        BlockPos breakBlock = optionalBreakBlock != null ? optionalBreakBlock.orElse(null) : null;
        Optional<WalkTarget> optionalWalkTarget = brain.getOptionalMemory(MemoryModuleType.WALK_TARGET);
        BlockPos walkTarget = null;
        if (optionalWalkTarget.isPresent()) {
            WalkTarget wt = optionalWalkTarget.get();
            if (wt != null && wt.getLookTarget() != null) {
                walkTarget = wt.getLookTarget().getBlockPos();
            }
        }
        showDebugInfoAll(villager, targetLog, breakBlock, walkTarget);
        if (tickCounter % 20 != 0) {
            return;
        }
        if (workstation == null)
            return;
        if (targetLog != null) {
            if (!world.getBlockState(targetLog).isIn(BlockTags.LOGS) &&
                !world.getBlockState(targetLog).isIn(BlockTags.LEAVES)) {
                // If the target log is not a log or leaf, we need to find a new
                targetLog = null;
                brain.forget(ModMemoryModules.TARGET_LOG);
            }
        }
        boolean needsNewTarget = (targetLog == null && walkTarget == null);
        // Only search for new logs or leaves every 10 ticks
        if (needsNewTarget) {
            if (tickCounter % 20 == 0) {
                MCSettlers.LOGGER.info("[WoodcutterBrain] Villager " + villager.getUuidAsString()
                        + " needs new target log or leaf, searching...");

                MCSettlers.LOGGER.info("[WoodcutterBrain] targetLog: " + targetLog);
                MCSettlers.LOGGER.info("[WoodcutterBrain] walkTarget: " + walkTarget);
                int searchRadius = 10;
                BlockPos villagerPos = villager.getBlockPos();
                BlockPos[] found = findNearbyLogAndApproach(world, villagerPos, workstation, searchRadius);
                BlockPos foundLog = found != null ? found[0] : null;
                BlockPos foundApproach = found != null ? found[1] : null;
                if (foundLog != null) {
                    MCSettlers.LOGGER.info("[WoodcutterBrain] Found log at " + foundLog.toShortString()
                            + ", approach at " + foundApproach.toShortString());
                    brain.remember(ModMemoryModules.TARGET_LOG, foundLog);
                    // Ensure approach is walkable (not inside block, on ground)
                    BlockPos walkableApproach = foundApproach;
                    while (!world.getBlockState(walkableApproach.down()).isSolidBlock(world, walkableApproach.down()) && walkableApproach.getY() > 0) {
                        walkableApproach = walkableApproach.down();
                    }
                    walkTarget = walkableApproach;
                    targetLog = foundLog; // Update targetLog to the found log
                    // Set walk target with reasonable completion range and duration
                    brain.remember(MemoryModuleType.WALK_TARGET,
                            new net.minecraft.entity.ai.brain.WalkTarget(
                                    new net.minecraft.entity.ai.brain.BlockPosLookTarget(walkableApproach),
                                    0.6F,
                                    1 // completion range
                            )
                    );
                } else {
                    MCSettlers.LOGGER.info("[WoodcutterBrain] No log found, clearing target.");
                    brain.forget(ModMemoryModules.TARGET_LOG);
                    // Walk to workstation if no log found
                    brain.remember(MemoryModuleType.WALK_TARGET,
                            new net.minecraft.entity.ai.brain.WalkTarget(
                                    new net.minecraft.entity.ai.brain.BlockPosLookTarget(workstation),
                                    0.6F,
                                    1
                            )
                    );
                    return;
                }
            } else {
                return;
            }
        }
        // Always show debug info for all related memory values
        showDebugInfoAll(villager, targetLog, breakBlock, walkTarget);
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
            // Only clear walk target if villager is close enough to the block
            if (villager.squaredDistanceTo(Vec3d.ofCenter(breakBlock)) <= 4) {
                brain.forget(MemoryModuleType.WALK_TARGET);
            }
        } else if (targetLog != null && walkTarget == null) {
            // No walking left, but block not in reach... We go back to workstation
            System.out.println("[WoodcutterBrain] Villager " + villager.getUuidAsString()
                    + " returning to workstation at " + workstation.toShortString());
            brain.forget(ModMemoryModules.TARGET_LOG);
            brain.remember(MemoryModuleType.WALK_TARGET,
            new net.minecraft.entity.ai.brain.WalkTarget(
                    new net.minecraft.entity.ai.brain.BlockPosLookTarget(workstation), 0.6F, 0));
        }
    }

    // Show debug info for all related memory values
    private static void showDebugInfoAll(VillagerEntity villager, BlockPos targetLog, BlockPos breakBlock,
            BlockPos walkTarget) {
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
        if (walkTarget != null) {
            double dist = Math.sqrt(villager.squaredDistanceTo(Vec3d.ofCenter(walkTarget)));
            sb.append("WalkTarget: ").append(walkTarget.getX()).append(",").append(walkTarget.getY()).append(",")
                    .append(walkTarget.getZ())
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

    private static BlockPos checkTargetLogForAccess(ServerWorld world, BlockPos targetLog) {
        // Check if the target log is accessible (not obstructed by blocks)
        // This is a placeholder; actual implementation would depend on game logic
        // For now, we assume all logs are accessible
        BlockState state = world.getBlockState(targetLog);
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) {

            BlockPos approach = null;
            for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {

                BlockPos adj = targetLog.offset(dir);
                BlockState adjState = world.getBlockState(adj);
                if (adjState.isAir()) {
                    approach = adj;
                    return approach;
                }
            }
        }
        return null;
    }

    // Find the nearest log/leaf and its approach air block
    private static BlockPos[] findNearbyLogAndApproach(
            ServerWorld world,
            BlockPos villagerPos,
            BlockPos workstation,
            int radius
        ) {
            // First try to find a log, then a leaf
        BlockPos[] result = findNearbyLogAndApproach(world, villagerPos, workstation, radius, BlockTags.LOGS);
        MCSettlers.LOGGER.info("[WoodcutterBrain] Searching for logs around villager at " + villagerPos.toShortString());
        if (result != null) {
            return result;
        }
        // If no log found, try leaves
        return findNearbyLogAndApproach(world, villagerPos, workstation, radius, BlockTags.LEAVES);
    }

    // Find the nearest log/leaf and its approach air block
    private static BlockPos[] findNearbyLogAndApproach(
            ServerWorld world,
            BlockPos villagerPos,
            BlockPos workstation,
            int radius,
            TagKey<net.minecraft.block.Block> targetBlockState
        ) {
        BlockPos bestLog = null;
        BlockPos bestApproach = null;
        int r2 = radius * radius;

        Iterable<BlockPos> radiusCoords = RadiusGenerator.radiusCoordinates(villagerPos, radius);
        for (BlockPos pos : radiusCoords) {
            BlockState state = world.getBlockState(pos);
            if (!state.isIn(targetBlockState)) {
                continue; // Skip if not in the target block state
            }
            if (workstation.getSquaredDistance(pos) <= r2) {
                // This is a good position!
                bestApproach = checkTargetLogForAccess(world, pos);
                if (bestApproach != null) {
                    bestLog = pos;
                    break;
                }
            }
        }

        // If not found, search around the workstation
        if (bestLog == null) {
            for (BlockPos pos : RadiusGenerator.radiusCoordinates(workstation, radius)) {
                BlockState state = world.getBlockState(pos);
                if (!state.isIn(targetBlockState)) {
                    continue; // Skip if not in the target block state
                }
                bestApproach = checkTargetLogForAccess(world, pos);
                if (bestApproach != null) {
                    bestLog = pos;
                    break;
                }
            }
        }

        // Move bestApproach down until it finds a solid block
        if (bestLog != null && bestApproach != null) {
            while (bestApproach.getY() > 0 && world.getBlockState(bestApproach.down()).isAir()) {
                bestApproach = bestApproach.down();
            }
        }

        return (bestLog != null && bestApproach != null) ? new BlockPos[] { bestLog, bestApproach } : null;
    }
}
