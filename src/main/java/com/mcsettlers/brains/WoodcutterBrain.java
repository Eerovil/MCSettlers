package com.mcsettlers.brains;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableSet;
import com.mcsettlers.MCSettlers;
import com.mcsettlers.ModMemoryModules;
import com.mcsettlers.utils.RadiusGenerator;

public class WoodcutterBrain extends WorkerBrain {
    public WoodcutterBrain() {
        this.TARGET_BLOCK_STATE = Optional.of(Blocks.OAK_LOG.getDefaultState());
        this.NON_AI_JOBS = ImmutableSet.of(
                "breaking",
                "pillaring",
                "stopping_pillaring");
        this.WANTED_ITEMS = ImmutableSet.of(
                ItemTags.AXES);
    }

    // Raycast from villager to target block, return first log/leaf in the way
    // (excluding target)
    private BlockPos getObstructingLogOrLeaf(VillagerEntity villager, ServerWorld world, BlockPos target) {
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

    private boolean anyItemsToDeposit(VillagerEntity villager) {
        for (ItemStack stack : villager.getInventory()) {
            if (!stack.isEmpty() && stack.getItem() != Items.AIR && stack.getCount() > 0) {
                // If stack is one of villager.gatherableItems(), return true
                if (villager.getVillagerData().profession().value().gatherableItems().contains(stack.getItem())) {
                    return true;
                }
            }
        }
        return false; // No items to deposit
    }

    @Override
    protected void handleJob(
            VillagerEntity villager, ServerWorld world,
            String jobStatus, BlockPos workstation, BlockPos targetLog) {

        Brain<?> brain = villager.getBrain();

        if (jobStatus == "walking") {
            if (!reallyReachedTarget(villager)) {
                return; // Already walking, nothing to do
            }
            startBreakingBlock(villager, world, targetLog);
        } else if (jobStatus == "breaking") {
            keepBreakingBlock(villager, world, targetLog, workstation);
        } else if (jobStatus == "idle") {
            // If idle, we can search for logs
            findNewTarget(villager, world, workstation);
        } else if (jobStatus == "pillaring") {
            keepPillaring(villager, world, targetLog);
        } else if (jobStatus == "stopping_pillaring") {
            keepStoppingPillaring(villager, world, targetLog);
        } else if (jobStatus == "picking_up_blocks") {
            keepPickingUpBlocks(villager, world, workstation);
        } else if (jobStatus == "deposit_items") {
            keepDepositingItems(villager, world, workstation);
        } else if (jobStatus == "stop_deposit_items") {
            stopDepositingItems(villager, world, workstation);
        } else if (jobStatus.startsWith("no_work")) {
            // Set timer for 10 seconds and make the villager idle
            // This is a placeholder; actual implementation would depend on game logic
            long now = world.getTime();
            Optional<Long> noWorkUntil = brain.getOptionalMemory(ModMemoryModules.NO_WORK_UNTIL_TICK);
            if (noWorkUntil.isEmpty()) {
                brain.remember(ModMemoryModules.NO_WORK_UNTIL_TICK, now + 100); // 10 seconds
            } else if (now >= noWorkUntil.get()) {
                brain.forget(ModMemoryModules.NO_WORK_UNTIL_TICK);
                // If items in inventory, set job status to deposit items
                if (world.getRandom().nextBoolean()) {
                    setJobStatus(villager, "idle");
                } else {
                    if (anyItemsToDeposit(villager) && getDepositChest(villager, world, workstation).isPresent()) {
                        startDepositingItems(villager, world, workstation);
                    } else {
                        setJobStatus(villager, "picking_up_blocks");
                    }
                }
                // If very far from workstation, walk to it
                if (villager.getBlockPos().getSquaredDistance(workstation) > 30) {
                    brain.remember(MemoryModuleType.WALK_TARGET,
                            new net.minecraft.entity.ai.brain.WalkTarget(
                                    new net.minecraft.entity.ai.brain.BlockPosLookTarget(workstation),
                                    0.6F,
                                    1 // completion range
                            ));
                }
            }
        } else {
            MCSettlers.LOGGER.warn("[WoodcutterBrain] Unknown job status: " + jobStatus);
            setJobStatus(villager, "no_work_unknown_status");
        }
    }

    protected void findNewTarget(VillagerEntity villager, ServerWorld world, BlockPos workstation) {
        int searchRadius = 20;
        BlockPos villagerPos = villager.getBlockPos();
        BlockPos[] found = findNearbyLogAndApproach(world, villagerPos, workstation, searchRadius);
        BlockPos foundLog = found != null ? found[0] : null;
        BlockPos foundApproach = found != null ? found[1] : null;
        if (foundLog != null) {
            MCSettlers.LOGGER.info("[WoodcutterBrain] Found log at " + foundLog.toShortString()
                    + ", approach at " + foundApproach.toShortString());
            villager.getBrain().remember(ModMemoryModules.TARGET_BREAK_BLOCK, foundLog);
            
            // Ensure approach is walkable (not inside block, on ground)
            BlockPos walkableApproach = foundApproach;
            while (!world.getBlockState(walkableApproach.down()).isSolidBlock(world, walkableApproach.down())
                    && walkableApproach.getY() > 0) {
                walkableApproach = walkableApproach.down();
            }
            // If walk target is close enough, start breaking
            double distSq = villager.squaredDistanceTo(Vec3d.ofCenter(walkableApproach));
            if (distSq < 3 * 3) {
                startBreakingBlock(villager, world, foundLog);
                return;
            }
            walkToPosition(villager, world, walkableApproach, 0.6F);

            setJobStatus(villager, "walking");
        } else {
            MCSettlers.LOGGER.info("[WoodcutterBrain] No logs found in radius " + searchRadius + " around " + villagerPos.toShortString());
            setJobStatus(villager, "no_work_no_logs");
        }
    }

    private void startBreakingBlock(
            VillagerEntity villager, ServerWorld world,
            BlockPos targetLog) {

        Brain<?> brain = villager.getBrain();

        // Start breaking logic?
        if (targetLog != null) {
            double distSq = villager.squaredDistanceTo(Vec3d.ofCenter(targetLog));
            double dist = Math.sqrt(distSq);
            // Only break if close enough to the log and not already set
            if (dist > 6) {
                // Too far still...

                // If it's above the villager, we can pillar up
                if (targetLog.getY() > villager.getBlockPos().getY() + 3) {
                    int squaredZXDistance = (villager.getBlockPos().getX() - targetLog.getX())
                            * (villager.getBlockPos().getX() - targetLog.getX());
                    if (squaredZXDistance < 5 * 5) {
                        System.out.println("[WoodcutterBrain] Villager " + MCSettlers.workerToString(villager)
                                + " starting to pillar up to log at " + targetLog.toShortString());
                        startPillaring(villager, world, targetLog);
                        return;
                    }
                }

                System.out.println("[WoodcutterBrain] Villager " + MCSettlers.workerToString(villager)
                        + " too far from target log at " + targetLog.toShortString() + " (distance: "
                        + String.format("%.2f", dist) + ")");

                brain.forget(ModMemoryModules.TARGET_BREAK_BLOCK);
                brain.forget(ModMemoryModules.BREAK_PROGRESS);
                setJobStatus(villager, "no_work_too_far_from_log");
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

            // Animate breaking progress (0-10)
            world.setBlockBreakingInfo(villager.getId(), targetLog, 0);

            // Remember the target block and start breaking

            brain.remember(ModMemoryModules.BREAK_PROGRESS, 0f);
            setJobStatus(villager, "breaking");
        }
    }

    private void keepBreakingBlock(
            VillagerEntity villager, ServerWorld world, BlockPos targetLog, BlockPos workstation) {
        // Continue breaking logic
        Brain<?> brain = villager.getBrain();
        Float breakProgress = brain.getOptionalMemory(ModMemoryModules.BREAK_PROGRESS).orElse(0f);
        // Animate breaking progress (0-10)
        world.setBlockBreakingInfo(villager.getId(), targetLog, Math.round(breakProgress));
        // Look at the block
        lookAtBlock(villager, targetLog);

        if (breakProgress < 10) {
            int ticksToBreak = blockBreakTime(targetLog, villager);
            float progressPerTick = 10f / ticksToBreak;
            brain.remember(ModMemoryModules.BREAK_PROGRESS, breakProgress + progressPerTick);
        } else {
            System.out.println("[WoodcutterBrain] Actually breaking block at " + targetLog.toShortString());
            world.breakBlock(targetLog, true, villager);
            world.setBlockBreakingInfo(villager.getId(), targetLog, -1); // clear animation

            brain.forget(ModMemoryModules.TARGET_BREAK_BLOCK);
            brain.forget(ModMemoryModules.BREAK_PROGRESS);
            // if pillaring, keep pillaring
            if (brain.getOptionalMemory(ModMemoryModules.PILLAR_BLOCKS).isPresent()
                    && brain.getOptionalMemory(ModMemoryModules.PILLAR_BLOCKS).get().size() > 0) {
                if (brain.getOptionalMemory(ModMemoryModules.KEEP_PILLARING).orElse(false)) {
                    setJobStatus(villager, "pillaring");
                } else {
                    setJobStatus(villager, "stopping_pillaring");
                }
            } else {
                // If lots of stuff in inventory, set job status to deposit items
                int itemCount = 0;
                for (int i = 0; i < villager.getInventory().size(); i++) {
                    net.minecraft.item.ItemStack stack = villager.getInventory().getStack(i);
                    if (!stack.isEmpty()) {
                        itemCount += stack.getCount();
                    }
                }
                if (itemCount > 10) {
                    startDepositingItems(villager, world, workstation);
                } else {
                    setJobStatus(villager, "idle");
                }
            }
        }
    }

    private BlockPos checkTargetLogForAccess(ServerWorld world, BlockPos targetLog) {
        // Check if the target log is accessible (not obstructed by blocks)
        // This is a placeholder; actual implementation would depend on game logic
        // For now, we assume all logs are accessible
        BlockState state = world.getBlockState(targetLog);
        if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) {

            BlockPos approach = null;
            for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {

                BlockPos adj = targetLog.offset(dir);
                BlockState adjState = world.getBlockState(adj);
                if (!adjState.isSolidBlock(world, adj)) {
                    approach = adj;
                    return approach;
                }
            }
        }
        return null;
    }

    // Find the nearest log/leaf and its approach air block
    private BlockPos[] findNearbyLogAndApproach(
            ServerWorld world,
            BlockPos villagerFeetPos,
            BlockPos workstation,
            int radius) {
        int r2 = radius * radius;
        BlockPos villagerPos = villagerFeetPos.up();
        HashMap<BlockPos, Boolean> handledCoords = new HashMap<>();
        // Get a list of all possible coordinates.
        Iterable<BlockPos> villagerRadiusCoords = RadiusGenerator.radiusCoordinates(villagerPos, 9, pos -> {
            BlockState state = world.getBlockState(pos);
            handledCoords.put(pos, true);
            if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES)) {
                if (workstation.getSquaredDistance(pos) <= r2) {
                    return true; // Only consider positions within the radius
                }
            }
            return false;
        });

        // Check all coordinates for accessible logs (villager radius, then workstation
        // radius)
        boolean anyLogs = false;
        for (BlockPos pos : villagerRadiusCoords) {
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
        for (BlockPos pos : villagerRadiusCoords) {
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

        // If no logs or leaves found, return null
        MCSettlers.LOGGER.info("[WoodcutterBrain] No logs or leaves found in radius " + radius + " around "
                + villagerPos.toShortString() + " or workstation " + workstation.toShortString());
        return null;

    }

    private void startPillaring(
            VillagerEntity villager, ServerWorld world, BlockPos targetLog) {
        // Start pillaring logic
        // This is a placeholder; actual implementation would depend on game logic
        // For now, we assume the villager can always pillar up
        // Initialize pillar blocks with empty list
        setJobStatus(villager, "pillaring");
        villager.setAiDisabled(true);
        villager.getBrain().remember(ModMemoryModules.KEEP_PILLARING, true);
    }

    private void keepPillaring(
            VillagerEntity villager, ServerWorld world, BlockPos targetLog) {

        // Only run this every 20 ticks
        if (villager.getWorld().getTime() % 20 != 0) {
            return;
        }
        Brain<?> brain = villager.getBrain();
        villager.setAiDisabled(true);
        // Continue pillaring logic
        // This is a placeholder; actual implementation would depend on game logic
        // For now, we assume the villager can always continue pillaring
        List<BlockPos> pillarBlocks = brain.getOptionalMemory(ModMemoryModules.PILLAR_BLOCKS).orElse(null);

        if (pillarBlocks == null) {
            pillarBlocks = new ArrayList<>();
        }

        BlockPos villagerPos = villager.getBlockPos();
        // Find log with y coordinate greater than villager's aand within radius 6 in X
        // and Z
        Iterable<BlockPos> nearbyLogs = RadiusGenerator.radiusCoordinates(villagerPos, 3, pos -> {
            BlockState state = world.getBlockState(pos);
            return (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.LEAVES))
                    && pos.getY() > villagerPos.getY();
        });

        // If found, mark it as the target log
        for (BlockPos pos : nearbyLogs) {
            BlockState state = world.getBlockState(pos);
            if (state.isIn(BlockTags.LOGS)) {
                targetLog = pos;
            } else if (state.isIn(BlockTags.LEAVES)) {
                // If leaf is directly above the villager, use it as target
                if (pos.getX() == villagerPos.getX() && pos.getZ() == villagerPos.getZ()
                        && pos.getY() > villagerPos.getY()) {
                    targetLog = pos;
                } else {
                    continue;
                }
            }

            brain.remember(ModMemoryModules.TARGET_BREAK_BLOCK, pos);
            MCSettlers.LOGGER.info("[WoodcutterBrain] Found log at " + pos.toShortString()
                    + " while pillaring up to log at " + targetLog.toShortString());
            startBreakingBlock(villager, world, targetLog);
            return;
        }

        // If leaf block exists directly above the villager, use it as target

        // check if we should stop pillaring
        // We find all log blocks within a radius of 5 blocks and ANY Y above the
        // villager
        // If nothing found, stop pillaring
        boolean foundAnyLogAbove = false;
        for (int extraY = 0; extraY < 5; extraY++) {
            Iterable<BlockPos> nearbyLogsWithExtraY = RadiusGenerator.radiusCoordinates(villagerPos.up(extraY), 4,
                    pos -> {
                        BlockState state = world.getBlockState(pos);
                        return state.isIn(BlockTags.LOGS);
                    });
            if (nearbyLogsWithExtraY.iterator().hasNext()) {
                foundAnyLogAbove = true;
                break; // Found at least one log above, no need to continue
            }
        }
        if (!foundAnyLogAbove) {
            // No logs found above, stop pillaring
            MCSettlers.LOGGER.info("[WoodcutterBrain] No logs found above villager " + MCSettlers.workerToString(villager)
                    + ", stopping pillaring.");
            setJobStatus(villager, "stopping_pillaring");
            brain.remember(ModMemoryModules.KEEP_PILLARING, false);
            return;
        }

        // Look down
        villager.setPitch(90); // Look straight down

        // Create dirt block under the villager
        BlockPos dirtPos = villagerPos;
        if (world.getBlockState(dirtPos).isReplaceable()) {
            world.setBlockState(dirtPos, net.minecraft.block.Blocks.DIRT.getDefaultState());
            MCSettlers.LOGGER.info("[WoodcutterBrain] Placed dirt block at " + dirtPos.toShortString());

            pillarBlocks.add(dirtPos); // Add the dirt block to the pillar blocks memory
            brain.remember(ModMemoryModules.PILLAR_BLOCKS, pillarBlocks);

            // Move villager to the block
            villager.setPos(villager.getX(), villager.getY() + 1, villager.getZ());
        } else {
            MCSettlers.LOGGER.warn("[WoodcutterBrain] Cannot place dirt block at " + dirtPos.toShortString());
        }
    }

    public void keepStoppingPillaring(
            VillagerEntity villager, ServerWorld world, BlockPos targetLog) {
        // Stop pillaring logic
        // Pop latest pillar blocks from memory
        // And delete it
        Brain<?> brain = villager.getBrain();
        List<BlockPos> pillarBlocks = brain.getOptionalMemory(ModMemoryModules.PILLAR_BLOCKS).orElse(null);
        if (pillarBlocks != null && !pillarBlocks.isEmpty()) {
            BlockPos lastBlock = pillarBlocks.remove(pillarBlocks.size() - 1);
            brain.remember(ModMemoryModules.TARGET_BREAK_BLOCK, lastBlock);
            // Move villager to the last block
            villager.setPos(villager.getX(), lastBlock.getY() + 1, villager.getZ());
            startBreakingBlock(villager, world, lastBlock);
        } else {
            // No more pillar blocks, stop pillaring
            setJobStatus(villager, "idle");
            villager.setAiDisabled(false);
        }
    }
}
