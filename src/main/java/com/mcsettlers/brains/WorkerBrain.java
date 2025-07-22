package com.mcsettlers.brains;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableSet;

import com.mcsettlers.MCSettlers;
import com.mcsettlers.ModMemoryModules;
import com.mcsettlers.utils.ChestAnimationHelper;
import com.mcsettlers.utils.RadiusGenerator;
import com.mcsettlers.utils.SharedMemories;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ai.brain.Brain;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

public class WorkerBrain {
    // Optional block state for the target block. This is used to select the best
    // tool from the chest.
    protected Optional<BlockState> TARGET_BLOCK_STATE = Optional.empty();
    protected Set<String> NON_AI_JOBS = ImmutableSet.of(
            "breaking");

    protected Set<TagKey<Item>> WANTED_ITEM_TAGS = ImmutableSet.of();

    public Set<Item> getWantedItems(VillagerEntity villager) {
        // Check memory for wanted items override
        Optional<Set<RegistryEntry<Item>>> optionalWantedItems = villager.getBrain().getOptionalMemory(ModMemoryModules.WANTED_ITEMS);
        if (optionalWantedItems.isPresent()) {
            return optionalWantedItems.get().stream().map(RegistryEntry::value).collect(Collectors.toSet());
        }
        // If no override, return the default wanted items
        return WANTED_ITEM_TAGS.stream()
                .flatMap(tag -> tagKeyToItems(tag).stream())
                .collect(Collectors.toSet());
    }

    protected Set<Item> tagKeyToItems(TagKey<Item> tag) {
        Set<Item> items = new HashSet<>();

        for (RegistryEntry<Item> entry : Registries.ITEM.iterateEntries(tag)) {
            items.add(entry.value());
        }
        return items;
    }

    public void initCustomBrain(VillagerEntity villager, ServerWorld world, SharedMemories sharedMemories) {
        // Initialize the custom brain for the villager
        // Read memory values
        Brain<?> brain = villager.getBrain();
        BlockPos workstation = brain.getOptionalMemory(MemoryModuleType.JOB_SITE)
                .map(GlobalPos::pos)
                .orElse(null);
        findDepositChest(world, workstation, sharedMemories);
        Optional<BlockPos> optionalTarget = brain.getOptionalMemory(ModMemoryModules.TARGET_BREAK_BLOCK);
        BlockPos targetBlock = optionalTarget != null ? optionalTarget.orElse(null) : null;
        if (!sharedMemories.reserveTargetBlock(world, villager, targetBlock)) {
            MCSettlers.LOGGER.warn("[WorkerBrain] Villager {} could not reserve target block {} for breaking.",
                    MCSettlers.workerToString(villager), targetBlock);
            brain.forget(ModMemoryModules.TARGET_BREAK_BLOCK);
        }
    }

    public void tick(VillagerEntity villager, ServerWorld world, SharedMemories sharedMemories) {
        // Skip every other tick to reduce load
        if (world.getTime() % 2 != 0) {
            return;
        }

        long tickStart = System.nanoTime();
        Brain<?> brain = villager.getBrain();

        Optional<String> optionalJobStatus = brain.getOptionalMemory(ModMemoryModules.JOB_STATUS);
        if (optionalJobStatus == null) {
            MCSettlers.LOGGER.warn("[WorkerBrain] Villager " + MCSettlers.workerToString(villager)
                    + " has no job status memory, setting to idle.");
            setJobStatus(villager, "idle");
            return;
        }

        String jobStatus = optionalJobStatus.orElse("unknown");

        if (jobStatus == null || jobStatus.isEmpty() || jobStatus.equals("unknown")) {
            // If job status is unknown, set it to idle
            MCSettlers.LOGGER.warn("[WorkerBrain] Villager " + MCSettlers.workerToString(villager)
                    + " has unknown job status: " + jobStatus + ", setting to idle.");
            setJobStatus(villager, "idle");
            jobStatus = "idle"; // Update local variable to avoid repeated lookups
        }

        Optional<GlobalPos> potentialJobSite = brain.getOptionalMemory(MemoryModuleType.POTENTIAL_JOB_SITE);

        if (potentialJobSite.isPresent() && (jobStatus.startsWith("no_work") || jobStatus.equals("idle"))) {
            // Worker is about to take a break, so forget the current jobs
            villager.setAiDisabled(false);
            setJobStatus(villager, "no_work_new_job");
            return;
        }

        Optional<Long> pauseUntil = brain.getOptionalMemory(ModMemoryModules.PAUSE_EVERYTHING_UNTIL);
        if (pauseUntil != null && pauseUntil.isPresent()) {
            long now = world.getTime();
            if (now >= pauseUntil.get()) {
                brain.forget(ModMemoryModules.PAUSE_EVERYTHING_UNTIL);
            } else {
                // If paused, skip the tick. Make sure AI is disabled to keep the villager from
                // moving
                villager.setAiDisabled(true);
                return;
            }
        }

        // When breaking or pillaring, disable AI to prevent movement
        // Otherwise enable AI
        if (NON_AI_JOBS.contains(jobStatus)) {
            villager.setAiDisabled(true);
        } else {
            villager.setAiDisabled(false);
        }

        // Read memory values
        BlockPos workstation = brain.getOptionalMemory(MemoryModuleType.JOB_SITE)
                .map(GlobalPos::pos)
                .orElse(null);

        BlockPos targetBlock = sharedMemories.getTargetBlock(world, villager);
        if (targetBlock == null) {
            // Also check memory for target block
            Optional<BlockPos> optionalTarget = brain.getOptionalMemory(ModMemoryModules.TARGET_BREAK_BLOCK);
            if (optionalTarget.isPresent()) {
                targetBlock = optionalTarget.get();
            }
        }

        BlockPos depositChest = sharedMemories.getDepositChest(world, villager, workstation);
        if (!(this instanceof CarrierBrain) && depositChest == null) {
            // Also check memory for deposit chest
            Optional<BlockPos> optionalDepositChest = brain.getOptionalMemory(ModMemoryModules.DEPOSIT_CHEST);
            if (optionalDepositChest.isPresent()) {
                if (!sharedMemories.reserveDepositChest(world, villager, optionalDepositChest.get())) {
                    MCSettlers.LOGGER.warn("[WorkerBrain] Villager " + MCSettlers.workerToString(villager)
                            + " could not reserve deposit chest: " + optionalDepositChest.get());
                    brain.forget(ModMemoryModules.DEPOSIT_CHEST);
                }
            }
        }

        if (workstation == null) {
            if (!(this instanceof CarrierBrain)) {
                return;
            }
        }

        keepHoldingItemInHand(villager);

        // if (dance(villager, world, workstation)) {
        // return;
        // }

        handleJob(villager, world, jobStatus, workstation, targetBlock, sharedMemories);

        long tickEnd = System.nanoTime();
        if (tickEnd - tickStart > 500_000) { // Only log if tick is slow (>0.5ms)
            MCSettlers.LOGGER
                    .info("[WorkerBrain] " + jobStatus + " tick took " + ((tickEnd - tickStart) / 1000) + "us");
        }
    }

    protected void startHoldingItem(
            VillagerEntity villager, ItemStack itemStack) {
        villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, itemStack);
        if (itemStack.isEmpty() || itemStack.getItem() == Items.AIR) {
            // Forget the item in hand memory if the item is empty or air
            villager.getBrain().forget(ModMemoryModules.ITEM_IN_HAND);
        } else {
            villager.getBrain().remember(ModMemoryModules.ITEM_IN_HAND, Registries.ITEM.getEntry(itemStack.getItem()));
        }
    }

    protected void startHoldingItem(
            VillagerEntity villager, Item item) {
        if (item == null) {
            // If item is null, choose item air
            item = Items.AIR; // Default to air if no item is provided
        }
        ItemStack itemStack = new ItemStack(item);
        startHoldingItem(villager, itemStack);
    }

    protected boolean keepHoldingItemInHand(
            VillagerEntity villager) {
        // Check memory for item in hand
        Optional<RegistryEntry<Item>> optionalItemInHand = villager.getBrain().getOptionalMemory(ModMemoryModules.ITEM_IN_HAND);
        if (optionalItemInHand.isPresent()) {
            Item itemInHand = optionalItemInHand.get().value();
            // Check if the villager is already holding the item
            ItemStack prevItemStack = villager.getStackInHand(net.minecraft.util.Hand.MAIN_HAND);
            if (prevItemStack.isOf(itemInHand)) {
                return true; // Already holding the item, nothing to do
            }
            // If not holding the item, set it in the main hand
            // Find item in inventory to get the correct stack
            for (int i = 0; i < villager.getInventory().size(); i++) {
                ItemStack stack = villager.getInventory().getStack(i);
                if (stack.isOf(itemInHand)) {
                    villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, stack);
                    // MCSettlers.LOGGER.info("[WorkerBrain] Villager {} is now holding item: {}, prev item: {}", MCSettlers.workerToString(villager),
                    //         stack, prevItemStack);
                    return true;
                }
            }
            // If item not found in inventory, clear the hand
            villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
            MCSettlers.LOGGER.info("[WorkerBrain] Villager {} could not find item {} in inventory, clearing hand.",
                    MCSettlers.workerToString(villager), itemInHand);
            villager.getBrain().forget(ModMemoryModules.ITEM_IN_HAND); // Forget the item in hand memory
        }
        // No item in hand memory, make sure the villager is not holding anything
        // If they are
        if (!villager.getStackInHand(net.minecraft.util.Hand.MAIN_HAND).isEmpty()) {
            MCSettlers.LOGGER.info("[WorkerBrain] Villager {} is clearing their hand.", MCSettlers.workerToString(villager));
            // Clear the hand
            villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, ItemStack.EMPTY);
        }
        return false;
    }

	protected boolean isAtValidPosition(VillagerEntity villager) {
		return villager.isOnGround() || villager.isInFluid() || villager.hasVehicle();
	}

    protected boolean reallyReachedTarget(
            ServerWorld world,
            VillagerEntity villager) {
        int squaredDistanceLimit = 3 * 3; // 3 blocks squared distance
        Brain<?> brain = villager.getBrain();
        Optional<WalkTarget> optionalWalkTarget = brain.getOptionalMemory(MemoryModuleType.WALK_TARGET);
        BlockPos walkTarget = null;
        if (optionalWalkTarget.isPresent()) {
            WalkTarget wt = optionalWalkTarget.get();
            if (wt != null && wt.getLookTarget() != null) {
                walkTarget = wt.getLookTarget().getBlockPos();
            }
        }
        if (walkTarget != null) {
            return false;
        }
        // Call this after worker stopped walking. It will start another job and return
        // false, if not reached.
        Optional<BlockPos> jobWalkTarget = brain.getOptionalMemory(ModMemoryModules.JOB_WALK_TARGET);
        if (jobWalkTarget.isEmpty()) {
            setJobStatus(villager, "no_work_taking_a_break");
            return false;
        }
        BlockPos target = jobWalkTarget.get();
        if (villager.squaredDistanceTo(Vec3d.ofCenter(target)) < squaredDistanceLimit) {
            // Reached the target
            // Forget the job walk target
            brain.forget(ModMemoryModules.JOB_WALK_TARGET);
            return true;
        }
        // Not reached yet. Let's check if we have failed too many times
        Optional<Integer> optionalFailureCount = brain.getOptionalMemory(ModMemoryModules.JOB_WALK_FAILURE_COUNT);
        if (optionalFailureCount.isPresent()) {
            int failureCount = optionalFailureCount.get();
            if (failureCount >= 3) {
                // Too many failures, give up
                brain.forget(ModMemoryModules.JOB_WALK_TARGET);
                // Set job status to no_work_path
                setJobStatus(villager, "no_work_path");
                MCSettlers.LOGGER.warn("[WorkerBrain] Villager {} failed to reach target {}: {} ({} times), giving up.",
                        MCSettlers.workerToString(villager), target.toShortString(), world.getBlockState(target), failureCount);
                return false;
            }
        }
        if (!isAtValidPosition(villager)) {
            MCSettlers.LOGGER.warn("[WorkerBrain] Villager {} is not at a valid position, cannot continue walking to target {}.",
                    MCSettlers.workerToString(villager), target.toShortString());
            return false; // Cannot continue walking to target
        }

        // Add or increment the failure count
        int newFailureCount = optionalFailureCount.orElse(0) + 1;
        brain.remember(ModMemoryModules.JOB_WALK_FAILURE_COUNT, newFailureCount);
        MCSettlers.LOGGER.info("[WorkerBrain] Villager {} failed to reach target {} ({} times)",
                MCSettlers.workerToString(villager), target.toShortString(), newFailureCount);
        // Try to find a new position to walk to
        BlockPos goodPos = findWalkablePosition(villager, world, target, 0.6F, java.util.Collections.singletonList(walkTarget));
        brain.remember(MemoryModuleType.WALK_TARGET,
                new net.minecraft.entity.ai.brain.WalkTarget(
                        new net.minecraft.entity.ai.brain.BlockPosLookTarget(goodPos),
                        0.6F,
                        1));
        return false;
    }

    protected boolean checkPositionIsWalkable(VillagerEntity villager, ServerWorld world, BlockPos pos) {
        // Check if a villager would fit in this position
        if (!world.getBlockState(pos.down()).isSolidBlock(world, pos.down())) {
            return false; // Position below must be solid
        }
        if (world.getBlockState(pos).isSolidBlock(world, pos) || !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) {
            MCSettlers.LOGGER.warn("{} is solid", world.getBlockState(pos).toString());
            return false;
        }
        MCSettlers.LOGGER.info("Not solid: {}", world.getBlockState(pos).toString());
        if (world.getBlockState(pos.up()).isSolidBlock(world, pos.up()) || !world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()) {
            MCSettlers.LOGGER.warn("{} is solid above", world.getBlockState(pos.up()).toString());
            return false;
        }
        MCSettlers.LOGGER.info("Found good position: {}: {}", pos.toShortString(), world.getBlockState(pos).toString());
        return true;
    }

    protected BlockPos findWalkablePosition(VillagerEntity villager, ServerWorld world, BlockPos pos, float speed) {
        return findWalkablePosition(villager, world, pos, speed, new ArrayList<BlockPos>());
    }

    protected BlockPos findWalkablePosition(VillagerEntity villager, ServerWorld world, BlockPos pos, float speed, List<BlockPos> notAllowedPositions) {
        // Make sure pos is not replaceable and has two replaceable blocks above it
        if (!checkPositionIsWalkable(villager, world, pos)) {
            // Check blocks around the position, and close to villager
            for (BlockPos newPos : RadiusGenerator.radiusCoordinates(pos, villager.getBlockPos(), 3)) {
                if (checkPositionIsWalkable(villager, world, newPos) && !notAllowedPositions.contains(newPos)) {
                    MCSettlers.LOGGER.info("[WorkerBrain] Found walkable position: {}",
                            newPos.toShortString());
                    return newPos;
                }
            }
        } else {
            return pos;
        }
        MCSettlers.LOGGER.warn("[WorkerBrain] No valid position found to walk to, giving up.");
        setJobStatus(villager, "no_work_no_walkable_position");
        return null; // No valid position found, give up
    }

    protected void walkToPosition(VillagerEntity villager, ServerWorld world, BlockPos pos, float speed) {
        Brain<?> brain = villager.getBrain();
        BlockPos goodPos = findWalkablePosition(villager, world, pos, speed);
        if (goodPos == null) {
            // No valid position found, give up
            setJobStatus(villager, "no_work_no_walkable_position");
            return;
        }

        // Try to walk to the target
        brain.remember(MemoryModuleType.WALK_TARGET,
                new net.minecraft.entity.ai.brain.WalkTarget(
                        new net.minecraft.entity.ai.brain.BlockPosLookTarget(goodPos),
                        0.6F,
                        1));
        brain.remember(MemoryModuleType.LOOK_TARGET,
                new net.minecraft.entity.ai.brain.BlockPosLookTarget(goodPos));
        brain.remember(ModMemoryModules.JOB_WALK_TARGET, goodPos);
        brain.forget(ModMemoryModules.JOB_WALK_FAILURE_COUNT); // Reset failure count
    }

    protected void handleJob(
            VillagerEntity villager, ServerWorld world,
            String jobStatus, BlockPos workstation, BlockPos targetLog, SharedMemories sharedMemories) {

        if (jobStatus.equals("walking")) {
            return;
        } else if (jobStatus.equals("picking_up_blocks")) {
            keepPickingUpBlocks(villager, world, workstation);
        } else if (jobStatus.equals("deposit_items")) {
            keepDepositingItems(villager, world, workstation, sharedMemories);
        } else if (jobStatus.equals("stop_deposit_items")) {
            stopDepositingItems(villager, world, workstation);
        }
    }

    protected void pauseForMS(VillagerEntity villager, ServerWorld world, long duration) {
        int pauseDuration = (int) (duration / 50); // Convert ms to ticks (20 ticks = 1 second)
        long pauseUntil = world.getTime() + pauseDuration;
        villager.getBrain().remember(ModMemoryModules.PAUSE_EVERYTHING_UNTIL, pauseUntil);
    }

    protected void setJobStatus(VillagerEntity villager, String status) {
        villager.getBrain().remember(ModMemoryModules.JOB_STATUS, status);
        Text customName = Text.of(status);
        // Add villager's workstation position to the custom name
        Optional<GlobalPos> optionalWorkstation = villager.getBrain().getOptionalMemory(MemoryModuleType.JOB_SITE);
        if (optionalWorkstation.isPresent()) {
            GlobalPos workstation = optionalWorkstation.get();
            customName = Text.of(status + " at " + workstation.pos().toShortString());
            // Add villager's deposit chest position to the custom name
            Optional<BlockPos> optionalDepositChest = villager.getBrain().getOptionalMemory(ModMemoryModules.DEPOSIT_CHEST);
            if (optionalDepositChest.isPresent()) {
                BlockPos depositChest = optionalDepositChest.get();
                customName = Text.of(customName.getString() + " (deposit at " + depositChest.toShortString() + ")");
            }
        }
        // Prepend with a part of uuid
        String uuidPart = villager.getUuid().toString().substring(0, 8);
        customName = Text.of(uuidPart + " " + customName.getString());
        villager.setCustomName(customName);
        villager.setCustomNameVisible(true);
        MCSettlers.LOGGER.info("[WorkerBrain] Set job status to " + status + " for villager "
                + MCSettlers.workerToString(villager));

    }

    protected Optional<BlockPos> findDepositChest(ServerWorld world, BlockPos workstation, SharedMemories sharedMemories) {
        int searchRadius = 10;
        for (BlockPos pos : RadiusGenerator.radiusCoordinates(workstation, searchRadius)) {
            BlockState state = world.getBlockState(pos);
            if (state.isOf(Blocks.CHEST)) {
                // Skip if this position is already used by another villager
                if (!sharedMemories.depositChestIsAvailable(world, pos)) {
                    continue;
                }
                MCSettlers.LOGGER.info("[WorkerBrain] Found deposit chest at {}", pos.toShortString());
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    protected ItemStack takeItemFromChest(
            ChestBlockEntity chest, VillagerEntity villager, Item item) {
        // Find the item in the chest and remove it
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (stack.getItem() == item) {
                // Remove the item from the chest
                int count = stack.getCount();
                if (count > 0) {
                    stack.decrement(1); // Decrement by 1
                    MCSettlers.LOGGER.info("CrafterBrain: Removed item {} from chest for crafting", item);
                    ItemStack itemStack = new ItemStack(item, 1);
                    villager.getInventory().addStack(itemStack);
                    return itemStack; // Return the item stack
                }
            }
        }
        MCSettlers.LOGGER.warn("Item {} not found in chest", item);
        return null;
    }

    protected void getBestToolFromChest(
        ServerWorld world,
            ChestBlockEntity chest, VillagerEntity villager, BlockPos workstation) {
        // Find the best axe in the chest
        net.minecraft.item.ItemStack bestAxe = net.minecraft.item.ItemStack.EMPTY;
        int bestToolIndex = -1;
        if (TARGET_BLOCK_STATE.isEmpty()) {
            return;
        }
        BlockState targetBlockState = TARGET_BLOCK_STATE.get();

        for (int i = 0; i < chest.size(); i++) {
            net.minecraft.item.ItemStack stack = chest.getStack(i);
            float miningSpeed = stack.getMiningSpeedMultiplier(targetBlockState);
            if (miningSpeed <= 1.0f)
                continue; // Not an axe or not effective
            if (bestAxe.isEmpty() || miningSpeed > bestAxe.getMiningSpeedMultiplier(targetBlockState)) {
                bestAxe = stack;
                bestToolIndex = i;
            }
        }
        if (!bestAxe.isEmpty()) {
            // Set the best axe in the villager's hand
            MCSettlers.LOGGER.info("[WorkerBrain] Villager {} took best axe from chest: {}", MCSettlers.workerToString(villager),
                    bestAxe);
            // Remove the axe from the chest
            chest.setStack(bestToolIndex, net.minecraft.item.ItemStack.EMPTY);
            villager.getInventory().addStack(bestAxe);
            startHoldingItem(villager, bestAxe);
        }
    }

    protected boolean addStackToChest(
            ChestBlockEntity chest, ItemStack stack) {
        int emptySlot = -1;
        for (int i = 0; i < chest.size(); i++) {
            ItemStack chestStack = chest.getStack(i);
            if (chestStack.isEmpty() && emptySlot == -1) {
                emptySlot = i;
                continue;
            }
            // Check if the item can be added to this slot
            if (chestStack.isOf(stack.getItem())) {
                int countToAdd = Math.min(stack.getCount(), chestStack.getMaxCount() - chestStack.getCount());
                if (countToAdd > 0) {
                    chestStack.increment(countToAdd);
                    stack.decrement(countToAdd);
                }
            }
            // If stack is empty, we can stop
            if (stack.isEmpty()) {
                return true;
            }
        }
        // Add to any empty slot in the chest
        if (emptySlot != -1) {
            chest.setStack(emptySlot, stack);
            return true;
        }
        // No empty slot found
        return false;
    }

    protected void stopDepositingItems(
            VillagerEntity villager, ServerWorld world, BlockPos workstation) {

        // Close the chest
        Optional<BlockPos> chestPos = villager.getBrain().getOptionalMemory(ModMemoryModules.DEPOSIT_CHEST);
        if (chestPos.isPresent()) {
            BlockPos pos = chestPos.get();
            ChestAnimationHelper.animateChest(world, pos, false);
        }

        // Set job status to idle
        setJobStatus(villager, "picking_up_blocks");
    }

    protected Optional<BlockPos> getDepositChest(VillagerEntity villager, ServerWorld world, BlockPos workstation, SharedMemories sharedMemories) {
        Brain<?> brain = villager.getBrain();
        // Find a chest to deposit items
        Optional<BlockPos> chestPos = brain.getOptionalMemory(ModMemoryModules.DEPOSIT_CHEST);
        if (chestPos.isEmpty() || !world.getBlockState(chestPos.get()).isOf(Blocks.CHEST)) {
            chestPos = findDepositChest(world, workstation, sharedMemories);
            if (chestPos.isPresent()) {
                if (!sharedMemories.reserveDepositChest(world, villager, chestPos.get())) {
                    setJobStatus(villager, "no_work_chest_reserved");
                    return Optional.empty(); // Chest is already reserved by another villager
                }
                brain.remember(ModMemoryModules.DEPOSIT_CHEST, chestPos.get());
            } else {
                MCSettlers.LOGGER
                        .info("[WorkerBrain] No nearby chest found for villager " + MCSettlers.workerToString(villager));
                setJobStatus(villager, "no_work_no_chest");
                return Optional.empty();
            }
        }

        return chestPos;
    }

    protected void startDepositingItems(
            VillagerEntity villager, ServerWorld world, BlockPos workstation, SharedMemories sharedMemories) {

        Optional<BlockPos> optionalChestPos = getDepositChest(villager, world, workstation, sharedMemories);
        if (optionalChestPos.isEmpty()) {
            // No chest found, set job status to no_work
            setJobStatus(villager, "no_work_no_chest");
            return;
        }

        BlockPos pos = optionalChestPos.get();
        walkToPosition(villager, world, pos, 0.6F);

        setJobStatus(villager, "deposit_items");
        MCSettlers.LOGGER.info("[WorkerBrain] Villager {} walking to deposit items at {}", MCSettlers.workerToString(villager),
                pos);
    }

    protected void keepDepositingItems(
            VillagerEntity villager, ServerWorld world, BlockPos workstation, SharedMemories sharedMemories) {

        if (!reallyReachedTarget(world, villager)) {
            return;
        }

        Optional<BlockPos> optionalChestPos = getDepositChest(villager, world, workstation, sharedMemories);
        if (optionalChestPos.isEmpty()) {
            // No chest found, set job status to no_work
            setJobStatus(villager, "no_work_no_chest");
            return;
        }

        // If close to chest, deposit items
        BlockPos pos = optionalChestPos.get();
        BlockEntity chest = world.getBlockEntity(pos);
        if (!(chest instanceof net.minecraft.block.entity.ChestBlockEntity chestEntity)) {
            MCSettlers.LOGGER.warn("[WorkerBrain] Villager {} found a non-chest block at {}, cannot deposit items.",
                    MCSettlers.workerToString(villager), pos);
            setJobStatus(villager, "no_work_no_chest");
            return;
        }
        // To open the chest
        ChestAnimationHelper.animateChest(world, pos, true);

        for (int i = 0; i < villager.getInventory().size(); i++) {
            net.minecraft.item.ItemStack stack = villager.getInventory().getStack(i);
            if (stack.isEmpty())
                continue;

            // Try to add the stack to the chest
            if (addStackToChest(chestEntity, stack)) {
                // Successfully added the stack
                villager.getInventory().removeStack(i);
                continue;
            }
        }
        // Then, select the best axe from the chest
        getBestToolFromChest(world, chestEntity, villager, workstation);

        setJobStatus(villager, "stop_deposit_items");

        // Turn head towards the chest
        lookAtBlock(villager, pos);

        pauseForMS(villager, world, 1000); // Pause for 1 second after depositing
    }

    protected void lookAtBlock(VillagerEntity villager, BlockPos target) {
        if (target == null) {
            return; // No target to look at
        }
        Vec3d eyePos = villager.getPos().add(0, villager.getStandingEyeHeight(), 0);
        Vec3d targetCenter = Vec3d.ofCenter(target);
        Vec3d dir = targetCenter.subtract(eyePos);

        double dx = dir.x;
        double dy = dir.y;
        double dz = dir.z;

        double distanceXZ = Math.sqrt(dx * dx + dz * dz);

        // Yaw: rotation around Y axis (horizontal)
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        // Pitch: rotation around X axis (vertical)
        float pitch = (float) (Math.toDegrees(-Math.atan2(dy, distanceXZ)));

        villager.setYaw(yaw);
        villager.setHeadYaw(yaw);
        villager.setPitch(pitch);
    }

    protected void keepPickingUpBlocks(
            VillagerEntity villager, ServerWorld world, BlockPos workstation) {

        // If currently walking, return;
        if (!reallyReachedTarget(world, villager)) {
            return;
        }

        int searchRadius = 20;
        BlockPos villagerPos = villager.getBlockPos();
        Set<Item> gatherableItems = villager.getVillagerData().profession().value().gatherableItems();
        // Search for first gatherable item in range

        for (BlockPos pos : RadiusGenerator.radiusCoordinates(workstation, villagerPos, searchRadius)) {
            // Skip if too far away in Y axis
            if (Math.abs(pos.getY() - villagerPos.getY()) > 2) {
                continue;
            }
            if (workstation != null && workstation.getSquaredDistance(pos) > searchRadius * searchRadius)
                continue;
            // Check for item entity at this position
            List<net.minecraft.entity.ItemEntity> items = world.getEntitiesByClass(
                    net.minecraft.entity.ItemEntity.class,
                    new net.minecraft.util.math.Box(pos),
                    itemEntity -> gatherableItems.contains(itemEntity.getStack().getItem()));
            if (!items.isEmpty()) {
                net.minecraft.entity.ItemEntity targetItem = items.get(0);
                BlockPos itemPos = targetItem.getBlockPos();
                walkToPosition(villager, world, itemPos, 0.6F);
                MCSettlers.LOGGER.info("[WorkerBrain] Villager {} walking to gatherable item {} at {}",
                        MCSettlers.workerToString(villager), targetItem.getStack().getItem(), itemPos);
                return;
            }
        }
        MCSettlers.LOGGER.info("[WorkerBrain] No gatherable items found in radius " + searchRadius + " around "
                + villagerPos.toShortString() + " or workstation " + workstation.toShortString());
        // If no item found, set job status to idle
        setJobStatus(villager, "idle");
    }

    protected int blockBreakTime(BlockPos targetLog, VillagerEntity villager) {
        if (targetLog == null) {
            return Integer.MAX_VALUE; // No target log to check
        }
        ItemStack heldItem = villager.getStackInHand(net.minecraft.util.Hand.MAIN_HAND);
        BlockState state = villager.getWorld().getBlockState(targetLog);

        // Get block hardness
        float hardness = state.getHardness(villager.getWorld(), targetLog);
        if (hardness < 0)
            return Integer.MAX_VALUE; // Unbreakable

        // Get tool effectiveness
        float toolSpeed = heldItem.getMiningSpeedMultiplier(state);

        // If the tool is not effective, use a default speed
        if (toolSpeed <= 1.0f)
            toolSpeed = 1.0f;

        // Calculate break time (formula: hardness * 30 / toolSpeed)
        int ticks = Math.round(hardness * 30.0f / toolSpeed);

        // Minimum 1 tick
        return Math.max(ticks, 1);
    }

}
