package com.mcsettlers.brains;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.mcsettlers.MCSettlers;
import com.mcsettlers.ModMemoryModules;
import com.mcsettlers.utils.ChestAnimationHelper;

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
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

public class WorkerBrain {
    // Optional block state for the target block. This is used to select the best
    // tool from the chest.
    protected Optional<BlockState> TARGET_BLOCK_STATE = Optional.empty();
    protected Set<String> NON_AI_JOBS = ImmutableSet.of(
            "breaking");
    // This worker wants these items in their chest
    protected Set<TagKey<Item>> WANTED_ITEMS = ImmutableSet.of();

    public void tick(VillagerEntity villager, ServerWorld world) {
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
            setJobStatus(villager, "idle");
            jobStatus = "idle"; // Update local variable to avoid repeated lookups
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
        Optional<BlockPos> optionalTarget = brain.getOptionalMemory(ModMemoryModules.TARGET_BREAK_BLOCK);
        BlockPos targetBlock = optionalTarget != null ? optionalTarget.orElse(null) : null;

        if (workstation == null) {
            if (!(this instanceof CarrierBrain)) {
                return;
            }
        }

        keepHoldingItemInHand(villager);

        // if (dance(villager, world, workstation)) {
        // return;
        // }

        handleJob(villager, world, jobStatus, workstation, targetBlock);

        long tickEnd = System.nanoTime();
        if (tickEnd - tickStart > 500_000) { // Only log if tick is slow (>0.5ms)
            MCSettlers.LOGGER
                    .info("[WorkerBrain] " + jobStatus + " tick took " + ((tickEnd - tickStart) / 1000) + "us");
        }
    }

    protected void startHoldingItem(
            VillagerEntity villager, ItemStack itemStack) {
        villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, itemStack);
        villager.getBrain().remember(ModMemoryModules.ITEM_IN_HAND, itemStack.getItem());
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
        Optional<Item> optionalItemInHand = villager.getBrain().getOptionalMemory(ModMemoryModules.ITEM_IN_HAND);
        if (optionalItemInHand.isPresent()) {
            Item itemInHand = optionalItemInHand.get();
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

    protected boolean reallyReachedTarget(
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
            setJobStatus(villager, "no_work_no_walk_target");
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
                MCSettlers.LOGGER.warn("[WorkerBrain] Villager {} failed to reach target {} ({} times), giving up.",
                        MCSettlers.workerToString(villager), target.toShortString(), failureCount);
                return false;
            }
        }
        // Add or increment the failure count
        int newFailureCount = optionalFailureCount.orElse(0) + 1;
        brain.remember(ModMemoryModules.JOB_WALK_FAILURE_COUNT, newFailureCount);
        MCSettlers.LOGGER.info("[WorkerBrain] Villager {} failed to reach target {} ({} times)",
                MCSettlers.workerToString(villager), target.toShortString(), newFailureCount);
        // Try to walk to the target
        brain.remember(MemoryModuleType.WALK_TARGET,
                new net.minecraft.entity.ai.brain.WalkTarget(
                        new net.minecraft.entity.ai.brain.BlockPosLookTarget(target),
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
        if (world.getBlockState(pos.up()).isSolidBlock(world, pos.up()) || !world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()) {
            MCSettlers.LOGGER.warn("{} is solid above", world.getBlockState(pos.up()).toString());
            return false;
        }
        return true;
    }

    protected void walkToPosition(VillagerEntity villager, ServerWorld world, BlockPos pos, float speed) {
        Brain<?> brain = villager.getBrain();
        // Make sure pos is not replaceable and has two replaceable blocks above it
        if (!checkPositionIsWalkable(villager, world, pos)) {
            // Check blocks around the position
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos newPos = pos.add(dx, dy, dz);
                        if (checkPositionIsWalkable(villager, world, newPos)) {
                            // Found a valid position to walk to
                            MCSettlers.LOGGER.info("[WorkerBrain] Found walkable position: {}",
                                    newPos.toShortString());
                            // Try to walk to the target
                            brain.remember(MemoryModuleType.WALK_TARGET,
                                    new net.minecraft.entity.ai.brain.WalkTarget(
                                            new net.minecraft.entity.ai.brain.BlockPosLookTarget(newPos),
                                            0.6F,
                                            1));
                            brain.remember(MemoryModuleType.LOOK_TARGET,
                                    new net.minecraft.entity.ai.brain.BlockPosLookTarget(newPos));
                            brain.remember(ModMemoryModules.JOB_WALK_TARGET, newPos);
                            brain.forget(ModMemoryModules.JOB_WALK_FAILURE_COUNT); // Reset failure count
                            return;
                        }
                    }
                }
            }

            MCSettlers.LOGGER.warn("[WorkerBrain] No valid position found to walk to, giving up.");
            setJobStatus(villager, "no_work_no_walkable_position");
            return; // No valid position found, give up
        }

        brain.remember(ModMemoryModules.JOB_WALK_TARGET, pos);
        brain.forget(ModMemoryModules.JOB_WALK_FAILURE_COUNT); // Reset failure count
    }

    protected void handleJob(
            VillagerEntity villager, ServerWorld world,
            String jobStatus, BlockPos workstation, BlockPos targetLog) {

        if (jobStatus == "walking") {
            return;
        } else if (jobStatus == "picking_up_blocks") {
            keepPickingUpBlocks(villager, world, workstation);
        } else if (jobStatus == "deposit_items") {
            keepDepositingItems(villager, world, workstation);
        } else if (jobStatus == "stop_deposit_items") {
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
        villager.setCustomName(net.minecraft.text.Text.of(status));
        villager.setCustomNameVisible(true);
        MCSettlers.LOGGER.info("[WorkerBrain] Set job status to " + status + " for villager "
                + MCSettlers.workerToString(villager));

    }

    protected Optional<BlockPos> findDepositChest(ServerWorld world, BlockPos workstation) {
        // Read all other villagers' deposit chests
        Set<BlockPos> otherPositions = new HashSet<>();
        for (VillagerEntity otherVillager : world.getEntitiesByType(net.minecraft.entity.EntityType.VILLAGER,
                v -> true)) {
            Brain<?> otherBrain = otherVillager.getBrain();
            Optional<BlockPos> otherChestPos = otherBrain.getOptionalMemory(ModMemoryModules.DEPOSIT_CHEST);
            if (otherChestPos.isPresent()) {
                BlockPos pos = otherChestPos.get();
                otherPositions.add(pos);
            }
        }

        int searchRadius = 10;
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    BlockPos pos = workstation.add(dx, dy, dz);
                    // Skip if this position is already used by another villager
                    if (otherPositions.contains(pos)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(pos);
                    if (state.isOf(Blocks.CHEST)) {
                        return Optional.of(pos);
                    }
                }
            }
        }
        return Optional.empty();
    }

    protected void getBestToolFromChest(
            ChestBlockEntity chest, VillagerEntity villager) {
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

    protected Optional<BlockPos> getDepositChest(VillagerEntity villager, ServerWorld world, BlockPos workstation) {
        Brain<?> brain = villager.getBrain();
        // Find a chest to deposit items
        Optional<BlockPos> chestPos = brain.getOptionalMemory(ModMemoryModules.DEPOSIT_CHEST);
        if (chestPos.isEmpty() || !world.getBlockState(chestPos.get()).isOf(Blocks.CHEST)) {
            chestPos = findDepositChest(world, workstation);
            if (chestPos.isPresent()) {
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
            VillagerEntity villager, ServerWorld world, BlockPos workstation) {

        Optional<BlockPos> optionalChestPos = getDepositChest(villager, world, workstation);
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
            VillagerEntity villager, ServerWorld world, BlockPos workstation) {

        if (!reallyReachedTarget(villager)) {
            return;
        }

        Optional<BlockPos> optionalChestPos = getDepositChest(villager, world, workstation);
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
        getBestToolFromChest(chestEntity, villager);

        setJobStatus(villager, "stop_deposit_items");

        // Turn head towards the chest
        lookAtBlock(villager, pos);

        pauseForMS(villager, world, 1000); // Pause for 1 second after depositing
    }

    protected void lookAtBlock(VillagerEntity villager, BlockPos target) {
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
        if (!reallyReachedTarget(villager)) {
            return;
        }

        int searchRadius = 20;
        BlockPos villagerPos = villager.getBlockPos();
        Set<Item> gatherableItems = villager.getVillagerData().profession().value().gatherableItems();
        // Search for first gatherable item in range
        for (int dx = -searchRadius; dx <= searchRadius; dx++) {
            for (int dy = -2; dy <= 1; dy++) {
                for (int dz = -searchRadius; dz <= searchRadius; dz++) {
                    BlockPos pos = villagerPos.add(dx, dy, dz);
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
            }
        }
        MCSettlers.LOGGER.info("[WorkerBrain] No gatherable items found in radius " + searchRadius + " around "
                + villagerPos.toShortString() + " or workstation " + workstation.toShortString());
        // If no item found, set job status to idle
        setJobStatus(villager, "idle");
    }

    protected int blockBreakTime(BlockPos targetLog, VillagerEntity villager) {
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
