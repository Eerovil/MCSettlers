package com.mcsettlers.brains;

import java.util.Optional;

import com.google.common.collect.ImmutableSet;
import com.mcsettlers.MCSettlers;
import com.mcsettlers.ModMemoryModules;
import com.mcsettlers.utils.RadiusGenerator;
import com.mcsettlers.utils.SharedMemories;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ai.brain.Brain;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class ForesterBrain extends WorkerBrain {
    // Implement Forester-specific behavior here

    public ForesterBrain() {
        this.WANTED_ITEM_TAGS = ImmutableSet.of(
                ItemTags.SAPLINGS);
    }

    @Override
    protected void handleJob(
            VillagerEntity villager, ServerWorld world,
            String jobStatus, BlockPos workstation, BlockPos targetLog, SharedMemories sharedMemories) {

        Brain<?> brain = villager.getBrain();

        if (jobStatus.equals("walking_to_plant")) {
            if (!reallyReachedTarget(world, villager)) {
                return;
            }
            startPlanting(villager, world, workstation, targetLog);
        } else if (jobStatus.equals("picking_up_blocks")) {
            keepPickingUpBlocks(villager, world, workstation);
        } else if (jobStatus.equals("deposit_items")) {
            keepDepositingItems(villager, world, workstation, sharedMemories);
        } else if (jobStatus.equals("stop_deposit_items")) {
            stopDepositingItems(villager, world, workstation);
            // If inventory is empty, set to no_work
            if (villager.getInventory().isEmpty()) {
                setJobStatus(villager, "no_work_after_deposit");
            } else {
                setJobStatus(villager, "idle");
            }
        } else if (jobStatus.startsWith("no_work")) {
            startHoldingItem(villager, ItemStack.EMPTY);
            // Set timer for 10 seconds and make the villager idle
            // This is a placeholder; actual implementation would depend on game logic
            long now = world.getTime();
            Optional<Long> noWorkUntil = brain.getOptionalMemory(ModMemoryModules.NO_WORK_UNTIL_TICK);
            if (noWorkUntil.isEmpty()) {
                brain.remember(ModMemoryModules.NO_WORK_UNTIL_TICK, now + 100); // 10 seconds
            } else if (now >= noWorkUntil.get()) {
                brain.forget(ModMemoryModules.NO_WORK_UNTIL_TICK);
                startDepositingItems(villager, world, workstation, sharedMemories);
            }
        } else if (jobStatus.equals("planting")) {
            lookAtBlock(villager, targetLog);
            setJobStatus(villager, "idle");
            pauseForMS(villager, world, 1000);
        } else if (jobStatus.equals("idle")) {
            // If inventory is empty, deposit items
            if (villager.getInventory().isEmpty()) {
                startDepositingItems(villager, world, workstation, sharedMemories);
            } else {
                findNewPlantingTarget(villager, world, workstation, sharedMemories);
            }
        } else {
            MCSettlers.LOGGER.warn("[Forester] Unknown job status: " + jobStatus);
            setJobStatus(villager, "no_work_unknown_status");
        }
    }

    protected void findNewPlantingTarget(
            VillagerEntity villager, ServerWorld world, BlockPos workstation, SharedMemories sharedMemories) {
        MCSettlers.LOGGER.info("Finding new planting target for villager: {}", MCSettlers.workerToString(villager));
        int r2 = 15 * 15;
        // Logic to find a new planting target
        BlockPos villagerPos = villager.getBlockPos();
        // Get a list of all possible coordinates.
        Iterable<BlockPos> villagerRadiusCoords = RadiusGenerator.radiusCoordinates(villagerPos, workstation, 9, pos -> {
            BlockState state = world.getBlockState(pos);
            // Position X and Z must be divisible by 3 to ensure even spacing
            if (pos.getX() % 3 != 0 || pos.getZ() % 3 != 0) {
                return false; // Skip positions not divisible by 3
            }
            if (!state.isIn(BlockTags.DIRT)) {
                return false; // Skip positions that are not dirt
            }
            // Must not be next to an interactable block
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue; // Skip the current position
                    BlockEntity nearBlockEntity = world.getBlockEntity(pos.up().add(dx, 0, dz));
                    if (nearBlockEntity != null) {
                        return false; // Skip positions next to interactable blocks
                    }
                }
            }
            BlockState aboveBlock = world.getBlockState(pos.up());
            if (aboveBlock.isReplaceable() && !aboveBlock.isIn(BlockTags.SAPLINGS)) {
                // Check if the position is within the radius of the workstation
                // and is not already occupied by another sapling

                if (workstation.getSquaredDistance(pos) <= r2) {
                    return true; // Only consider positions within the radius
                }
            }
            return false;
        });
        Brain<?> brain = villager.getBrain();
        for (BlockPos pos : villagerRadiusCoords) {
            if (!sharedMemories.reserveTargetBlock(world, villager, pos)) {
                continue; // Skip if the target block is already reserved
            }
            brain.remember(ModMemoryModules.TARGET_BREAK_BLOCK, pos.up());

            // Walk to the position above the dirt block
            walkToPosition(villager, world, pos.up(), 0.6F);
            // Set the job status to walking, forester knows
            // that after this job is done, it will start planting
            setJobStatus(villager, "walking_to_plant");
            return;
        }

        setJobStatus(villager, "no_work");
        MCSettlers.LOGGER.info("No suitable planting target found for villager: {}", MCSettlers.workerToString(villager));
    }

    protected ItemStack getSapling(VillagerEntity villager) {
        // Get a sapling item from inventory and remove it
        for (int i = 0; i < villager.getInventory().size(); i++) {
            ItemStack stack = villager.getInventory().getStack(i);
            if (stack.isIn(ItemTags.SAPLINGS) && stack.getCount() >= 1) {
                // Create a new stack with a single sapling
                ItemStack saplingStack = stack.copy();
                saplingStack.setCount(1);
                villager.getInventory().removeStack(i, 1);
                startHoldingItem(villager, ItemStack.EMPTY); // Clear the held item
                return saplingStack;
            }
        }
        return ItemStack.EMPTY;
    }

    protected void startPlanting(
            VillagerEntity villager, ServerWorld world, BlockPos workstation, BlockPos targetLog) {
        // Logic to start planting trees
        // This is a placeholder; actual implementation would depend on game logic
        setJobStatus(villager, "planting");

        MCSettlers.LOGGER.info("Villager {} is starting to plant trees at {}", MCSettlers.workerToString(villager), targetLog);

        // create sapling at targetLog
        if (targetLog != null) {
            BlockState saplingState = world.getBlockState(targetLog);
            if (saplingState.isReplaceable()) {
                Item saplingItem = getSapling(villager).getItem();
                if (saplingItem instanceof net.minecraft.item.BlockItem) {
                    net.minecraft.block.Block saplingBlock = ((net.minecraft.item.BlockItem) saplingItem).getBlock();
                    world.setBlockState(targetLog, saplingBlock.getDefaultState());
                }
            } else {
                MCSettlers.LOGGER.warn("Target log position {} is not replaceable for planting sapling.", saplingState);
            }
        }
    }

    @Override
    protected void getBestToolFromChest(
        ServerWorld world,
            ChestBlockEntity chest, VillagerEntity villager, BlockPos workstation) {
        // Instead of getting a tool, we want to get a single sapling
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (stack.isIn(ItemTags.SAPLINGS) && stack.getCount() >= 1) {
                // Create a new stack with a single sapling
                ItemStack saplingStack = stack.copy();
                saplingStack.setCount(1);
                chest.removeStack(i, 1);
                villager.getInventory().addStack(saplingStack);
                startHoldingItem(villager, saplingStack);
                return;
            }
        }
    }
}
