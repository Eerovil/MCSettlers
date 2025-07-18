package com.mcsettlers.brains;

import java.util.Optional;

import com.mcsettlers.MCSettlers;
import com.mcsettlers.ModMemoryModules;
import com.mcsettlers.utils.RadiusGenerator;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class ForesterBrain extends WorkerBrain {
    // Implement Forester-specific behavior here

    @Override
    protected void handleJob(
        VillagerEntity villager, ServerWorld world, Brain<?> brain,
        String jobStatus, BlockPos workstation, BlockPos targetLog, BlockPos walkTarget) {

        if (jobStatus == "walking") {
            if (walkTarget != null) {
                return; // Already walking, nothing to do
            }
            startPlanting(villager, world, brain, workstation, targetLog);
        } else if (jobStatus == "picking_up_blocks") {
            keepPickingUpBlocks(villager, world, brain, workstation);
        } else if (jobStatus == "deposit_items") {
            keepDepositingItems(villager, world, brain, workstation);
        } else if (jobStatus == "stop_deposit_items") {
            stopDepositingItems(villager, world, brain, workstation);
            // If inventory is empty, set to no_work
            if (villager.getInventory().isEmpty()) {
                setJobStatus(brain, villager, "no_work");
            }
        } else if (jobStatus == "no_work") {
            // Set timer for 10 seconds and make the villager idle
            // This is a placeholder; actual implementation would depend on game logic
            long now = world.getTime();
            Optional<Long> noWorkUntil = brain.getOptionalMemory(ModMemoryModules.NO_WORK_UNTIL_TICK);
            if (noWorkUntil.isEmpty()) {
                brain.remember(ModMemoryModules.NO_WORK_UNTIL_TICK, now + 100); // 10 seconds
            } else if (now >= noWorkUntil.get()) {
                brain.forget(ModMemoryModules.NO_WORK_UNTIL_TICK);
                setJobStatus(brain, villager, "deposit_items");
            }
        } else if (jobStatus == "planting") {
            lookAtBlock(villager, targetLog);
            setJobStatus(brain, villager, "idle");
            pauseForMS(world, brain, 1000);
        } else if (jobStatus == "idle") {
            // If inventory is empty, deposit items
            if (villager.getInventory().isEmpty()) {
                setJobStatus(brain, villager, "deposit_items");
            } else {
                findNewPlantingTarget(villager, world, brain, workstation);
            }
        }
    }

    protected void findNewPlantingTarget(
        VillagerEntity villager, ServerWorld world, Brain<?> brain, BlockPos workstation)
    {
        MCSettlers.LOGGER.info("Finding new planting target for villager: {}", villager.getUuid());
        int r2 = 15 * 15;
        // Logic to find a new planting target
        BlockPos villagerPos = villager.getBlockPos();
        // Get a list of all possible coordinates.
        Iterable<BlockPos> villagerRadiusCoords = RadiusGenerator.radiusCoordinates(villagerPos, 9, pos -> {
            BlockState state = world.getBlockState(pos);
            // Position X and Z must be divisible by 2 to ensure even spacing
            if (pos.getX() % 2 != 0 || pos.getZ() % 2 != 0) {
                return false; // Skip positions not divisible by 2
            }
            if (state.isIn(BlockTags.DIRT)) {
                BlockState aboveBlock = world.getBlockState(pos.up());
                if (aboveBlock.isReplaceable() && !aboveBlock.isIn(BlockTags.SAPLINGS)) {
                    // Check if the position is within the radius of the workstation
                    // and is not already occupied by another sapling
                    MCSettlers.LOGGER.info("Good block state found: {}", aboveBlock);

                    if (workstation.getSquaredDistance(pos) <= r2) {
                        return true; // Only consider positions within the radius
                    }
                }
            }
            return false;
        });
        for (BlockPos pos : villagerRadiusCoords) {
            brain.remember(ModMemoryModules.TARGET_BREAK_BLOCK, pos.up());

            // Set walk target with reasonable completion range and duration
            brain.remember(MemoryModuleType.WALK_TARGET,
                    new net.minecraft.entity.ai.brain.WalkTarget(
                            new net.minecraft.entity.ai.brain.BlockPosLookTarget(pos.up()),
                            0.6F,
                            1 // completion range
                    ));

            setJobStatus(brain, villager, "walking");
            return;
        }

        setJobStatus(brain, villager, "no_work");
        MCSettlers.LOGGER.info("No suitable planting target found for villager: {}", villager.getUuid());
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
                return saplingStack;
            }
        }
        return ItemStack.EMPTY;
    }

    protected void startPlanting(
        VillagerEntity villager, ServerWorld world, Brain<?> brain,
        BlockPos workstation, BlockPos targetLog) {
        // Logic to start planting trees
        // This is a placeholder; actual implementation would depend on game logic
        setJobStatus(brain, villager, "planting");

        MCSettlers.LOGGER.info("Villager {} is starting to plant trees at {}", villager.getUuid(), targetLog);

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
            ChestBlockEntity chest, VillagerEntity villager) {
        // Instead of getting a tool, we want to get a single sapling
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (stack.isIn(ItemTags.SAPLINGS) && stack.getCount() >= 1) {
                // Create a new stack with a single sapling
                ItemStack saplingStack = stack.copy();
                saplingStack.setCount(1);
                chest.removeStack(i, 1);
                villager.getInventory().addStack(saplingStack);
                return;
            }
        }
    }
}
