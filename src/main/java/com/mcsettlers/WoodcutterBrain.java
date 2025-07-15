package com.mcsettlers;

import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
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
        // Highlight walkTarget and targetLog blocks for debugging
        if (walkTarget != null) {
            // Use a unique entity ID offset for walkTarget highlight
            world.setBlockBreakingInfo(villager.getId() + 1000, walkTarget, 1);
        } else {
            // Clear highlight if not present
            world.setBlockBreakingInfo(villager.getId() + 1000, BlockPos.ORIGIN, -1);
        }
        if (targetLog != null) {
            // Use a unique entity ID offset for targetLog highlight
            world.setBlockBreakingInfo(villager.getId() + 2000, targetLog, 2);
        } else {
            // Clear highlight if not present
            world.setBlockBreakingInfo(villager.getId() + 2000, BlockPos.ORIGIN, -1);
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
                    while (!world.getBlockState(walkableApproach.down()).isSolidBlock(world, walkableApproach.down())
                            && walkableApproach.getY() > 0) {
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
                            ));
                } else {
                    brain.forget(ModMemoryModules.TARGET_LOG);
                    // Walk to workstation if no log found
                    brain.remember(MemoryModuleType.WALK_TARGET,
                            new net.minecraft.entity.ai.brain.WalkTarget(
                                    new net.minecraft.entity.ai.brain.BlockPosLookTarget(workstation),
                                    0.6F,
                                    1));
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
        // Smooth block breaking animation using a progress counter in memory
        if (breakBlock != null) {
            // Get or initialize breaking progress
            int breakProgress = brain.getOptionalMemory(ModMemoryModules.BREAK_PROGRESS).orElse(0);
            System.out.println("[WoodcutterBrain] Villager " + villager.getUuidAsString() + " breaking block at "
                    + breakBlock.toShortString() + " (progress: " + breakProgress + ")");
            // Axe holding logic
            net.minecraft.item.ItemStack axeStack = findAxeInInventory(villager);
            if (!axeStack.isEmpty()) {
                System.out.println("[WoodcutterBrain] Villager holding axe: " + axeStack);
                villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, axeStack);
            }
            Vec3d blockCenter = Vec3d.ofCenter(breakBlock);
            villager.getLookControl().lookAt(blockCenter.x, blockCenter.y, blockCenter.z);
            // Animate breaking progress (0-10)
            world.setBlockBreakingInfo(villager.getId(), breakBlock, breakProgress);
            villager.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            // Block wandering while breaking
            if (breakProgress == 0) {
                brain.forget(MemoryModuleType.WALK_TARGET);
            }
            if (breakProgress < 10) {
                brain.remember(ModMemoryModules.BREAK_PROGRESS, breakProgress + 1);
            } else {
                System.out.println("[WoodcutterBrain] Actually breaking block at " + breakBlock.toShortString());
                world.breakBlock(breakBlock, true, villager);
                world.setBlockBreakingInfo(villager.getId(), breakBlock, -1); // clear animation
                villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, net.minecraft.item.ItemStack.EMPTY);
                brain.forget(ModMemoryModules.TARGET_BREAK_BLOCK);
                brain.forget(ModMemoryModules.TARGET_LOG); // clear after breaking
                brain.forget(ModMemoryModules.BREAK_PROGRESS);
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
            int radius) {
        int r2 = radius * radius;
        HashMap<BlockPos, Boolean> handledCoords = new HashMap<>();
        // Get a list of all possible coordinates.
        Iterable<BlockPos> villagerRadiusCoords = RadiusGenerator.radiusCoordinates(villagerPos, radius, pos -> {
            BlockState state = world.getBlockState(pos);
            handledCoords.put(pos, true);
            if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) {
                if (workstation.getSquaredDistance(pos) <= r2) {
                    return true; // Only consider positions within the radius
                }
            }
            return false;
        });
        Iterable<BlockPos> workstationRadiusCoords = RadiusGenerator.radiusCoordinates(workstation, radius, pos -> {
            if (handledCoords.containsKey(pos)) {
                return false; // Skip already handled positions
            }
            BlockState state = world.getBlockState(pos);
            if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) {
                return true;
            }
            return false;
        });

        // Check all coordinates for accessible logs (villager radius, then workstation
        // radius)
        for (BlockPos pos : concat(villagerRadiusCoords, workstationRadiusCoords)) {
            BlockState state = world.getBlockState(pos);
            if (state.isIn(BlockTags.LOGS)) {
                BlockPos approach = checkTargetLogForAccess(world, pos);
                if (approach != null) {
                    MCSettlers.LOGGER.info("[WoodcutterBrain] Found log at " + pos.toShortString()
                            + ", approach at " + approach.toShortString());
                    return new BlockPos[] { pos, approach };
                }
            }
        }

        // Check all coordinates for leaves that are attached to a log
        for (BlockPos pos : concat(villagerRadiusCoords, workstationRadiusCoords)) {
            BlockState state = world.getBlockState(pos);
            if (state.isIn(BlockTags.LEAVES)) {
                // Check if the leaf block is attached to a log
                boolean hasLogNeighbor = false;
                for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
                    BlockPos neighborPos = pos.offset(dir);
                    BlockState neighborState = world.getBlockState(neighborPos);
                    if (neighborState.isIn(BlockTags.LOGS)) {
                        hasLogNeighbor = true;
                        break; // Found a log neighbor, no need to check further
                    }
                }
                if (!hasLogNeighbor) {
                    continue; // Skip leaves not attached to a log
                }
                BlockPos approach = checkTargetLogForAccess(world, pos);
                if (approach != null) {
                    MCSettlers.LOGGER.info("[WoodcutterBrain] Found attached leaf at " + pos.toShortString()
                            + ", approach at " + approach.toShortString());
                    return new BlockPos[] { pos, approach };
                }
            }
        }

        // Find any leaf
        for (BlockPos pos : concat(villagerRadiusCoords, workstationRadiusCoords)) {
            BlockState state = world.getBlockState(pos);
            if (state.isIn(BlockTags.LEAVES)) {
                BlockPos approach = checkTargetLogForAccess(world, pos);
                if (approach != null) {
                    MCSettlers.LOGGER.info("[WoodcutterBrain] Found leaf at " + pos.toShortString()
                            + ", approach at " + approach.toShortString());
                    return new BlockPos[] { pos, approach };
                }
            }
        }

        // If no logs or leaves found, return null
        MCSettlers.LOGGER.info("[WoodcutterBrain] No logs or leaves found in radius " + radius + " around "
                + villagerPos.toShortString() + " or workstation " + workstation.toShortString());
        return null;

    }

    // Utility to concatenate two iterables
    private static <T> Iterable<T> concat(final Iterable<T> first, final Iterable<T> second) {
        return () -> new java.util.Iterator<T>() {
            java.util.Iterator<T> current = first.iterator();
            java.util.Iterator<T> next = second.iterator();

            @Override
            public boolean hasNext() {
                return current.hasNext() || next.hasNext();
            }

            @Override
            public T next() {
                if (current.hasNext()) {
                    return current.next();
                } else {
                    return next.next();
                }
            }
        };
    }
}
