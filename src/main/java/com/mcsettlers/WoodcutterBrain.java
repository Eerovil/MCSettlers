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

    // Raycast from villager to target block, return first log/leaf in the way
    // (excluding target)
    private static BlockPos getObstructingLogOrLeaf(VillagerEntity villager, ServerWorld world, BlockPos target) {
        Vec3d eyePos = villager.getPos().add(0, villager.getStandingEyeHeight(), 0);
        Vec3d targetCenter = Vec3d.ofCenter(target);
        Vec3d dir = targetCenter.subtract(eyePos).normalize();
        double distance = eyePos.distanceTo(targetCenter);
        for (double step = 0.5; step < distance; step += 0.5) {
            Vec3d pos = eyePos.add(dir.multiply(step));
            BlockPos blockPos = BlockPos.ofFloored(pos);
            if (blockPos.equals(target))
                continue; // skip the target itself
            BlockState state = world.getBlockState(blockPos);
            if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) {
                return blockPos;
            }
        }
        return null;
    }

    private static void setJobStatus(Brain<?> brain, VillagerEntity villager, String status) {
        brain.remember(ModMemoryModules.JOB_STATUS, status);
        villager.setCustomName(net.minecraft.text.Text.of(status));
        villager.setCustomNameVisible(true);
        MCSettlers.LOGGER.info("[WoodcutterBrain] Set job status to " + status + " for villager "
                + villager.getUuidAsString());
    }

    public static void tick(VillagerEntity villager, ServerWorld world) {
        long tickStart = System.nanoTime();
        Brain<?> brain = villager.getBrain();
        String jobStatus = brain.getOptionalMemory(ModMemoryModules.JOB_STATUS)
                .orElse("unknown");
        
        if (jobStatus == null || jobStatus.isEmpty() || jobStatus.equals("unknown")) {
            // If job status is unknown, set it to idle
            setJobStatus(brain, villager, "idle");
            jobStatus = "idle"; // Update local variable to avoid repeated lookups
        }

        // Read memory values
        BlockPos workstation = brain.getOptionalMemory(MemoryModuleType.JOB_SITE)
                .map(GlobalPos::pos)
                .orElse(null);
        Optional<BlockPos> optionalTarget = brain.getOptionalMemory(ModMemoryModules.TARGET_BREAK_BLOCK);
        BlockPos targetLog = optionalTarget != null ? optionalTarget.orElse(null) : null;
        Optional<WalkTarget> optionalWalkTarget = brain.getOptionalMemory(MemoryModuleType.WALK_TARGET);
        BlockPos walkTarget = null;
        if (optionalWalkTarget.isPresent()) {
            WalkTarget wt = optionalWalkTarget.get();
            if (wt != null && wt.getLookTarget() != null) {
                walkTarget = wt.getLookTarget().getBlockPos();
            }
        }

        if (workstation == null)
            return;

        if (jobStatus == "walking") {
            if (walkTarget != null) {
                return; // Already walking, nothing to do
            }
            startBreakingBlock(villager, world, workstation, targetLog, brain);
        } else if (jobStatus == "breaking") {
            keepBreakingBlock(villager, world, targetLog, brain);
        } else if (jobStatus == "idle") {
            // If idle, we can search for logs
            findNewTarget(villager, world, workstation, brain);
        } else if (jobStatus == "no_work") {
            // Set timer for 10 seconds and make the villager idle
            // This is a placeholder; actual implementation would depend on game logic
        }

        long tickEnd = System.nanoTime();
        if (tickEnd - tickStart > 500_000) { // Only log if tick is slow (>0.5ms)
            MCSettlers.LOGGER.info("[WoodcutterBrain] TOTAL tick took " + ((tickEnd - tickStart) / 1000) + "us");
        }
    }

    private static void findNewTarget(VillagerEntity villager, ServerWorld world, BlockPos workstation,
            Brain<?> brain) {
        int searchRadius = 10;
        BlockPos villagerPos = villager.getBlockPos();
        BlockPos[] found = findNearbyLogAndApproach(world, villagerPos, workstation, searchRadius);
        BlockPos foundLog = found != null ? found[0] : null;
        BlockPos foundApproach = found != null ? found[1] : null;
        if (foundLog != null) {
            MCSettlers.LOGGER.info("[WoodcutterBrain] Found log at " + foundLog.toShortString()
                    + ", approach at " + foundApproach.toShortString());
            brain.remember(ModMemoryModules.TARGET_BREAK_BLOCK, foundLog);
            // Ensure approach is walkable (not inside block, on ground)
            BlockPos walkableApproach = foundApproach;
            while (!world.getBlockState(walkableApproach.down()).isSolidBlock(world, walkableApproach.down())
                    && walkableApproach.getY() > 0) {
                walkableApproach = walkableApproach.down();
            }
            // Set walk target with reasonable completion range and duration
            brain.remember(MemoryModuleType.WALK_TARGET,
                    new net.minecraft.entity.ai.brain.WalkTarget(
                            new net.minecraft.entity.ai.brain.BlockPosLookTarget(walkableApproach),
                            0.6F,
                            1 // completion range
                    ));

            setJobStatus(brain, villager, "walking");
        } else {
            setJobStatus(brain, villager, "no_work");
        }
    }

    private static void startBreakingBlock(
            VillagerEntity villager, ServerWorld world, BlockPos workstation,
            BlockPos targetLog, Brain<?> brain) {

        // Start breaking logic?
        if (targetLog != null) {
            double distSq = villager.squaredDistanceTo(Vec3d.ofCenter(targetLog));
            double dist = Math.sqrt(distSq);
            // Only break if close enough to the log and not already set
            if (dist > 6) {
                // Too far still...
                System.out.println("[WoodcutterBrain] Villager " + villager.getUuidAsString()
                        + " too far from target log at " + targetLog.toShortString() + " (distance: "
                        + String.format("%.2f", dist) + ")");

                brain.forget(ModMemoryModules.TARGET_BREAK_BLOCK);
                brain.forget(ModMemoryModules.BREAK_PROGRESS);
                setJobStatus(brain, villager, "no_work");
                return;
            }
            Vec3d blockCenter = Vec3d.ofCenter(targetLog);
            villager.getLookControl().lookAt(blockCenter.x, blockCenter.y, blockCenter.z);

            BlockPos obstructing = getObstructingLogOrLeaf(villager, world, targetLog);
            if (obstructing != null && !obstructing.equals(targetLog)) {
                // If the obstructing block is different, update the break target
                targetLog = obstructing;
                brain.remember(ModMemoryModules.TARGET_BREAK_BLOCK, obstructing);
                targetLog = obstructing;
            }
            // Get or initialize breaking progress
            // Axe holding logic
            net.minecraft.item.ItemStack axeStack = findAxeInInventory(villager);
            if (!axeStack.isEmpty()) {
                System.out.println("[WoodcutterBrain] Villager holding axe: " + axeStack);
                villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, axeStack);
            }

            // Animate breaking progress (0-10)
            world.setBlockBreakingInfo(villager.getId(), targetLog, 0);
            villager.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            // Remember the target block and start breaking

            brain.remember(ModMemoryModules.BREAK_PROGRESS, 0);
            setJobStatus(brain, villager, "breaking");
        }
    }

    private static void keepBreakingBlock(
            VillagerEntity villager, ServerWorld world, BlockPos targetLog, Brain<?> brain) {
        // Continue breaking logic
        int breakProgress = brain.getOptionalMemory(ModMemoryModules.BREAK_PROGRESS).orElse(0);
        // Animate breaking progress (0-10)
        world.setBlockBreakingInfo(villager.getId(), targetLog, breakProgress);

        if (breakProgress < 10) {
            brain.remember(ModMemoryModules.BREAK_PROGRESS, breakProgress + 1);
        } else {
            System.out.println("[WoodcutterBrain] Actually breaking block at " + targetLog.toShortString());
            world.breakBlock(targetLog, true, villager);
            world.setBlockBreakingInfo(villager.getId(), targetLog, -1); // clear animation
            villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, net.minecraft.item.ItemStack.EMPTY);
            brain.forget(ModMemoryModules.TARGET_BREAK_BLOCK);
            brain.forget(ModMemoryModules.BREAK_PROGRESS);
            setJobStatus(brain, villager, "idle");
        }
    }

    // Show debug info for all related memory values
    private static void showDebugInfo(VillagerEntity villager, String status) {
        StringBuilder sb = new StringBuilder();
        if (status != null) {
            sb.append(status);
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
        boolean anyLogs = false;
        for (BlockPos pos : concat(villagerRadiusCoords, workstationRadiusCoords)) {
            BlockState state = world.getBlockState(pos);
            if (state.isIn(BlockTags.LOGS)) {
                anyLogs = true;
                BlockPos approach = checkTargetLogForAccess(world, pos);
                if (approach != null) {
                    MCSettlers.LOGGER.info("[WoodcutterBrain] Found log at " + pos.toShortString()
                            + ", approach at " + approach.toShortString());
                    return new BlockPos[] { pos, approach };
                }
            }
        }

        // If no logs in range, just give up
        if (!anyLogs) {
            MCSettlers.LOGGER.info("[WoodcutterBrain] No logs found in radius " + radius + " around "
                    + villagerPos.toShortString() + " or workstation " + workstation.toShortString());
            return null;
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
