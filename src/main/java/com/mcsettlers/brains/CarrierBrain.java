package com.mcsettlers.brains;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

import com.mcsettlers.MCSettlers;
import com.mcsettlers.ModMemoryModules;
import com.mcsettlers.utils.ChestAnimationHelper;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.ai.brain.Brain;

import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

class DepositChestValues {
    public BlockPos pos;
    public Set<Item> wantedItems;
    public Set<Item> containedItems;
}

public class CarrierBrain extends WorkerBrain {
    // Carrier carries items from chest to another

    @Override
    protected void handleJob(
            VillagerEntity villager, ServerWorld world,
            String jobStatus, BlockPos workstation, BlockPos targetBlock) {

        Brain<?> brain = villager.getBrain();

        if (jobStatus == "walking_to_pick_up") {
            if (!reallyReachedTarget(villager)) {
                return; // Already walking, nothing to do
            }
            startPickingUpItem(villager, world, targetBlock);
        } else if (jobStatus == "stop_picking_up_item") {
            stopPickingUpItem(villager, world, targetBlock, workstation);
        } else if (jobStatus == "deposit_items") {
            keepDepositingItems(villager, world, workstation);
        } else if (jobStatus == "stop_deposit_items") {
            stopDepositingItems(villager, world, workstation);
            // If inventory is empty, set to no_work
            if (villager.getInventory().isEmpty()) {
                setJobStatus(villager, "no_work_after_deposit");
            }
        } else if (jobStatus.startsWith("no_work")) {
            // Set timer for 10 seconds and make the villager idle
            // This is a placeholder; actual implementation would depend on game logic
            long now = world.getTime();
            Optional<Long> noWorkUntil = brain.getOptionalMemory(ModMemoryModules.NO_WORK_UNTIL_TICK);
            if (noWorkUntil.isEmpty()) {
                brain.remember(ModMemoryModules.NO_WORK_UNTIL_TICK, now + 100); // 10 seconds
            } else if (now >= noWorkUntil.get()) {
                brain.forget(ModMemoryModules.NO_WORK_UNTIL_TICK);
                setJobStatus(villager, "idle");
            }
        } else if (jobStatus == "idle") {
            // If inventory is empty, deposit items
            findNewCarryJob(villager, world);
        } else {
            MCSettlers.LOGGER.warn("[WoodcutterBrain] Unknown job status: " + jobStatus);
            setJobStatus(villager, "no_work_unknown_status");
        }
    }

    @Override
    protected Optional<BlockPos> findDepositChest(ServerWorld world, BlockPos workstation) {
        return Optional.empty();
    }

    protected void findNewCarryJob(
            VillagerEntity villager, ServerWorld world) {
        // Logic to find a new carry job, e.g., looking for items to pick up or chests
        // to deposit into

        // Deposit chest is where the villager will deposit items
        // Target break block is the chest to get item from

        BlockPos villagerPos = villager.getBlockPos();

        PriorityQueue<DepositChestValues> queue = new PriorityQueue<>(
                Comparator.comparingDouble(p -> p.pos.getSquaredDistance(villagerPos)));

        // Get a list of all deposit chests for all villagers
        for (VillagerEntity otherVillager : world.getEntitiesByType(net.minecraft.entity.EntityType.VILLAGER,
                v -> true)) {
            Brain<?> otherVillagerBrain = otherVillager.getBrain();
            Optional<BlockPos> depositChest = otherVillagerBrain.getOptionalMemory(ModMemoryModules.DEPOSIT_CHEST);
            if (depositChest.isPresent()) {
                DepositChestValues depositChestValues = new DepositChestValues();
                depositChestValues.pos = depositChest.get();
                WorkerBrain workerBrain = MCSettlers.getBrainFor(otherVillager.getVillagerData().profession());
                depositChestValues.wantedItems = new HashSet<>();
                depositChestValues.containedItems = new HashSet<>();

                BlockEntity chest = world.getBlockEntity(depositChestValues.pos);
                if (chest instanceof ChestBlockEntity) {
                    ChestBlockEntity chestEntity = (ChestBlockEntity) chest;
                    for (int i = 0; i < chestEntity.size(); i++) {
                        ItemStack stack = chestEntity.getStack(i);
                        if (stack.isEmpty()) {
                            // Add all items in WANTED_ITEMS to wantedItems, since there is emty slot
                            for (TagKey<Item> wantedTag : workerBrain.WANTED_ITEMS) {
                                for (RegistryEntry<Item> wantedItem : Registries.ITEM.iterateEntries(wantedTag)) {
                                    depositChestValues.wantedItems.add(wantedItem.value());
                                }
                            }
                            continue; // Skip empty slots
                        }

                        Item item = stack.getItem();
                        depositChestValues.containedItems.add(item);

                        // Check if the item is wanted by the worker brain
                        for (TagKey<Item> wantedTag : workerBrain.WANTED_ITEMS) {
                            if (stack.isIn(wantedTag)) {
                                // If stack is not full, add to wanted items
                                if (stack.getCount() < stack.getMaxCount()) {
                                    depositChestValues.wantedItems.add(item);
                                }
                                break;
                            }
                        }
                    }
                }
                queue.add(depositChestValues);
            }
        }

        Brain<?> brain = villager.getBrain();
        MCSettlers.LOGGER.info("Found {} deposit chests for villager: {}", queue.size(), MCSettlers.workerToString(villager));
        // Print values for each deposit chest
        for (DepositChestValues chestValuesFrom : queue) {
            for (DepositChestValues chestValuesTo : queue) {
                // If same chest, skip
                if (chestValuesFrom.pos.equals(chestValuesTo.pos)) {
                    continue;
                }
                // If chest from has items that the chest to wants, set it as target break block
                if (!chestValuesFrom.containedItems.isEmpty() &&
                        !chestValuesTo.wantedItems.isEmpty()) {
                    Item itemToCarry = null;
                    for (Item item : chestValuesFrom.containedItems) {
                        if (chestValuesTo.wantedItems.contains(item)) {
                            itemToCarry = item;
                            MCSettlers.LOGGER.info("Found item to carry: {} for villager: {}", itemToCarry,
                                    MCSettlers.workerToString(villager));
                            break; // Found an item to carry, no need to check further
                        }
                    }
                    // If no item to carry found, continue
                    if (itemToCarry == null) {
                        continue;
                    }
                    MCSettlers.LOGGER.info("Found suitable deposit chest for villager: {} -> {}", MCSettlers.workerToString(villager),
                            chestValuesTo.pos);
                    brain.remember(ModMemoryModules.TARGET_BREAK_BLOCK, chestValuesFrom.pos);
                    brain.remember(ModMemoryModules.DEPOSIT_CHEST, chestValuesTo.pos);
                    brain.remember(ModMemoryModules.ITEM_TO_CARRY, itemToCarry);
                    // Set walk target with reasonable completion range and duration
                    walkToPosition(villager, world, chestValuesFrom.pos, 0.6F);
                    setJobStatus(villager, "walking_to_pick_up");
                    return;
                }
            }
        }
        // If no suitable deposit chest found, set job status to no work
        MCSettlers.LOGGER.info("No suitable deposit chest found for villager: {}", MCSettlers.workerToString(villager));
        setJobStatus(villager, "no_work_no_chest");
        brain.forget(ModMemoryModules.TARGET_BREAK_BLOCK);
        brain.forget(ModMemoryModules.DEPOSIT_CHEST);
        brain.forget(ModMemoryModules.ITEM_TO_CARRY);
    }

    protected void startPickingUpItem(
            VillagerEntity villager, ServerWorld world,
            BlockPos targetBlock) {
        BlockEntity blockEntity = world.getBlockEntity(targetBlock);
        if (!(blockEntity instanceof ChestBlockEntity)) {
            MCSettlers.LOGGER.warn("Target block is not a chest: {}", targetBlock);
            return;
        }
        ChestBlockEntity chest = (ChestBlockEntity) world.getBlockEntity(targetBlock);
        if (chest == null || chest.isEmpty()) {
            MCSettlers.LOGGER.warn("Chest at {} is empty or does not exist.", targetBlock);
            return;
        }

        // Find itemstack to carry
        Item itemToCarry = villager.getBrain().getOptionalMemory(ModMemoryModules.ITEM_TO_CARRY)
                .orElse(null);
        if (itemToCarry == null) {
            MCSettlers.LOGGER.warn("No item to carry found for villager: {}", MCSettlers.workerToString(villager));
            return;
        }

        // Check if the chest contains the item to carry
        lookAtBlock(villager, targetBlock);
        ChestAnimationHelper.animateChest(world, targetBlock, true);

        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (stack.getItem() == itemToCarry && stack.getCount() > 0) {
                // Add one item to villager's inventory
                ItemStack carriedStack = stack.copy();
                carriedStack.setCount(1); // Take only one item
                villager.getInventory().addStack(carriedStack);
                // Show item in hand
                chest.removeStack(i, 1); // Remove one item from chest
                break;
            }
        }

        setJobStatus(villager, "stop_picking_up_item");
        pauseForMS(villager, world, 1000);
    }

    protected void stopDepositingItems(
            VillagerEntity villager, ServerWorld world,
            BlockPos workstation) {
        super.stopDepositingItems(villager, world, workstation);
        // Stop holding item in hand
        startHoldingItem(villager, ItemStack.EMPTY);
        setJobStatus(villager, "no_work_after_deposit");
    }

    protected void stopPickingUpItem(
            VillagerEntity villager, ServerWorld world,
            BlockPos targetBlock, BlockPos workstation) {
        Brain<?> brain = villager.getBrain();
        // Logic to stop picking up item, e.g., resetting hand and chest animation
        ChestAnimationHelper.animateChest(world, targetBlock, false);
        startDepositingItems(villager, world, workstation);
        Item itemToCarry = brain.getOptionalMemory(ModMemoryModules.ITEM_TO_CARRY)
                .orElse(null);

        if (itemToCarry != null) {
            for (int i = 0; i < villager.getInventory().size(); i++) {
                ItemStack stack = villager.getInventory().getStack(i);
                if (stack.getItem() == itemToCarry && stack.getCount() > 0) {
                    startHoldingItem(villager, stack);
                    break; // Found the item to carry, set it in hand
                }
            }
        }

        // Set walk target to deposit chest
        BlockPos depositChest = brain.getOptionalMemory(ModMemoryModules.DEPOSIT_CHEST)
                .orElse(null);
        if (depositChest != null) {
            walkToPosition(villager, world, depositChest, 0.6F);
        } else {
            MCSettlers.LOGGER.warn("No deposit chest found for villager: {}", MCSettlers.workerToString(villager));
        }
    }
}
