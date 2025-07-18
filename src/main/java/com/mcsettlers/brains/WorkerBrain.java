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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;

public class WorkerBrain {
    // Optional block state for the target block. This is used to select the best tool from the chest.
    protected Optional<BlockState> TARGET_BLOCK_STATE = Optional.empty();
    protected final Set<String> NON_AI_JOBS = ImmutableSet.of(
        "breaking"
    );

    public void tick(VillagerEntity villager, ServerWorld world) {
        long tickStart = System.nanoTime();
        Brain<?> brain = villager.getBrain();

        Optional<String> optionalJobStatus = brain.getOptionalMemory(ModMemoryModules.JOB_STATUS);
        if (optionalJobStatus == null) {
            MCSettlers.LOGGER.warn("[WorkerBrain] Villager " + villager.getUuidAsString()
                    + " has no job status memory, setting to idle.");
            setJobStatus(brain, villager, "idle");
            return;
        }

        String jobStatus = optionalJobStatus.orElse("unknown");

        if (jobStatus == null || jobStatus.isEmpty() || jobStatus.equals("unknown")) {
            // If job status is unknown, set it to idle
            setJobStatus(brain, villager, "idle");
            jobStatus = "idle"; // Update local variable to avoid repeated lookups
        }

        Optional<Long> pauseUntil = brain.getOptionalMemory(ModMemoryModules.PAUSE_EVERYTHING_UNTIL);
        if (pauseUntil != null && pauseUntil.isPresent()) {
            long now = world.getTime();
            if (now >= pauseUntil.get()) {
                brain.forget(ModMemoryModules.PAUSE_EVERYTHING_UNTIL);
            } else {
                // If paused, skip the tick. Make sure AI is disabled to keep the villager from moving
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

        // if (dance(villager, world, brain, workstation)) {
        //     return;
        // }

        handleJob(villager, world, brain, jobStatus, workstation, targetLog, walkTarget);

        long tickEnd = System.nanoTime();
        if (tickEnd - tickStart > 500_000) { // Only log if tick is slow (>0.5ms)
            MCSettlers.LOGGER.info("[WorkerBrain] " + jobStatus + " tick took " + ((tickEnd - tickStart) / 1000) + "us");
        }
    }

    protected void handleJob(
            VillagerEntity villager, ServerWorld world, Brain<?> brain,
            String jobStatus, BlockPos workstation, BlockPos targetLog, BlockPos walkTarget) {

        if (jobStatus == "walking") {
            return;
        } else if (jobStatus == "picking_up_blocks") {
            keepPickingUpBlocks(villager, world, brain, workstation);
        } else if (jobStatus == "deposit_items") {
            keepDepositingItems(villager, world, brain, workstation);
        } else if (jobStatus == "stop_deposit_items") {
            stopDepositingItems(villager, world, brain, workstation);
        }
    }

    protected void pauseForMS(ServerWorld world, Brain<?> brain, long duration) {
        int pauseDuration = (int) (duration / 50); // Convert ms to ticks (20 ticks = 1 second)
        long pauseUntil = world.getTime() + pauseDuration;
        brain.remember(ModMemoryModules.PAUSE_EVERYTHING_UNTIL, pauseUntil);
    }

    protected void setJobStatus(Brain<?> brain, VillagerEntity villager, String status) {
        brain.remember(ModMemoryModules.JOB_STATUS, status);
        villager.setCustomName(net.minecraft.text.Text.of(status));
        villager.setCustomNameVisible(true);
        MCSettlers.LOGGER.info("[WorkerBrain] Set job status to " + status + " for villager "
                + villager.getUuidAsString());
        
    }

    protected Optional<BlockPos> findDepositChest(ServerWorld world, BlockPos workstation) {
        // Read all other villagers' deposit chests
        Set<BlockPos> otherPositions = new HashSet<>();
        for (VillagerEntity otherVillager : world.getEntitiesByType(net.minecraft.entity.EntityType.VILLAGER, v -> true)) {
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
            if (miningSpeed <= 1.0f) continue; // Not an axe or not effective
            if (bestAxe.isEmpty() || miningSpeed > bestAxe.getMiningSpeedMultiplier(targetBlockState)) {
                bestAxe = stack;
                bestToolIndex = i;
            }
        }
        if (!bestAxe.isEmpty()) {
            // Set the best axe in the villager's hand
            MCSettlers.LOGGER.info("[WorkerBrain] Villager {} took best axe from chest: {}", villager.getUuidAsString(), bestAxe);
            // Remove the axe from the chest
            chest.setStack(bestToolIndex, net.minecraft.item.ItemStack.EMPTY);
            villager.getInventory().addStack(bestAxe);
            villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, bestAxe);
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
            VillagerEntity villager, ServerWorld world, Brain<?> brain, BlockPos workstation) {

        // Close the chest
        Optional<BlockPos> chestPos = brain.getOptionalMemory(ModMemoryModules.DEPOSIT_CHEST);
        if (chestPos.isPresent()) {
            BlockPos pos = chestPos.get();
            ChestAnimationHelper.animateChest(world, pos, false);
        }

        // Set job status to idle
        setJobStatus(brain, villager, "picking_up_blocks");
    }

    protected void keepDepositingItems(
            VillagerEntity villager, ServerWorld world, Brain<?> brain, BlockPos workstation) {

        // If currently walking, return;
        Optional<WalkTarget> optionalWalkTarget = brain.getOptionalMemory(MemoryModuleType.WALK_TARGET);
        if (optionalWalkTarget.isPresent()) {
            return;
        }

        // Find a chest to deposit items
        Optional<BlockPos> chestPos = brain.getOptionalMemory(ModMemoryModules.DEPOSIT_CHEST);
        if (chestPos.isEmpty()) {
            chestPos = findDepositChest(world, workstation);
            if (chestPos.isPresent()) {
                brain.remember(ModMemoryModules.DEPOSIT_CHEST, chestPos.get());
            } else {
                MCSettlers.LOGGER.info("[WorkerBrain] No nearby chest found for villager " + villager.getUuidAsString());
                setJobStatus(brain, villager, "no_work");
                return;
            }
        }

        BlockPos pos = chestPos.get();

        // If close to chest, deposit items
        if (villager.squaredDistanceTo(Vec3d.ofCenter(pos)) < 2.0) {
            BlockEntity chest = world.getBlockEntity(pos);
            if (chest instanceof net.minecraft.block.entity.ChestBlockEntity chestEntity) {
                // To open the chest
                ChestAnimationHelper.animateChest(world, pos, true);

                for (int i = 0; i < villager.getInventory().size(); i++) {
                    net.minecraft.item.ItemStack stack = villager.getInventory().getStack(i);
                    if (stack.isEmpty()) continue;

                    // Try to add the stack to the chest
                    if (addStackToChest(chestEntity, stack)) {
                        // Successfully added the stack
                        villager.getInventory().removeStack(i);
                        continue;
                    }
                }
                // Then, select the best axe from the chest
                getBestToolFromChest(chestEntity, villager);
            }

            setJobStatus(brain, villager, "stop_deposit_items");

            // Turn head towards the chest
            lookAtBlock(villager, pos);

            pauseForMS(world, brain, 1000); // Pause for 1 second after depositing
            return;
        }

        brain.remember(MemoryModuleType.WALK_TARGET,
                new net.minecraft.entity.ai.brain.WalkTarget(
                        new net.minecraft.entity.ai.brain.BlockPosLookTarget(pos),
                        0.6F,
                        1
                ));
        MCSettlers.LOGGER.info("[WorkerBrain] Villager {} walking to deposit items at {}", villager.getUuidAsString(), pos);

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
        VillagerEntity villager, ServerWorld world, Brain<?> brain, BlockPos workstation) {

        // If currently walking, return;
        Optional<WalkTarget> optionalWalkTarget = brain.getOptionalMemory(MemoryModuleType.WALK_TARGET);
        if (optionalWalkTarget.isPresent()) {
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
                    if (workstation != null && workstation.getSquaredDistance(pos) > searchRadius * searchRadius) continue;
                    // Check for item entity at this position
                    List<net.minecraft.entity.ItemEntity> items = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                        new net.minecraft.util.math.Box(pos),
                        itemEntity -> gatherableItems.contains(itemEntity.getStack().getItem()));
                    if (!items.isEmpty()) {
                        net.minecraft.entity.ItemEntity targetItem = items.get(0);
                        BlockPos itemPos = targetItem.getBlockPos();
                        brain.remember(MemoryModuleType.WALK_TARGET,
                            new WalkTarget(
                                new net.minecraft.entity.ai.brain.BlockPosLookTarget(itemPos),
                                0.6F,
                                1
                            )
                        );
                        MCSettlers.LOGGER.info("[WorkerBrain] Villager {} walking to gatherable item {} at {}", villager.getUuidAsString(), targetItem.getStack().getItem(), itemPos);
                        return;
                    }
                }
            }
        }
        MCSettlers.LOGGER.info("[WorkerBrain] No gatherable items found in radius " + searchRadius + " around " + villagerPos.toShortString() + " or workstation " + workstation.toShortString());
        // If no item found, set job status to idle
        setJobStatus(brain, villager, "idle");
    }

    protected int blockBreakTime(BlockPos targetLog, VillagerEntity villager) {
        ItemStack heldItem = villager.getStackInHand(net.minecraft.util.Hand.MAIN_HAND);
        BlockState state = villager.getWorld().getBlockState(targetLog);

        // Get block hardness
        float hardness = state.getHardness(villager.getWorld(), targetLog);
        if (hardness < 0) return Integer.MAX_VALUE; // Unbreakable

        // Get tool effectiveness
        float toolSpeed = heldItem.getMiningSpeedMultiplier(state);

        // If the tool is not effective, use a default speed
        if (toolSpeed <= 1.0f) toolSpeed = 1.0f;

        // Calculate break time (formula: hardness * 30 / toolSpeed)
        int ticks = Math.round(hardness * 30.0f / toolSpeed);

        MCSettlers.LOGGER.info("[WorkerBrain] Calculated break time for " + targetLog.toShortString()
                + ": hardness=" + hardness + ", toolSpeed=" + toolSpeed + ", ticks=" + ticks + ", using tool " + heldItem.getName().getString());

        // Minimum 1 tick
        return Math.max(ticks, 1);
    }

    protected void selectToolFromInventory(BlockPos targetLog, VillagerEntity villager) {
        // Check if the villager has a tool in their inventory
        if (TARGET_BLOCK_STATE.isEmpty()) {
            return;
        }
        BlockState targetBlockState = TARGET_BLOCK_STATE.get();
        for (int i = 0; i < villager.getInventory().size(); i++) {
            net.minecraft.item.ItemStack stack = villager.getInventory().getStack(i);
            float miningSpeed = stack.getMiningSpeedMultiplier(targetBlockState);
            if (miningSpeed <= 1.0f) continue; // Not a tool or not effective
            // Set the villager's main hand to the tool
            villager.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, stack);
            villager.swingHand(net.minecraft.util.Hand.MAIN_HAND);
            MCSettlers.LOGGER.info("[WorkerBrain] Villager " + villager.getUuidAsString()
                    + " selected tool from inventory: " + stack.getName().getString());
            return; // Tool found and set, exit the loop
        }
        MCSettlers.LOGGER.info("[WorkerBrain] No tool found in inventory.");
    }

}
